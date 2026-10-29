package com.lagradost.cloudstream3.skystream

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.preference.PreferenceManager
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import com.dokar.quickjs.quickJs
import com.lagradost.cloudstream3.nuvio.NuvioPolyfill
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Executes SkyStream's JavaScript provider contract in the existing QuickJS host.
 * Exports are called using SkyStream's real API names: getHome, search, load and loadStreams.
 */
object SkyStreamEngine {
    private const val TIMEOUT_MS = 40_000L
    private const val MAX_HTTP_BODY_BYTES = 8 * 1024 * 1024

    suspend fun invoke(
        context: Context,
        repoUrl: String,
        plugin: StoredSkyStreamPlugin,
        providerId: String?,
        functionName: String,
        args: List<Any?> = emptyList(),
        customBaseUrl: String? = null,
        timeoutMs: Long = TIMEOUT_MS,
    ): Any? {
        val code = SkyStreamRepository.script(context, repoUrl, plugin.packageName)
            ?: throw IllegalStateException("Plugin script is missing; refresh the repository")
        require(code.isNotBlank()) { "Plugin script is empty" }
        val bridge = SkyStreamHostBridge(context.applicationContext, plugin, repoUrl)
        val manifest = plugin.manifest().apply {
            if (!providerId.isNullOrBlank()) put("providerId", providerId)
            if (!customBaseUrl.isNullOrBlank()) put("baseUrl", customBaseUrl)
        }
        val argArray = JSONArray()
        args.forEach { arg ->
            argArray.put(when (arg) {
                null -> JSONObject.NULL
                is JSONObject, is JSONArray, is String, is Number, is Boolean -> arg
                else -> arg.toString()
            })
        }

        val resultJson = withTimeoutOrNull(timeoutMs) {
            quickJs(Dispatchers.Default) {
                defineBinding(
                    "skyStreamCall",
                    object : FunctionBinding<String?> {
                        override fun invoke(args: Array<Any?>): String? =
                            bridge.callSync(args.firstOrNull() as? String ?: "{}")
                    }
                )
                defineBinding(
                    "skyStreamFetchAsync",
                    object : AsyncFunctionBinding<String> {
                        override suspend fun invoke(args: Array<Any?>): String =
                            bridge.fetchAsync(args.firstOrNull() as? String ?: "{}")
                    }
                )
                defineBinding(
                    "skyStreamSleepAsync",
                    object : AsyncFunctionBinding<Any?> {
                        override suspend fun invoke(args: Array<Any?>): Any? {
                            val ms = (args.firstOrNull() as? Number)?.toLong() ?: 0L
                            delay(ms.coerceIn(0L, 30_000L))
                            return null
                        }
                    }
                )
                // Reuse the app's more complete JS compatibility layer (fetch, XHR,
                // URL, TextEncoder/Decoder, Buffer, axios, cheerio and CryptoJS).
                defineBinding(
                    "nuvioCall",
                    object : FunctionBinding<String?> {
                        override fun invoke(args: Array<Any?>): String? =
                            bridge.callSync(args.firstOrNull() as? String ?: "{}")
                    }
                )
                defineBinding(
                    "nuvioFetchAsync",
                    object : AsyncFunctionBinding<String> {
                        override suspend fun invoke(args: Array<Any?>): String =
                            bridge.fetchAsync(args.firstOrNull() as? String ?: "{}")
                    }
                )
                defineBinding(
                    "nuvioSleepAsync",
                    object : AsyncFunctionBinding<Any?> {
                        override suspend fun invoke(args: Array<Any?>): Any? {
                            val ms = (args.firstOrNull() as? Number)?.toLong() ?: 0L
                            delay(ms.coerceIn(0L, 30_000L))
                            return null
                        }
                    }
                )
                evaluate<Any?>(NuvioPolyfill.build("{}", "null"), "skystream-shared-polyfill.js")
                evaluate<Any?>(buildHostPolyfill(manifest), "skystream-host-polyfill.js")
                evaluate<Any?>(buildPluginWrapper(code), "skystream-plugin.js")
                val call = buildInvocation(functionName, argArray)
                val evalText = runCatching { evaluate<String>(call, "skystream-call.js") }.getOrNull()
                when {
                    bridge.resultJson != null -> bridge.resultJson
                    !evalText.isNullOrBlank() && evalText.trimStart().startsWith("{") -> evalText
                    !evalText.isNullOrBlank() && evalText.trimStart().startsWith("[") -> evalText
                    else -> bridge.resultDeferred.await()
                }
            }
        } ?: throw IllegalStateException("SkyStream plugin timed out in " + functionName)

        val raw = resultJson ?: bridge.resultJson
            ?: throw IllegalStateException("SkyStream plugin returned no result for " + functionName)
        return runCatching { JSONTokener(raw).nextValue() }
            .getOrElse { throw IllegalStateException("SkyStream plugin returned invalid JSON: " + it.message) }
            .let { if (it === JSONObject.NULL) null else it }
    }

