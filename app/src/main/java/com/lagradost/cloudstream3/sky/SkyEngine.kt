package com.lagradost.cloudstream3.sky

import android.content.Context
import android.util.Log
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The SkyStream host bridge: executes a plugin's http_get/http_post/
 * http_parallel calls on OkHttp, modelled on NuvioHostBridge (shared
 * client, per-session cookie jar, browser UA default) but returning
 * the SkyStream response shape {status, body, headers} with
 * lowercased header names and the reference's 8 MB body cap.
 * Preferences resolve through SkyRepository (per-plugin store).
 */
class SkyHostBridge(
    private val appContext: Context,
    private val packageName: String,
    private val subId: String?,
) {
    /** Completed by the skyComplete binding with the envelope JSON. */
    @Volatile
    var pending: CompletableDeferred<String>? = null

    private val cookies = mutableMapOf<String, MutableList<String>>()

    suspend fun httpAsync(requestJson: String): String {
        return withContext(Dispatchers.IO) {
            try {
                doHttp(JSONObject(requestJson)).toString()
            } catch (e: Exception) {
                JSONObject()
                    .put("status", 0)
                    .put("body", "")
                    .put("headers", JSONObject())
                    .put("error", e.message ?: "http failed")
                    .toString()
            }
        }
    }

    private fun doHttp(p: JSONObject): JSONObject {
        val url = p.optString("url")
        val method = p.optString("method", "GET").uppercase()
        val host = runCatching { URI(url).host ?: "" }.getOrDefault("")
        val builder = Request.Builder().url(url)
        var contentType: String? = null
        var hasUserAgent = false
        var hasAcceptEncoding = false
        var sawCookie = false
        val headers = p.optJSONObject("headers")
        if (headers != null) {
            val keys = headers.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = headers.optString(key)
                if (key.equals("content-type", ignoreCase = true)) contentType = value
                if (key.equals("user-agent", ignoreCase = true)) hasUserAgent = true
                if (key.equals("accept-encoding", ignoreCase = true)) hasAcceptEncoding = true
                if (key.equals("cookie", ignoreCase = true)) sawCookie = true
                builder.header(key, value)
            }
        }
        if (!hasUserAgent) builder.header("User-Agent", SkyHttp.SKY_USER_AGENT)
        // SkyStream parity (8.80): the reference bridge forces
        // Accept-Encoding: identity unless the plugin set one, so
        // responses are never gzip-wrapped behind the plugin's back.
        if (!hasAcceptEncoding) builder.header("Accept-Encoding", "identity")
        synchronized(cookies) {
            val jar = cookies[host]
            if (!jar.isNullOrEmpty() && !sawCookie) {
                builder.header("Cookie", jar.joinToString("; "))
            }
        }
        val bodyText = if (p.has("body") && !p.isNull("body")) {
            p.optString("body")
        } else {
            null
        }
        when (method) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            "DELETE" -> {
                if (bodyText != null) {
                    builder.delete(
                        bodyText.toRequestBody(contentType?.toMediaTypeOrNull())
                    )
                } else {
                    builder.delete()
                }
            }
            else -> {
                // SkyStream parity (8.80): POST/PUT default to
                // application/x-www-form-urlencoded when the plugin
                // did not send a Content-Type.
                val effectiveType = contentType
                    ?: if (method == "POST" || method == "PUT") {
                        "application/x-www-form-urlencoded"
                    } else {
                        null
                    }
                val body = (bodyText ?: "")
                    .toRequestBody(effectiveType?.toMediaTypeOrNull())
                when (method) {
                    "POST" -> builder.post(body)
                    "PUT" -> builder.put(body)
                    "PATCH" -> builder.patch(body)
                    else -> builder.method(method, body)
                }
            }
        }
        val response = SkyHttp.client.newCall(builder.build()).execute()
        return response.use { resp ->
            // SkyStream parity (8.80): the reference bridge exposes
            // Set-Cookie to plugins as a header list — plugins keep
            // their own cookie store from it (katdrama's locker chain
            // silently stalls without it) — while ALSO feeding the
            // host-side jar below.
            val setCookies = resp.headers.values("Set-Cookie")
            setCookies.forEach { setCookie ->
                val pair = setCookie.substringBefore(';').trim()
                if (pair.contains('=')) {
                    synchronized(cookies) {
                        val jar = cookies.getOrPut(host) { mutableListOf() }
                        val name = pair.substringBefore('=')
                        jar.removeAll { it.substringBefore('=') == name }
                        jar.add(pair)
                    }
                }
            }
            val rawBytes = resp.body?.bytes() ?: ByteArray(0)
            val capped = if (rawBytes.size > MAX_BODY_BYTES) {
                rawBytes.copyOf(MAX_BODY_BYTES)
            } else {
                rawBytes
            }
            val charset = resp.body?.contentType()?.charset(Charsets.UTF_8)
                ?: Charsets.UTF_8
            val headersOut = JSONObject()
            resp.headers.forEach { (name, value) ->
                val key = name.lowercase()
                if (key == "set-cookie") return@forEach
                headersOut.put(
                    key,
                    if (headersOut.has(key)) {
                        headersOut.optString(key) + ", " + value
                    } else {
                        value
                    }
                )
            }
            if (setCookies.isNotEmpty()) {
                headersOut.put("set-cookie", JSONArray(setCookies.toList()))
            }
            // SkyStream parity (8.80): plugins read finalUrl to learn
            // where a redirect landed (the kmhd /file/ -> /locked
            // hop); without it their chain-walkers stall.
            val finalUrl = resp.request.url.toString()
            JSONObject()
                .put("status", resp.code)
                .put("code", resp.code)
                .put("statusCode", resp.code)
                .put("body", String(capped, charset))
                .put("headers", headersOut)
                .put("finalUrl", finalUrl)
                .put("url", finalUrl)
        }
    }

    fun prefGet(key: String): String? =
        SkyRepository.prefGet(appContext, packageName, subId, key)

    fun prefSet(key: String, value: String?) {
        // The polyfill stores JSON.stringify(value); "null" means remove,
        // exactly like the spike bridge.
        SkyRepository.prefSet(
            appContext, packageName, subId, key,
            if (value == "null") null else value,
        )
    }

    companion object {
        private const val MAX_BODY_BYTES = 8 * 1024 * 1024
    }
}

