package ar.cumulo.hoops.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
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

/** De dónde salen los archivos de las pantallas (assets en Android, una carpeta o el .jar en PC). */
fun interface AssetSource {
    fun read(path: String): ByteArray?
}

/**
 * Servidor HTTP mínimo para la red local.
 *
 * Páginas (piden contraseña si la pantalla la tiene):
 *   /            Conectar (QR); en el equipo servidor, también las contraseñas
 *   /login       ingreso con contraseña
 *   /operador    mesa de control              [operador]
 *   /tablero     tablero principal            [tablero]
 *   /posesion    reloj de 24                  [posesion]
 *   /estadisticas planilla y compartir        [operador]
 *   /nomina      planteles, clubes y fotos    [operador]
 *
 * API:
 *   GET  /events          estado en vivo (Server-Sent Events)          [cualquier pantalla]
 *   GET  /api/state       estado actual                                [cualquier pantalla]
 *   POST /api/action      acciones del operador (JSON)                 [operador]
 *   POST /api/upload      foto de escudo o jugador (?team=&player=)    [operador]
 *   GET  /media/...       fotos guardadas                              [cualquier pantalla]
 *   GET  /api/info        IPs y puerto para armar los QR
 *   GET  /api/auth        qué pantallas tienen contraseña y a cuáles puede entrar este dispositivo
 *   POST /api/login       {screen, password}
 *   POST /api/logout
 *   POST /api/passwords   {operador?, tablero?, posesion?}  solo desde el propio equipo servidor
 *
 * El propio equipo servidor (127.0.0.1) siempre tiene acceso: es el que maneja las contraseñas.
 */
