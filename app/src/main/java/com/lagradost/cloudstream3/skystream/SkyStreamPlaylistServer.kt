package com.lagradost.cloudstream3.skystream

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Small loopback-only server for inline M3U8 content returned by SkyStream
 * extensions. The player needs an HTTP playlist URL; a file:// URI breaks
 * relative playlist references and isn't handled consistently by HLS engines.
 *
 * It never proxies arbitrary requests: only opaque IDs created by [serve]
 * can return playlist text, and the listener binds to 127.0.0.1 only.
 */
internal object SkyStreamPlaylistServer {
    private const val MAX_PLAYLISTS = 50
    private const val MAX_PLAYLIST_BYTES = 8 * 1024 * 1024

    @Volatile private var listener: ServerSocket? = null
    @Volatile private var port: Int = 0
    private val playlists = LinkedHashMap<String, String>()

    @Synchronized
    fun serve(content: String): String {
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_PLAYLIST_BYTES) { "Inline M3U8 is too large" }
        require(content.trimStart().startsWith("#EXTM3U")) { "Inline playlist is not valid M3U8" }
        ensureStarted()
        while (playlists.size >= MAX_PLAYLISTS) {
            val oldest = playlists.keys.firstOrNull() ?: break
            playlists.remove(oldest)
        }
        val id = UUID.randomUUID().toString().replace("-", "")
        playlists[id] = content
        return "http://127.0.0.1:$port/$id.m3u8"
    }

    @Synchronized
    private fun ensureStarted() {
        if (listener?.isClosed == false) return
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        listener = server
        port = server.localPort
        thread(name = "SkyStream-M3U8-Loopback", isDaemon = true) {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                thread(name = "SkyStream-M3U8-Client", isDaemon = true) {
                    handle(socket)
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 5_000
                val reader = BufferedReader(
                    InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8)
                )
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0).orEmpty()
                val rawPath = parts.getOrNull(1).orEmpty().substringBefore('?')
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }

                if (method != "GET" && method != "HEAD") {
                    writeResponse(client.getOutputStream(), 405, "Method Not Allowed", "text/plain", ByteArray(0), method == "HEAD")
                    return
                }
                val id = rawPath.removePrefix("/").removeSuffix(".m3u8")
                val content = synchronized(this) { playlists[id] }
                if (content == null) {
                    writeResponse(client.getOutputStream(), 404, "Not Found", "text/plain", ByteArray(0), method == "HEAD")
                    return
                }
                writeResponse(
                    client.getOutputStream(),
                    200,
                    "OK",
                    "application/vnd.apple.mpegurl; charset=utf-8",
                    content.toByteArray(StandardCharsets.UTF_8),
                    method == "HEAD",
                )
            } catch (_: Exception) {
                // A closed player socket is normal; don't crash the app for it.
            }
        }
    }

    private fun writeResponse(
        output: OutputStream,
        status: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
        headOnly: Boolean,
    ) {
        val writer = BufferedWriter(OutputStreamWriter(output, StandardCharsets.US_ASCII))
        writer.write("HTTP/1.1 $status $reason\r\n")
        writer.write("Content-Type: $contentType\r\n")
        writer.write("Content-Length: ${body.size}\r\n")
        writer.write("Access-Control-Allow-Origin: *\r\n")
        writer.write("Connection: close\r\n\r\n")
        writer.flush()
        if (!headOnly && body.isNotEmpty()) {
            output.write(body)
            output.flush()
        }
    }
}