/**
 * One persistent QuickJS context for one plugin instance
 * (packageName + sub-provider). SkyStream plugins keep session state
 * in globals across calls (domain caches, tokens), so — unlike the
 * Nuvio bridge's fresh context per call — the context is created
 * once, owned by a worker coroutine that consumes invoke jobs from a
 * channel, and closed on eviction. Invokes are serialized by a mutex
 * (the reference serializes per provider too), and the envelope
 * completion is awaited INSIDE the quickJs block, the shape the S2
 * spike verified against a real plugin.
 */
class SkyPluginSession(
    private val appContext: Context,
    val plugin: StoredSkyPlugin,
    private val manifest: SkyPluginManifest,
    val subId: String?,
    /** effectiveBaseUrl + subId; a mismatch recreates the context. */
    val stamp: String,
) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = Channel<InvokeJob>(Channel.UNLIMITED)
    private val ready = CompletableDeferred<Boolean>()

    @Volatile
    private var worker: Job? = null

    @Volatile
    var closed: Boolean = false
        private set

    @Volatile
    private var startError: String? = null

    private class InvokeJob(
        val name: String,
        val argsJson: String,
        val result: CompletableDeferred<JSONObject?>,
    )

    fun start() {
        worker = scope.launch { runWorker() }
    }

    private fun manifestForJs(): String {
        val obj = runCatching { JSONObject(manifest.rawJson) }
            .getOrElse { JSONObject() }
        obj.put(
            "baseUrl",
            SkyRepository.effectiveBaseUrl(
                appContext, manifest,
                manifest.subProviders.firstOrNull { it.id == subId },
            ),
        )
        if (subId != null) obj.put("providerId", subId)
        return obj.toString()
    }

    private suspend fun runWorker() {
        val bridge = SkyHostBridge(appContext, plugin.packageName, subId)
        try {
            quickJs(Dispatchers.Default) {
                defineBinding(
                    "skyHttpAsync",
                    object : AsyncFunctionBinding<String> {
                        override suspend fun invoke(args: Array<Any?>): String =
                            bridge.httpAsync(args.firstOrNull() as? String ?: "{}")
                    }
                )
                defineBinding(
                    "skySleepAsync",
                    object : AsyncFunctionBinding<Any?> {
                        override suspend fun invoke(args: Array<Any?>): Any? {
                            val ms = (args.firstOrNull() as? Number)?.toLong() ?: 0L
                            delay(ms.coerceIn(0L, 30_000L))
                            return null
                        }
                    }
                )
                defineBinding(
                    "skyComplete",
                    object : FunctionBinding<Any?> {
                        override fun invoke(args: Array<Any?>): Any? {
                            val value = args.firstOrNull() as? String ?: "{}"
                            bridge.pending?.let {
                                if (!it.isCompleted) it.complete(value)
                            }
                            return null
                        }
                    }
                )
                defineBinding(
                    "skyPrefGet",
                    object : FunctionBinding<String?> {
                        override fun invoke(args: Array<Any?>): String? =
                            bridge.prefGet(args.firstOrNull() as? String ?: "")
                    }
                )
                defineBinding(
                    "skyPrefSet",
                    object : FunctionBinding<Any?> {
                        override fun invoke(args: Array<Any?>): Any? {
                            val key = args.getOrNull(0) as? String ?: return null
                            bridge.prefSet(key, args.getOrNull(1) as? String)
                            return null
                        }
                    }
                )
                defineBinding(
                    "skyLog",
                    object : FunctionBinding<Any?> {
                        override fun invoke(args: Array<Any?>): Any? {
                            Log.d(
                                "SkyJS",
                                "[${plugin.packageName}] " +
                                    (args.firstOrNull()?.toString() ?: "")
                            )
                            return null
                        }
                    }
                )
                defineBinding(
                    "skyNativeSync",
                    object : FunctionBinding<String?> {
                        override fun invoke(args: Array<Any?>): String? =
                            SkyNative.sync(
                                args.getOrNull(0) as? String ?: "",
                                args.getOrNull(1) as? String ?: "",
                            )
                    }
                )
                defineBinding(
                    "skyNativeAsync",
                    object : AsyncFunctionBinding<String> {
                        override suspend fun invoke(args: Array<Any?>): String =
                            SkyNative.asyncOp(
                                args.getOrNull(0) as? String ?: "",
                                args.getOrNull(1) as? String ?: "",
                            )
                    }
                )
                evaluate<Any?>(SkyPolyfill.CODE, "sky-polyfill.js")
                evaluate<Any?>(
                    "globalThis.manifest = " + manifestForJs() + ";",
                    "sky-manifest.js",
                )
                val code = SkyRepository.readCode(appContext, plugin)
                    ?: throw IllegalStateException("plugin.js missing")
                evaluate<Any?>(code, "plugin.js")
                // Sacrificial drain (spike learning): plugins can leave
                // a pending-job TypeError ('cannot convert symbol to
                // string') behind at load time; evaluating once inside
                // runCatching absorbs it here instead of poisoning the
                // first real invoke.
                runCatching { evaluate<Any?>("undefined", "sky-drain.js") }
                if (!ready.isCompleted) ready.complete(true)
                for (job in jobs) {
                    val deferred = CompletableDeferred<String>()
                    bridge.pending = deferred
                    val envelope = try {
                        evaluate<Any?>(
                            "__sky_call(" + JSONObject.quote(job.name) + ", " +
                                JSONObject.quote(job.argsJson) + ")",
                            "sky-call.js",
                        )
                        val raw = withTimeoutOrNull(INVOKE_TIMEOUT_MS) {
                            deferred.await()
                        }
                        raw?.let {
                            runCatching { JSONObject(it) }.getOrNull()
                        }
                    } catch (e: Exception) {
                        Log.d(
                            "SkyJS",
                            "[${plugin.packageName}] invoke ${job.name} failed: ${e.message}"
                        )
                        null
                    } finally {
                        bridge.pending = null
                    }
                    job.result.complete(envelope)
                }
            }
        } catch (e: Exception) {
            startError = e.message ?: e.javaClass.simpleName
            Log.d(
                "SkyJS",
                "[${plugin.packageName}] context failed: $startError"
            )
            if (!ready.isCompleted) ready.complete(false)
        } finally {
            closed = true
            // Fail any jobs still queued so their callers do not hang.
            while (true) {
                val job = jobs.tryReceive().getOrNull() ?: break
                job.result.complete(null)
            }
        }
    }

    /**
     * Invokes one exported function with the callback envelope.
     * @return the parsed envelope ({success, data, ...}) or null on
     * timeout / engine failure. Never throws.
     */
    suspend fun invoke(name: String, args: List<Any?>): JSONObject? {
        if (closed) return null
        return mutex.withLock {
            if (closed) return@withLock null
            val started = withTimeoutOrNull(START_TIMEOUT_MS) { ready.await() }
            if (started != true) {
                Log.d(
                    "SkyJS",
                    "[${plugin.packageName}] not ready (error=$startError)"
                )
                return@withLock null
            }
            val job = InvokeJob(name, JSONArray(args).toString(), CompletableDeferred())
            if (jobs.trySend(job).isFailure) return@withLock null
            withTimeoutOrNull(INVOKE_TIMEOUT_MS + 5_000L) { job.result.await() }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        jobs.close()
        worker?.cancel()
    }

    companion object {
        const val INVOKE_TIMEOUT_MS = 60_000L
        private const val START_TIMEOUT_MS = 30_000L
    }
}