class WebServer(
    private val game: Game,
    private val assets: AssetSource,
    private val port: Int = 3000,
    private val persist: ((String) -> Unit)? = null,
    private val security: Security = Security(),
    private val media: MediaStore? = null,
    /** Dispositivos con acceso total (por defecto, el propio equipo). */
    private val trusted: (InetAddress) -> Boolean = { it.isLoopbackAddress },
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

    private class Request(
        val method: String, val path: String, val query: Map<String, String>,
        val token: String?, val local: Boolean, val client: String, val body: ByteArray,
    ) {
        val text: String get() = String(body, Charsets.UTF_8)
    }

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
        media?.let { m -> scheduler.execute { guard { m.cleanup(game.mediaInUse()) } } }
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
            var cookie = ""
            var headers = 0
            while (true) {
                val h = readLine(input) ?: return
                if (h.isEmpty()) break
                if (++headers > 100) return
                val idx = h.indexOf(':')
                if (idx <= 0) continue
                val name = h.substring(0, idx).trim().lowercase()
                val value = h.substring(idx + 1).trim()
                when (name) {
                    "content-length" -> contentLength = value.toIntOrNull() ?: 0
                    "cookie" -> cookie = value
                }
            }
            if (contentLength > MAX_BODY) return respond(out, 413, "text/plain; charset=utf-8", "Demasiado grande".toByteArray())
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
            val token = cookie.split(';').map { it.trim() }.firstOrNull { it.startsWith("$COOKIE=") }
                ?.substringAfter('=')?.takeIf { Security.TOKEN.matches(it) }
            val req = Request(
                method, path, parseQuery(target.substringAfter('?', "")), token,
                local = trusted(sock.inetAddress), client = sock.inetAddress.hostAddress ?: "?", body = body
            )
            keepOpen = route(req, sock, out)
        } catch (_: IOException) {
            // conexión cortada
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            if (!keepOpen) try { sock.close() } catch (_: IOException) { }
        }
    }

    /** Devuelve true si la conexión queda abierta (eventos en vivo). */
    private fun route(r: Request, sock: Socket, out: BufferedOutputStream): Boolean {
        val p = r.path
        when {
            p == "/events" -> {
                if (!canWatch(r)) { denied(out); return false }
                openEvents(sock, out)
                return true
            }
            p == "/api/state" -> if (canWatch(r)) json(out, 200, game.publicJson()) else denied(out)
            p == "/api/action" && r.method == "POST" -> if (can(r, Security.OPERATOR)) action(out, r.text) else denied(out)
            p == "/api/upload" && r.method == "POST" -> if (can(r, Security.OPERATOR)) upload(out, r) else denied(out)
            p.startsWith("/media/") -> {
                if (!canWatch(r)) return denied(out).let { false }
                val data = media?.read(p.removePrefix("/media/"))
                    ?: return respond(out, 404, "text/plain; charset=utf-8", "No encontrado".toByteArray()).let { false }
                // Los nombres son únicos, así que las TVs pueden guardarlas en caché sin problema.
                respond(out, 200, MIME[p.substringAfterLast('.')] ?: "image/jpeg", data, r.method == "HEAD",
                    listOf("Cache-Control: private, max-age=31536000, immutable"))
            }
            p == "/api/info" -> json(out, 200, Json.stringify(linkedMapOf(
                "ips" to lanIPs(), "port" to localPort, "screens" to clients.size,
                "local" to r.local, "protected" to security.summary()
            )))
            p == "/api/auth" -> json(out, 200, authJson(r, r.token))
            p == "/api/login" && r.method == "POST" -> login(out, r)
            p == "/api/logout" && r.method == "POST" -> {
                security.logout(r.token)
                respond(out, 200, JSON_TYPE, """{"ok":true}""".toByteArray(), extra = listOf(clearCookie()))
            }
            p == "/api/passwords" && r.method == "POST" -> passwords(out, r)
            r.method == "GET" || r.method == "HEAD" -> page(out, r)
            else -> respond(out, 405, "text/plain; charset=utf-8", "Método no permitido".toByteArray())
        }
        return false
    }

    private fun can(r: Request, screen: String) = r.local || security.allowed(screen, r.token)
    private fun canWatch(r: Request) = Security.SCREENS.any { can(r, it) }

    private fun denied(out: OutputStream) =
        json(out, 401, """{"ok":false,"error":"Acceso no autorizado: ingresá la contraseña","login":true}""")

    private fun authJson(r: Request, token: String?): String = Json.stringify(linkedMapOf(
        "local" to r.local,
        "screens" to Security.SCREENS.associateWith { s ->
            linkedMapOf("protected" to security.isProtected(s), "allowed" to (r.local || security.allowed(s, token)))
        }
    ))

    private fun login(out: OutputStream, r: Request) {
        val m = try { Json.parseObject(r.text) } catch (e: Exception) { return json(out, 400, err("Datos inválidos")) }
        val screen = m.str("screen").orEmpty()
        if (screen !in Security.SCREENS) return json(out, 400, err("Pantalla inválida"))
        when (val res = security.login(screen, m.str("password").orEmpty(), r.client, r.token)) {
            is Security.Login.Ok -> respond(out, 200, JSON_TYPE, authJson(r, res.token).toByteArray(Charsets.UTF_8),
                extra = listOf("Set-Cookie: $COOKIE=${res.token}; Path=/; Max-Age=31536000; HttpOnly; SameSite=Lax"))
            Security.Login.Open -> json(out, 200, authJson(r, r.token))
            Security.Login.Wrong -> json(out, 401, err("Contraseña incorrecta"))
            is Security.Login.Blocked -> json(out, 429, err("Demasiados intentos. Esperá ${(res.seconds + 59) / 60} min y probá de nuevo."))
        }
    }

    private fun passwords(out: OutputStream, r: Request) {
        if (!r.local) return json(out, 403, err("Las contraseñas solo se cambian desde el equipo que hace de servidor"))
        try {
            val m = Json.parseObject(r.text)
            val changes = Security.SCREENS.filter { m.containsKey(it) }.associateWith { m.str(it) }
            // Validar todo antes de cambiar nada
            for ((_, pw) in changes) {
                val v = pw?.trim().orEmpty()
                require(v.isEmpty() || v.length in 4..64) { "Cada contraseña debe tener entre 4 y 64 caracteres" }
            }
            for ((s, pw) in changes) security.setPassword(s, pw)
            // Las pantallas conectadas se reconectan y, si ya no tienen permiso, piden la contraseña.
            clients.forEach { it.close() }
            clients.clear()
            json(out, 200, Json.stringify(linkedMapOf("ok" to true, "protected" to security.summary())))
        } catch (e: IllegalArgumentException) {
            json(out, 400, err(e.message ?: "Datos inválidos"))
        }
    }

    private fun upload(out: OutputStream, r: Request) {
        val store = media ?: return json(out, 400, err("Este servidor no guarda fotos"))
        try {
            val side = r.query["team"] ?: throw IllegalArgumentException("Falta el equipo")
            val playerId = r.query["player"]?.toIntOrNull()
            val url = store.save(r.body)
            try {
                if (playerId == null) game.setTeamLogo(side, url) else game.setPlayerPhoto(side, playerId, url)
            } catch (e: Exception) {
                store.cleanup(game.mediaInUse())
                throw e
            }
            store.cleanup(game.mediaInUse())
            changed()
            json(out, 200, Json.stringify(linkedMapOf("ok" to true, "url" to url)))
        } catch (e: IllegalArgumentException) {
            json(out, 400, err(e.message ?: "Imagen inválida"))
        }
    }

    private fun action(out: OutputStream, body: String) {
        try {
            game.dispatch(Json.parseObject(body.ifBlank { "{}" }))
            changed()
            media?.let { m -> guard { m.cleanup(game.mediaInUse()) } }
            json(out, 200, """{"ok":true}""")
        } catch (e: Exception) {
            json(out, 400, err(e.message ?: "Error"))
        }
    }

    private fun openEvents(sock: Socket, out: BufferedOutputStream) {
        sock.soTimeout = 0
        sock.keepAlive = true
        val head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n" +
            "Cache-Control: no-cache\r\nConnection: keep-alive\r\n\r\n"
        val c = SseClient(sock, out)
        val first = (head + "retry: 1000\n\ndata: ${game.publicJson()}\n\n").toByteArray(Charsets.UTF_8)
        if (c.send(first)) clients.add(c)
    }

    private fun page(out: OutputStream, r: Request) {
        val file = ROUTES[r.path] ?: r.path.trimStart('/')
        PROTECTED[r.path]?.let { screen ->
            if (!can(r, screen)) {
                val next = r.path
                return respond(out, 302, "text/plain; charset=utf-8", ByteArray(0),
                    extra = listOf("Location: /login?p=$screen&next=$next"))
            }
        }
        if (file.isEmpty() || file.contains("..") || file.contains('\\')) {
            return respond(out, 404, "text/plain; charset=utf-8", "No encontrado".toByteArray())
        }
        val data = assets.read(file) ?: return respond(out, 404, "text/plain; charset=utf-8", "No encontrado".toByteArray())
        val type = MIME[file.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"
        respond(out, 200, type, data, r.method == "HEAD")
    }

    private fun err(msg: String) = Json.stringify(linkedMapOf("ok" to false, "error" to msg))

    private fun clearCookie() = "Set-Cookie: $COOKIE=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"

    private fun json(out: OutputStream, code: Int, text: String) =
        respond(out, code, JSON_TYPE, text.toByteArray(Charsets.UTF_8))

    private fun respond(
        out: OutputStream, code: Int, type: String, body: ByteArray,
        headOnly: Boolean = false, extra: List<String> = emptyList(),
    ) {
        val sb = StringBuilder("HTTP/1.1 $code ${REASON[code] ?: "OK"}\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n")
        if (extra.none { it.startsWith("Cache-Control") }) sb.append("Cache-Control: no-cache\r\n")
        for (h in extra) sb.append(h).append("\r\n")
        sb.append("X-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (!headOnly) out.write(body)
        out.flush()
    }

    private fun parseQuery(q: String): Map<String, String> = q.split('&').mapNotNull {
        val k = it.substringBefore('=')
        if (k.isEmpty()) null else try {
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        } catch (_: Exception) { null }
    }.toMap()

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
        private const val MAX_BODY = 2_500_000
        private const val COOKIE = "hoops"
        private const val JSON_TYPE = "application/json; charset=utf-8"
        private val ROUTES = mapOf(
            "/" to "index.html", "/login" to "login.html", "/operador" to "operador.html",
            "/tablero" to "tablero.html", "/posesion" to "posesion.html", "/estadisticas" to "estadisticas.html",
            "/nomina" to "nomina.html"
        )
        /** Qué contraseña pide cada página. */
        private val PROTECTED = mapOf(
            "/operador" to Security.OPERATOR, "/operador.html" to Security.OPERATOR,
            "/estadisticas" to Security.OPERATOR, "/estadisticas.html" to Security.OPERATOR,
            "/nomina" to Security.OPERATOR, "/nomina.html" to Security.OPERATOR,
            "/tablero" to "tablero", "/tablero.html" to "tablero",
            "/posesion" to "posesion", "/posesion.html" to "posesion"
        )
        private val MIME = mapOf(
            "html" to "text/html; charset=utf-8", "js" to "application/javascript; charset=utf-8",
            "css" to "text/css; charset=utf-8", "json" to "application/json", "png" to "image/png",
            "svg" to "image/svg+xml", "ico" to "image/x-icon", "jpg" to "image/jpeg", "webp" to "image/webp",
            "mp3" to "audio/mpeg", "woff2" to "font/woff2"
        )
        private val REASON = mapOf(
            200 to "OK", 302 to "Found", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden",
            404 to "Not Found", 405 to "Method Not Allowed", 413 to "Payload Too Large", 429 to "Too Many Requests"
        )
        // Interfaces típicas del hotspot en Android y de datos móviles (que no sirven para las TVs)
        private val HOTSPOT = listOf("ap", "swlan", "softap", "wlan1", "wlan2", "rndis", "wigig")
        private val CELLULAR = listOf("rmnet", "ccmni", "v4-rmnet", "dummy", "tun", "ppp", "clat", "ims")
    }
}
