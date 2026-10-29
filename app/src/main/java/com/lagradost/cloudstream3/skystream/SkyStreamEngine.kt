package com.lagradost.cloudstream3.skystream

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import com.dokar.quickjs.quickJs
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
import java.util.concurrent.TimeUnit

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
        timeoutMs: Long = TIMEOUT_MS,
    ): Any? {
        val code = SkyStreamRepository.script(context, repoUrl, plugin.packageName)
            ?: throw IllegalStateException("Plugin script is missing; refresh the repository")
        require(code.isNotBlank()) { "Plugin script is empty" }
        val bridge = SkyStreamHostBridge(context.applicationContext, plugin, repoUrl)
        val manifest = plugin.manifest().apply {
            if (!providerId.isNullOrBlank()) put("providerId", providerId)
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
                evaluate<Any?>(buildHostPolyfill(manifest))
                evaluate<Any?>(buildPluginWrapper(code))
                val call = buildInvocation(functionName, argArray)
                val evalText = runCatching { evaluate<String>(call, "skystream-call.js") }.getOrNull()
                when {
                    bridge.resultJson != null -> bridge.resultJson
                    bridge.resultDeferred.isCompleted -> bridge.resultDeferred.await()
                    !evalText.isNullOrBlank() && evalText.trimStart().startsWith("{") -> evalText
                    !evalText.isNullOrBlank() && evalText.trimStart().startsWith("[") -> evalText
                    else -> null
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
    skyStreamCall(JSON.stringify({op:'result', data:JSON.stringify({__sky_error:String(e && (e.stack || e.message) || e})}));
    return null;
  }
})()
""".trimIndent()
            .replace("$args", args.toString())
    }

    private fun buildPluginWrapper(code: String): String {
        // Raw extension scripts are repository code, not markup. The wrapper keeps all
        // their declarations in a closure and exposes the same export names as SkyStream.
        return """
globalThis.__skyStreamExports = (function() {
  const exports = {};
  const module = { exports: exports };
  const require = function(name) {
    if (name === 'url') return { URL: URL, URLSearchParams: URLSearchParams };
    throw new Error('Unsupported module: ' + name);
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
""".trimIndent()
    }

    private fun buildHostPolyfill(manifest: JSONObject): String {
        val manifestLiteral = manifest.toString()
        return """
(function() {
  const manifest = $manifestLiteral;
  globalThis.manifest = manifest;
  globalThis.window = globalThis;
  globalThis.self = globalThis;
  globalThis.global = globalThis;
  globalThis.process = { env: { NODE_ENV: 'production' }, platform: 'android', nextTick: (fn) => Promise.resolve().then(fn) };
  globalThis.console = {
    log: (...a) => skyStreamCall(JSON.stringify({op:'log', message:a.map(String).join(' ')})),
    info: (...a) => skyStreamCall(JSON.stringify({op:'log', message:a.map(String).join(' ')})),
    warn: (...a) => skyStreamCall(JSON.stringify({op:'log', message:a.map(String).join(' ')})),
    error: (...a) => skyStreamCall(JSON.stringify({op:'log', message:a.map(String).join(' ')})),
    debug: (...a) => skyStreamCall(JSON.stringify({op:'log', message:a.map(String).join(' ')}))
  };
  globalThis.setTimeout = (fn, ms) => { skyStreamSleepAsync(Number(ms)||0).then(() => { try { fn(); } catch(e) {} }); return 1; };
  globalThis.clearTimeout = () => {};
  globalThis.setInterval = (fn, ms) => { let active=true; const tick=()=>{if(!active)return; skyStreamSleepAsync(Number(ms)||0).then(()=>{if(!active)return;try{fn();}catch(e){}tick();});};tick();return {cancel:()=>{active=false;}}; };
  globalThis.clearInterval = (timer) => { if (timer && timer.cancel) timer.cancel(); };
  globalThis.getPreference = (key) => {
    const raw = skyStreamCall(JSON.stringify({op:'get_preference', key:String(key)}));
    try { return JSON.parse(raw); } catch(e) { return null; }
  };
  globalThis.setPreference = (key, value) => skyStreamCall(JSON.stringify({op:'set_preference', key:String(key), value:value}));
  globalThis.localStorage = {
    getItem: (key) => { const v=skyStreamCall(JSON.stringify({op:'storage_get',key:String(key)})); try {const o=JSON.parse(v);return o.value==null?null:o.value;}catch(e){return null;} },
    setItem: (key,value) => skyStreamCall(JSON.stringify({op:'storage_set',key:String(key),value:String(value)})),
    removeItem: (key) => skyStreamCall(JSON.stringify({op:'storage_remove',key:String(key)})),
    clear: () => skyStreamCall(JSON.stringify({op:'storage_clear'}))
  };
  const doRequest = async (url, options={}) => {
    const raw = await skyStreamFetchAsync(JSON.stringify({
      url:String(url), method:options.method||'GET', headers:options.headers||{},
      body:options.body||'', contentType:options.contentType||''
    }));
    const response = JSON.parse(raw || '{}');
    if (response.error) throw new Error(response.error);
    const headers = response.headers || {};
    const headerBag = { get: (key) => headers[String(key).toLowerCase()] || headers[key] || null, forEach: (fn) => Object.keys(headers).forEach(k=>fn(headers[k],k)) };
    return {
      status:response.status||response.statusCode||0, statusCode:response.statusCode||response.status||0,
      code:response.code||response.status||0, ok:(response.status||0)>=200&&(response.status||0)<300,
      url:response.finalUrl||response.url||String(url), headers:headerBag,
      body:response.body||'', data:response.body||'',
      text:async()=>response.body||'', json:async()=>JSON.parse(response.body||'null'),
      content:response.body||''
    };
  };
  globalThis.fetch = (url, options={}) => doRequest(url, options);
  globalThis.http_get = (url, headers={}, callback) => doRequest(url,{headers}).then(r=>{if(typeof callback==='function')callback(r);return r;});
  globalThis.http_post = (url, headers={}, body='', callback) => doRequest(url,{method:'POST',headers,body}).then(r=>{if(typeof callback==='function')callback(r);return r;});
  globalThis.http_parallel = (requests) => Promise.all((requests||[]).map(r=>doRequest(r.url,{method:r.method||'GET',headers:r.headers||{},body:r.body||''})));
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
            val req = JSONObject(requestJson)
            val key = req.optString("key")
            when (req.optString("op")) {
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
                    prefs.edit().putString(plugin.packageName + ":" + key,
                        if (value == null || value === JSONObject.NULL) null else if (value is String) value else value.toString()
                    ).apply()
                    "{}"
                }
                "storage_get" -> JSONObject().put("value", prefs.getString(storageKey(key), null) ?: JSONObject.NULL).toString()
                "storage_set" -> {
                    val value = req.optString("value")
                    prefs.edit().putString(storageKey(key), value).apply()
                    "{}"
                }
                "storage_remove" -> {
                    prefs.edit().remove(storageKey(key)).apply()
                    "{}"
                }
                "storage_clear" -> {
                    val allKeys = prefs.all.keys.filter { it.startsWith(plugin.packageName + ":storage:") }
                    prefs.edit().also { edit -> allKeys.forEach { edit.remove(it) } }.apply()
                    "{}"
                }
                else -> JSONObject().put("error", "Unsupported bridge operation").toString()
            }
        }.getOrElse { JSONObject().put("error", it.message ?: "bridge error").toString() }

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
                val body = bodyString.toRequestBody(contentType.toMediaTypeOrNull())
                val request = when (method) {
                    "POST" -> builder.post(body).build()
                    "PUT" -> builder.put(body).build()
                    "PATCH" -> builder.patch(body).build()
                    "DELETE" -> if (bodyString.isBlank()) builder.delete().build() else builder.delete(body).build()
                    "HEAD" -> builder.head().build()
                    else -> builder.get().build()
                }
                client.newCall(request).execute().use { response ->
                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    require(bytes.size <= MAX_HTTP_BODY_BYTES) { "HTTP response exceeds the 8 MB limit" }
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
                        .put("body", bodyText).put("headers", responseHeaders)
                        .put("finalUrl", response.request.url.toString())
                        .toString()
                }
            }.getOrElse { JSONObject().put("status", 0).put("code", 0).put("statusCode", 0)
                .put("body", "").put("error", it.message ?: "HTTP request failed").toString() }
        }
    }
}
