package ar.cumulo.hoops.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

class CoreTest {

    private var t = 1_000_000L
    private fun game() = Game { t }
    private fun Game.act(vararg kv: Pair<String, Any?>) = dispatch(mapOf(*kv))
    private fun Game.state() = Json.parseObject(publicJson())

    @Test
    fun json_roundtrip() {
        val src = """{"a":1,"b":[true,false,null],"c":"ñ \"x\" \n","d":{"e":-2.5}}"""
        val v = Json.parseObject(src)
        assertEquals(1.0, v["a"])
        assertEquals("ñ \"x\" \n", v["c"])
        assertEquals(src, Json.stringify(v))
    }

    @Test
    fun puntos_faltas_y_deshacer() {
        val g = game()
        val p = g.home.players[0]
        g.act("type" to "score", "team" to "home", "pts" to 2, "playerId" to p.id)
        g.act("type" to "foul", "team" to "home", "playerId" to p.id)
        assertEquals(2, g.home.score)
        assertEquals(2, g.home.players[0].points)
        assertEquals(1, g.home.fouls)
        g.act("type" to "undo")
        assertEquals(0, g.home.fouls)
        assertEquals(2, g.home.score)
        g.act("type" to "undo")
        assertEquals(0, g.home.score)
        assertEquals(0, g.home.players[0].points)
    }

    @Test
    fun accion_invalida_no_deja_basura_para_deshacer() {
        val g = game()
        g.act("type" to "score", "team" to "home", "pts" to 3)
        try { g.act("type" to "score", "team" to "xx", "pts" to 2) } catch (_: IllegalArgumentException) { }
        g.act("type" to "undo")
        assertEquals(0, g.home.score)
    }

    @Test
    fun reloj_vence_y_suena_chicharra() {
        val g = game()
        g.act("type" to "clockSet", "ms" to 3000)
        g.act("type" to "clockToggle")
        assertTrue(g.clock.running && g.shot.running)
        t += 2000
        assertFalse(g.tick())
        assertEquals(1000L, g.rem(g.clock))
        t += 1500
        assertTrue(g.tick())
        assertFalse(g.clock.running)
        assertFalse(g.shot.running)
        assertEquals(0L, g.rem(g.clock))
        assertEquals(1, g.buzzer)
    }

    @Test
    fun violacion_de_24_frena_el_juego() {
        val g = game()
        g.act("type" to "shotReset", "s" to 14)
        g.act("type" to "clockToggle")
        t += 14_001
        assertTrue(g.tick())
        assertFalse(g.clock.running)
        assertEquals(480_000L - 14_001, g.rem(g.clock))
        assertEquals(1, g.buzzer)
    }

    @Test
    fun cambio_de_posesion_resetea_24_y_sigue_corriendo() {
        val g = game()
        g.act("type" to "clockToggle")
        t += 10_000
        g.act("type" to "swapPossession")
        assertEquals("home", g.possession)
        assertEquals(24_000L, g.rem(g.shot))
        assertTrue(g.shot.running)
        g.act("type" to "undo")
        assertNull(g.possession)
    }

    @Test
    fun cambio_de_cuarto_reinicia_faltas_pero_no_en_suplementario() {
        val g = game()
        g.act("type" to "foul", "team" to "away")
        g.act("type" to "period", "n" to 2)
        assertEquals(0, g.away.fouls)
        g.act("type" to "period", "n" to 4)
        g.act("type" to "foul", "team" to "away")
        g.act("type" to "period", "n" to 5)
        assertEquals(1, g.away.fouls)
        assertEquals("OT1", g.periodLabel())
        assertEquals(300_000L, g.rem(g.clock))
    }

    @Test
    fun configuracion_y_plantel() {
        val g = game()
        val keep = g.home.players.first { it.num == "7" }
        keep.points = 9
        g.act(
            "type" to "config", "title" to "Final U15", "periodMinutes" to 10,
            "home" to mapOf("name" to "Echesortu", "short" to "eche", "color" to "#112233", "roster" to "7 Juan\n#23 Pedro Gómez\nbasura\n")
        )
        assertEquals("Final U15", g.title)
        assertEquals("ECHE", g.home.short)
        assertEquals("#112233", g.home.color)
        assertEquals(listOf("7", "23"), g.home.players.map { it.num })
        assertEquals(9, g.home.players[0].points) // conserva estadísticas por número
        assertEquals(600_000L, g.rem(g.clock))
    }

    @Test
    fun guardar_y_recuperar() {
        val g = game()
        g.act("type" to "score", "team" to "away", "pts" to 3)
        g.act("type" to "clockToggle")
        t += 5000
        val saved = g.saveJson()
        val g2 = game()
        assertTrue(g2.load(saved))
        assertEquals(3, g2.away.score)
        assertFalse(g2.clock.running)
        assertEquals(475_000L, g2.rem(g2.clock))
        assertFalse(g2.load("{roto"))
    }

    @Test
    fun servidor_http_y_eventos() {
        val web = File("../web").takeIf { it.isDirectory } ?: File("web")
        val server = WebServer(Game(), { p -> File(web, p).takeIf { it.isFile }?.readBytes() }, port = 0)
        server.start()
        try {
            val base = "http://127.0.0.1:${server.localPort}"
            assertTrue(get("$base/operador").contains("Mesa de control"))
            assertTrue(get("$base/tablero").contains("Tablero"))
            assertEquals(404, code("$base/../server.js"))
            assertTrue(post("$base/api/action", """{"type":"score","team":"home","pts":3}""").contains("\"ok\":true"))
            assertEquals(400, code("$base/api/action", """{"type":"nada"}"""))
            assertEquals(3.0, Json.parseObject(get("$base/api/state")).obj("home")!!.num("score"))
            val info = Json.parseObject(get("$base/api/info"))
            assertEquals(server.localPort.toDouble(), info.num("port"))

            val c = URI("$base/events").toURL().openConnection() as HttpURLConnection
            c.readTimeout = 3000
            val reader = c.inputStream.bufferedReader()
            var line = reader.readLine()
            while (line != null && !line.startsWith("data:")) line = reader.readLine()
            assertTrue(line!!.contains("\"score\":3"))
            post("$base/api/action", """{"type":"score","team":"away","pts":2}""")
            line = reader.readLine()
            while (line != null && !line.startsWith("data:")) line = reader.readLine()
            assertTrue(line!!.contains("\"score\":2"))
            c.disconnect()
        } finally {
            server.stop()
        }
    }

    private fun get(url: String) = (URI(url).toURL().openConnection() as HttpURLConnection).inputStream.bufferedReader().readText()

    private fun post(url: String, body: String): String {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
        return c.inputStream.bufferedReader().readText()
    }

    private fun code(url: String, body: String? = null): Int {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        if (body != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        return c.responseCode
    }
}