    private fun buildInvocation(functionName: String, args: JSONArray): String {
        val functionLiteral = JSONObject.quote(functionName)
        return """
(async function() {
  const exports = globalThis.__skyStreamExports || {};
  const fn = exports[$functionLiteral];
  if (typeof fn !== 'function') {
    skyStreamCall(JSON.stringify({op:'result', data:JSON.stringify({__sky_error:'Missing export: '+$functionLiteral})}));
    return null;
  }
  try {
    const value = await fn.apply(null, $args);
    skyStreamCall(JSON.stringify({op:'result', data:JSON.stringify(value === undefined ? null : value)}));
    return value;
  } catch (e) {
    skyStreamCall(JSON.stringify({op:'result', data:JSON.stringify({__sky_error:String(e && (e.stack || e.message) || e)})}));
    return null;
  }
})()
""".trimIndent()
    }

    private fun buildPluginWrapper(code: String): String {
        // Mirror SkyStream's source wrapper: plugin declarations live in a nested scope,
        // which avoids collisions with the host's exports/module bindings.
        return """
globalThis.__skyStreamExports = (function() {
  const pluginExports = (function() {
    const manifest = globalThis.manifest;
    const exports = {};
    const module = { exports: exports };
    const require = globalThis.require || function(name) {
      if (name === 'url') return { URL: URL, URLSearchParams: URLSearchParams };
      return {};
    };
    try {
      $code
    } catch (e) {
      globalThis.__skyStreamLoadError = String(e && (e.stack || e.message) || e);
      throw e;
    }
    const sourceExports = (module && module.exports && Object.keys(module.exports).length)
        ? module.exports : exports;
    return {
      getHome: (typeof getHome !== 'undefined') ? getHome : (sourceExports.getHome || globalThis.getHome),
      search: (typeof search !== 'undefined') ? search : (sourceExports.search || globalThis.search),
      load: (typeof load !== 'undefined') ? load : (sourceExports.load || globalThis.load),
      loadStreams: (typeof loadStreams !== 'undefined') ? loadStreams : (sourceExports.loadStreams || globalThis.loadStreams),
      getProviders: (typeof getProviders !== 'undefined') ? getProviders : (sourceExports.getProviders || globalThis.getProviders),
      getSettings: (typeof getSettings !== 'undefined') ? getSettings : (sourceExports.getSettings || globalThis.getSettings)
    };
  })();
  return pluginExports;
})();
""".trimIndent()
    }

    private fun buildHostPolyfill(manifest: JSONObject): String {
        val manifestLiteral = manifest.toString()
        return """
(function() {
  globalThis.manifest = $manifestLiteral;
  globalThis.window = globalThis;
  globalThis.self = globalThis;
  globalThis.global = globalThis;
  globalThis.getPreference = (key) => {
    const raw = skyStreamCall(JSON.stringify({op:'get_preference', key:String(key)}));
    try { const obj = JSON.parse(raw || '{}'); return obj.value == null ? null : obj.value; } catch(e) { return null; }
  };
  globalThis.setPreference = (key, value) =>
    skyStreamCall(JSON.stringify({op:'set_preference', key:String(key), value:value}));
  const legacyResponse = async (response) => {
    const body = await response.text();
    const headers = {};
    if (response.headers && response.headers.forEach) response.headers.forEach((v,k) => headers[k] = v);
    return {status:response.status, statusCode:response.status, code:response.status,
      body:body, bodyText:body, data:body, content:body, url:response.url,
      finalUrl:response.url, headers:headers};
  };
  globalThis.http_get = (url, headers={}, callback) =>
    globalThis.fetch(url, {method:'GET', headers:headers || {}})
      .then(legacyResponse).then(r => { if (typeof callback === 'function') callback(r); return r; });
  globalThis.http_post = (url, headers={}, body='', callback) =>
    globalThis.fetch(url, {method:'POST', headers:headers || {}, body:body || ''})
      .then(legacyResponse).then(r => { if (typeof callback === 'function') callback(r); return r; });
  globalThis.http_parallel = (requests) => Promise.all((requests || []).map(r =>
    globalThis.fetch(r.url, {method:r.method || 'GET', headers:r.headers || {}, body:r.body || ''})
      .then(legacyResponse)));
  globalThis._fetch = (url) => globalThis.http_get(url);
  globalThis.__skyStreamExports = {};
})();
""".trimIndent()
    }

