package ar.cumulo.hoops.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** De dónde salen los archivos de las pantallas (assets en Android, una carpeta en PC). */
fun interface AssetSource {
    fun read(path: String): ByteArray?
}

/**
 * Servidor HTTP mínimo para la red local:
 *  - GET  /, /operador, /tablero, /posesion y archivos estáticos
 *  - GET  /events        estado en vivo (Server-Sent Events)
 *  - POST /api/action    acciones del operador (JSON)
 *  - GET  /api/info      IPs y puerto para armar los QR
 *  - GET  /api/state     estado actual
 */
class WebServer(
    private val game: Game,
    private val assets: AssetSource,
    private val port: Int = 3000,
    private val persist: ((String) -> Unit)? = null,
) {
    private var server: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<SseClient>()
    private val seenIPs = CopyOnWriteArraySet<String>()
    private val workers = Executors.newCachedThreadPool { Thread(it, "hoops-http").apply { isDaemon = true } }
    private val broadcaster = Executors.newSingleThreadExecutor { Thread(it, "hoops-sse").apply { isDaemon = true } }
    private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "hoops-reloj").apply { isDaemon = true } }
    @Volatile private var running = false
    @Volatile private var dirty = false

    /** Puerto real (útil si se pidió el 0 para elegir uno libre). */
    val localPort: Int get() = server?.localPort ?: port
    val screenCount: Int get() = clients.size

    @Throws(IOException::class)
    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        server = ss
        running = true
        workers.execute { acceptLoop(ss) }
        scheduler.scheduleAtFixedRate({ guard { if (game.tick()) changed() } }, 25, 25, TimeUnit.MILLISECONDS)
        // Reenvío periódico: resincroniza relojes y permite detectar pantallas desconectadas.
        scheduler.scheduleAtFixedRate({ broadcaster.execute { broadcast() } }, 5, 5, TimeUnit.SECONDS)
        scheduler.scheduleAtFixedRate({ guard { if (dirty || game.isClockRunning()) save() } }, 1, 2, TimeUnit.SECONDS)
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: IOException) { }
        scheduler.shutdownNow()
        broadcaster.shutdownNow()
        clients.forEach { it.close() }
        clients.clear()
        workers.shutdownNow()
        save()
    }

    /** IPs de este equipo en la red local, con las del hotspot primero. */
    fun lanIPs(): List<String> {
        val found = LinkedHashMap<String, Int>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name.lowercase()
                if (CELLULAR.any { name.startsWith(it) }) continue
                val rank = when {
                    HOTSPOT.any { name.startsWith(it) } -> 0
                    name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("en") -> 1
                    else -> 2
                }
                for (a in ni.inetAddresses.toList()) {
                    if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) found.putIfAbsent(a.hostAddress, rank)
                }
            }
        } catch (_: Exception) { /* sin permiso para leer interfaces */ }
        for (ip in seenIPs) found.putIfAbsent(ip, 1)
        return found.entries.sortedBy { it.value }.map { it.key }
    }

    // ------------------------------------------------------------------ internos
    private fun guard(block: () -> Unit) = try { block() } catch (e: Exception) { e.printStackTrace() }

    private fun changed() {
        dirty = true
        try { broadcaster.execute { broadcast() } } catch (_: Exception) { /* detenido */ }
    }

    private fun save() {
        val p = persist ?: return
        dirty = false
        guard { p(game.saveJson()) }
    }

    private fun broadcast() {
        if (clients.isEmpty()) return
        val msg = "data: ${game.publicJson()}\n\n".toByteArray(Charsets.UTF_8)
        for (c in clients) if (!c.send(msg)) clients.remove(c)
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val s = try { ss.accept() } catch (e: SocketException) { break } catch (e: IOException) { continue }
            try { workers.execute { handle(s) } } catch (_: Exception) { s.close() }
        }
    }

    private fun handle(sock: Socket) {
        var keepOpen = false
        try {
            sock.tcpNoDelay = true
            sock.soTimeout = 15_000
            val input = BufferedInputStream(sock.getInputStream())
            val out = BufferedOutputStream(sock.getOutputStream())

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val target = parts[1]
            var contentLength = 0
            var headers = 0
            while (true) {
                val h = readLine(input) ?: return
                if (h.isEmpty()) break
                if (++headers > 100) return
                val idx = h.indexOf(':')
                if (idx > 0 && h.substring(0, idx).trim().equals("content-length", true)) {
                    contentLength = h.substring(idx + 1).trim().toIntOrNull() ?: 0
                }
            }
            if (contentLength > MAX_BODY) return respond(out, 413, "text/plain", "Demasiado grande".toByteArray())
            val body = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(body, read, contentLength - read)
                if (n < 0) break
                read += n
            }

            (sock.localAddress as? Inet4Address)?.hostAddress?.let {
                if (!it.startsWith("127.") && !it.startsWith("0.")) seenIPs.add(it)
            }

            val path = try { URLDecoder.decode(target.substringBefore('?'), "UTF-8") } catch (_: Exception) { "/" }
            when {
                path == "/events" -> { openEvents(sock, out); keepOpen = true }
                path == "/api/action" && method == "POST" -> action(out, String(body, Charsets.UTF_8))
                path == "/api/info" -> json(out, 200, Json.stringify(linkedMapOf("ips" to lanIPs(), "port" to localPort, "screens" to clients.size)))
                path == "/api/state" -> json(out, 200, game.publicJson())
                method == "GET" || method == "HEAD" -> static(out, path, method == "HEAD")
                else -> respond(out, 405, "text/plain", "Método no permitido".toByteArray())
            }
        } catch (_: IOException) {
            // conexión cortada
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            if (!keepOpen) try { sock.close() } catch (_: IOException) { }
        }
    }

    private fun action(out: OutputStream, body: String) {
        try {
            game.dispatch(Json.parseObject(body.ifBlank { "{}" }))
            changed()
            json(out, 200, """{"ok":true}""")
        } catch (e: Exception) {
            json(out, 400, Json.stringify(linkedMapOf("ok" to false, "error" to (e.message ?: "Error"))))
        }
    }

    private fun openEvents(sock: Socket, out: BufferedOutputStream) {
        sock.soTimeout = 0
        sock.keepAlive = true
        val head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n" +
            "Cache-Control: no-cache\r\nConnection: keep-alive\r\nAccess-Control-Allow-Origin: *\r\n\r\n"
        val c = SseClient(sock, out)
        val first = (head + "retry: 1000\n\ndata: ${game.publicJson()}\n\n").toByteArray(Charsets.UTF_8)
        if (c.send(first)) clients.add(c)
    }

    private fun static(out: OutputStream, path: String, headOnly: Boolean) {
        val file = ROUTES[path] ?: path.trimStart('/')
        if (file.isEmpty() || file.contains("..") || file.contains('\\')) {
            return respond(out, 404, "text/plain; charset=utf-8", "No encontrado".toByteArray())
        }
        val data = assets.read(file) ?: return respond(out, 404, "text/plain; charset=utf-8", "No encontrado".toByteArray())
        val type = MIME[file.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"
        respond(out, 200, type, data, headOnly)
    }

    private fun json(out: OutputStream, code: Int, text: String) =
        respond(out, code, "application/json; charset=utf-8", text.toByteArray(Charsets.UTF_8))

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray, headOnly: Boolean = false) {
        val head = "HTTP/1.1 $code ${REASON[code] ?: "OK"}\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-cache\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        if (!headOnly) out.write(body)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) throw IOException("Línea demasiado larga")
        }
    }

    private class SseClient(private val sock: Socket, private val out: OutputStream) {
        @Synchronized
        fun send(bytes: ByteArray): Boolean = try {
            out.write(bytes)
            out.flush()
            true
        } catch (_: IOException) {
            close()
            false
        }

        fun close() = try { sock.close() } catch (_: IOException) { }
    }

    companion object {
        private const val MAX_BODY = 1_000_000
        private val ROUTES = mapOf(
            "/" to "index.html", "/operador" to "operador.html",
            "/tablero" to "tablero.html", "/posesion" to "posesion.html"
        )
        private val MIME = mapOf(
            "html" to "text/html; charset=utf-8", "js" to "application/javascript; charset=utf-8",
            "css" to "text/css; charset=utf-8", "json" to "application/json", "png" to "image/png",
            "svg" to "image/svg+xml", "ico" to "image/x-icon", "jpg" to "image/jpeg", "webp" to "image/webp",
            "mp3" to "audio/mpeg", "woff2" to "font/woff2"
        )
        private val REASON = mapOf(200 to "OK", 400 to "Bad Request", 404 to "Not Found", 405 to "Method Not Allowed", 413 to "Payload Too Large")
        // Interfaces típicas del hotspot en Android y de datos móviles (que no sirven para las TVs)
        private val HOTSPOT = listOf("ap", "swlan", "softap", "wlan1", "wlan2", "rndis", "wigig")
        private val CELLULAR = listOf("rmnet", "ccmni", "v4-rmnet", "dummy", "tun", "ppp", "clat", "ims")
    }
}