/**
 * Registry of live plugin contexts, LRU-evicted past [MAX_CONTEXTS]
 * — persistent contexts are the bridge's main memory cost on the
 * user's Android 8.1 phone, so at most four plugins stay warm.
 */
object SkyEngine {

    private const val MAX_CONTEXTS = 4

    private val sessions =
        LinkedHashMap<String, SkyPluginSession>(8, 0.75f, true)

    fun instanceKey(packageName: String, subId: String?): String =
        packageName + "|" + (subId ?: "")

    @Synchronized
    private fun sessionFor(
        ctx: Context,
        plugin: StoredSkyPlugin,
        subId: String?,
    ): SkyPluginSession? {
        val manifest = plugin.manifest() ?: return null
        val sub = manifest.subProviders.firstOrNull { it.id == subId }
        val stamp = SkyRepository.effectiveBaseUrl(ctx, manifest, sub) +
            "|" + (subId ?: "")
        val key = instanceKey(plugin.packageName, subId)
        val existing = sessions[key]
        if (existing != null && !existing.closed && existing.stamp == stamp) {
            return existing
        }
        // Base URL or sub-provider changed under a live context:
        // recreate it so the new manifest takes effect.
        if (existing != null) {
            existing.close()
            sessions.remove(key)
        }
        val session = SkyPluginSession(
            ctx.applicationContext, plugin, manifest, subId, stamp
        )
        session.start()
        sessions[key] = session
        while (sessions.size > MAX_CONTEXTS) {
            val eldestKey = sessions.keys.firstOrNull { it != key } ?: break
            sessions.remove(eldestKey)?.close()
        }
        return session
    }