    private class SkyStreamHostBridge(
        private val context: Context,
        private val plugin: StoredSkyStreamPlugin,
        private val repoUrl: String,
    ) {
        @Volatile var resultJson: String? = null
            private set
        val resultDeferred = CompletableDeferred<String>()
        private val cookies = mutableMapOf<String, MutableList<String>>()
        private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(32, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        fun callSync(requestJson: String): String? = runCatching {
            val outer = JSONObject(requestJson)
            val req = outer.optJSONObject("payload") ?: outer
            val key = req.optString("key")
            when (outer.optString("op").ifBlank { req.optString("op") }) {
                "result" -> {
                    val data = req.optString("data", "null")
                    resultJson = data
                    if (!resultDeferred.isCompleted) resultDeferred.complete(data)
                    "{}"
                }
                "log" -> {
                    Log.d("SkyStreamJS", "[" + plugin.packageName + "] " + req.optString("message"))
                    "{}"
                }
                "get_preference" -> JSONObject().put("value",
                    prefs.getString(plugin.packageName + ":" + key, null) ?: JSONObject.NULL).toString()
                "set_preference" -> {
                    val value = req.opt("value")
                    prefs.edit().putString(
                        plugin.packageName + ":" + key,
                        if (value == null || value === JSONObject.NULL) null else if (value is String) value else value.toString()
                    ).apply()
                    "{}"
                }
                "storage_get", "storageGet" -> JSONObject().put(
                    "value", prefs.getString(storageKey(key), null) ?: JSONObject.NULL
                ).toString()
                "storage_set", "storageSet" -> {
                    val value = req.opt("value")
                    prefs.edit().putString(
                        storageKey(key),
                        if (value == null || value === JSONObject.NULL) null else if (value is String) value else value.toString()
                    ).apply()
                    "{}"
                }
                "storage_remove" -> {
                    prefs.edit().remove(storageKey(key)).apply()
                    "{}"
                }
                "storage_clear", "storageClear" -> {
                    val allKeys = prefs.all.keys.filter { it.startsWith(plugin.packageName + ":storage:") }
                    prefs.edit().also { edit -> allKeys.forEach { edit.remove(it) } }.apply()
                    "{}"
                }
                "crypto" -> doCrypto(req).toString()
                else -> JSONObject().put("error", "Unsupported bridge operation: " + outer.optString("op")).toString()
            }
        }.getOrElse { JSONObject().put("error", it.message ?: "bridge error").toString() }

        private fun b64Decode(value: String?): ByteArray =
            runCatching { Base64.decode(value ?: "", Base64.DEFAULT) }.getOrElse { ByteArray(0) }

        private fun b64Encode(bytes: ByteArray): String =
            Base64.encodeToString(bytes, Base64.NO_WRAP)

        private fun doCrypto(payload: JSONObject): JSONObject {
            val out = JSONObject()
            when (val algo = payload.optString("algo")) {
                "md5", "sha1", "sha256", "sha512" -> {
                    val digest = when (algo) {
                        "md5" -> "MD5"
                        "sha1" -> "SHA-1"
                        "sha256" -> "SHA-256"
                        else -> "SHA-512"
                    }
                    out.put("dataB64", b64Encode(
                        MessageDigest.getInstance(digest).digest(b64Decode(payload.optString("dataB64")))
                    ))
                }
                "hmac-sha1", "hmac-sha256" -> {
                    val mac = Mac.getInstance(if (algo == "hmac-sha1") "HmacSHA1" else "HmacSHA256")
                    mac.init(SecretKeySpec(b64Decode(payload.optString("keyB64")), mac.algorithm))
                    out.put("dataB64", b64Encode(mac.doFinal(b64Decode(payload.optString("dataB64")))))
                }
                "random" -> {
                    val bytes = ByteArray(payload.optInt("n", 16).coerceIn(1, 1024))
                    SecureRandom().nextBytes(bytes)
                    out.put("dataB64", b64Encode(bytes))
                }
                "aes-encrypt", "aes-decrypt" -> {
                    val encrypt = algo == "aes-encrypt"
                    val mode = payload.optString("mode", "CBC")
                    val padding = payload.optString("padding", "Pkcs7")
                    var bytes = b64Decode(payload.optString("dataB64"))
                    val cipherPadding = if (padding == "NoPadding" || padding == "ZeroPadding") "NoPadding" else "PKCS5Padding"
                    if (padding == "ZeroPadding" && encrypt) {
                        val remainder = bytes.size % 16
                        if (remainder != 0) bytes += ByteArray(16 - remainder)
                    }
                    val cipher = Cipher.getInstance("AES/" + mode + "/" + cipherPadding)
                    val keyBytes = b64Decode(payload.optString("keyB64"))
                    val secretKey = SecretKeySpec(keyBytes, "AES")
                    if (mode == "ECB") {
                        cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, secretKey)
                    } else {
                        cipher.init(
                            if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                            secretKey,
                            IvParameterSpec(b64Decode(payload.optString("ivB64")))
                        )
                    }
                    var result = cipher.doFinal(bytes)
                    if (padding == "ZeroPadding" && !encrypt) {
                        var end = result.size
                        while (end > 0 && result[end - 1] == 0.toByte()) end--
                        result = result.copyOf(end)
                    }
                    out.put("dataB64", b64Encode(result))
                }
                else -> out.put("error", "Unsupported crypto operation: " + algo)
            }
            return out
        }

        private fun storageKey(key: String) = plugin.packageName + ":storage:" + key

        suspend fun fetchAsync(raw: String): String = withContext(Dispatchers.IO) {
            runCatching {
                val req = JSONObject(raw)
                val url = req.optString("url")
                val uri = URI(url)
                require(uri.scheme == "http" || uri.scheme == "https") { "Unsupported URL scheme" }
                val method = req.optString("method", "GET").uppercase()
                val headers = req.optJSONObject("headers") ?: JSONObject()
                val builder = Request.Builder().url(url)
                var hasUserAgent = false
                var contentType = req.optString("contentType").ifBlank { "application/x-www-form-urlencoded" }
                val keys = headers.keys()
                while (keys.hasNext()) {
                    val header = keys.next()
                    val value = headers.optString(header)
                    if (header.equals("User-Agent", true)) hasUserAgent = true
                    if (header.equals("Content-Type", true)) contentType = value
                    builder.header(header, value)
                }
                if (!hasUserAgent) builder.header("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36")
                synchronized(cookies) {
                    val saved = cookies[uri.host]
                    if (!saved.isNullOrEmpty() && !headers.has("Cookie")) builder.header("Cookie", saved.joinToString("; "))
                }
                val bodyString = req.optString("body")
                val bodyBytes = if (req.has("bodyB64") && !req.isNull("bodyB64")) {
                    b64Decode(req.optString("bodyB64"))
                } else {
                    bodyString.toByteArray(Charsets.UTF_8)
                }
                val body = bodyBytes.toRequestBody(contentType.toMediaTypeOrNull())
                val request = when (method) {
                    "POST" -> builder.post(body).build()
                    "PUT" -> builder.put(body).build()
                    "PATCH" -> builder.patch(body).build()
                    "DELETE" -> if (bodyString.isBlank()) builder.delete().build() else builder.delete(body).build()
                    "HEAD" -> builder.head().build()
                    else -> builder.get().build()
                }
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body
                    val bytes = if (responseBody == null) {
                        ByteArray(0)
                    } else {
                        val source = responseBody.source()
                        source.request((MAX_HTTP_BODY_BYTES + 1).toLong())
                        require(source.buffer.size <= MAX_HTTP_BODY_BYTES) { "HTTP response exceeds the 8 MB limit" }
                        source.buffer.readByteArray()
                    }
                    val bodyText = bytes.toString(Charsets.UTF_8)
                    val responseHeaders = JSONObject()
                    response.headers.names().forEach { name ->
                        responseHeaders.put(name.lowercase(), response.headers.values(name).joinToString(","))
                    }
                    synchronized(cookies) {
                        val setCookies = response.headers.values("Set-Cookie")
                        if (setCookies.isNotEmpty()) {
                            val jar = cookies.getOrPut(uri.host) { mutableListOf() }
                            setCookies.forEach { cookie -> cookie.substringBefore(";").takeIf { it.contains("=") }?.let { pair ->
                                val cookieName = pair.substringBefore("=")
                                jar.removeAll { it.substringBefore("=") == cookieName }
                                jar.add(pair)
                            } }
                        }
                    }
                    JSONObject()
                        .put("code", response.code).put("status", response.code).put("statusCode", response.code)
                        .put("body", bodyText).put("bodyText", bodyText).put("bodyB64", b64Encode(bytes))
                        .put("headers", responseHeaders)
                        .put("url", response.request.url.toString())
                        .put("finalUrl", response.request.url.toString())
                        .toString()
                }
            }.getOrElse { JSONObject().put("status", 0).put("code", 0).put("statusCode", 0)
                .put("body", "").put("error", it.message ?: "HTTP request failed").toString() }
        }
    }
}