    /**
     * Invokes [name] on the plugin instance's persistent context.
     * @return the callback envelope, or null when the plugin/context
     * failed. Never throws.
     */
    suspend fun invoke(
        ctx: Context,
        plugin: StoredSkyPlugin,
        subId: String?,
        name: String,
        args: List<Any?> = emptyList(),
    ): JSONObject? {
        val session = runCatching { sessionFor(ctx, plugin, subId) }
            .getOrNull() ?: return null
        val envelope = runCatching { session.invoke(name, args) }.getOrNull()
        if (envelope == null && session.closed) {
            // Dead context (timeout, crash): drop it so the next call
            // rebuilds instead of reusing a corpse.
            evict(plugin.packageName, subId)
        }
        return envelope
    }

    @Synchronized
    fun evict(packageName: String, subId: String?) {
        sessions.remove(instanceKey(packageName, subId))?.close()
    }

    /** Drops every live context of one plugin (remove/disable/settings). */
    @Synchronized
    fun evictPackage(packageName: String) {
        val keys = sessions.keys.filter { it.startsWith("$packageName|") }
        keys.forEach { key -> sessions.remove(key)?.close() }
    }

    /** Keeps only the wanted instances alive (registry sync). */
    @Synchronized
    fun retainOnly(wantedKeys: Set<String>) {
        val keys = sessions.keys.filter { it !in wantedKeys }
        keys.forEach { key -> sessions.remove(key)?.close() }
    }
}

/**
 * Native-assist operations behind the polyfill's sendMessage /
 * crypto.* surface, with SkyStream's own semantics
 * (lib/core/extensions/engine/aes_decrypt.dart + the worker's
 * channel handlers): base64 payloads are whitespace-stripped and
 * re-padded before decoding (URL-safe tolerated), AES is CBC with
 * PKCS7 for any mode except "gcm" (GCM, 128-bit tag appended) and
 * "ecb", hashes are lowercase hex of the UTF-8 input, and PBKDF2 is
 * HMAC-SHA256 (hand-rolled: SecretKeyFactory's PBKDF2WithHmacSHA256
 * only exists on API 26+, we target 23). Verified in the spike
 * against CastleTV's encrypted payloads.
 */
object SkyNative {

    fun b64DecodeTolerant(s: String): ByteArray {
        var cleaned = s.replace(Regex("\\s"), "").replace('-', '+').replace('_', '/')
        while (cleaned.length % 4 != 0) cleaned += "="
        return android.util.Base64.decode(cleaned, android.util.Base64.DEFAULT)
    }

    private fun payloadString(payload: String): String =
        runCatching { JSONArray("[$payload]").optString(0) }.getOrDefault(payload)

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    fun sync(channel: String, payload: String): String? {
        return try {
            when (channel) {
                "base64_encode" -> android.util.Base64.encodeToString(
                    payloadString(payload).toByteArray(Charsets.UTF_8),
                    android.util.Base64.NO_WRAP,
                )
                "base64_decode" -> String(b64DecodeTolerant(payloadString(payload)), Charsets.UTF_8)
                "crypto_md5" -> hex(
                    MessageDigest.getInstance("MD5")
                        .digest(payloadString(payload).toByteArray(Charsets.UTF_8))
                )
                "crypto_sha256" -> hex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(payloadString(payload).toByteArray(Charsets.UTF_8))
                )
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun asyncOp(channel: String, payload: String): String {
        val data = JSONObject(payload)
        return when (channel) {
            "crypto_decrypt_aes" -> aesDecrypt(
                data.optString("data"),
                data.optString("key"),
                data.optString("iv"),
                data.optString("mode", "cbc"),
            )
            "crypto_pbkdf2" -> android.util.Base64.encodeToString(
                pbkdf2Sha256(
                    data.optString("password"),
                    b64DecodeTolerant(data.optString("salt")),
                    data.optInt("iterations", 10000),
                    data.optInt("keyLength", 32),
                ),
                android.util.Base64.NO_WRAP,
            )
            else -> throw IllegalArgumentException("unknown channel $channel")
        }
    }

    private fun aesDecrypt(dataB64: String, keyB64: String, ivB64: String, mode: String): String {
        val keyBytes = b64DecodeTolerant(keyB64)
        val cipher = when (mode.lowercase()) {
            "gcm" -> Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(keyBytes, "AES"),
                    GCMParameterSpec(128, b64DecodeTolerant(ivB64)),
                )
            }
            "ecb" -> Cipher.getInstance("AES/ECB/PKCS5Padding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
            }
            else -> Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(keyBytes, "AES"),
                    IvParameterSpec(b64DecodeTolerant(ivB64)),
                )
            }
        }
        // String(bytes, UTF_8) replaces malformed sequences instead of
        // throwing — the reference's allowMalformed behaviour.
        return String(cipher.doFinal(b64DecodeTolerant(dataB64)), Charsets.UTF_8)
    }

    private fun pbkdf2Sha256(
        password: String,
        salt: ByteArray,
        iterations: Int,
        keyLen: Int,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val out = ByteArray(keyLen)
        var offset = 0
        var block = 1
        while (offset < keyLen) {
            mac.update(salt)
            var u = mac.doFinal(
                byteArrayOf(
                    (block ushr 24).toByte(),
                    (block ushr 16).toByte(),
                    (block ushr 8).toByte(),
                    block.toByte(),
                )
            )
            val t = u.copyOf()
            for (i in 1 until iterations) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            val len = minOf(t.size, keyLen - offset)
            System.arraycopy(t, 0, out, offset, len)
            offset += len
            block++
        }
        return out
    }
}
