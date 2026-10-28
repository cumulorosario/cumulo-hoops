package ar.cumulo.hoops.core

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Estado y reglas del partido. Es la única fuente de verdad: las pantallas solo muestran
 * lo que esta clase publica. Los nombres de los campos JSON coinciden con lo que esperan
 * las páginas de /web, así que no hay que tocar nada del lado del navegador.
 *
 * Todos los métodos públicos son sincronizados: los llaman a la vez los hilos HTTP y el reloj.
 */
class Game(private val now: () -> Long = { System.currentTimeMillis() }) {

    // ------------------------------------------------------------------ modelo
    class Clock(var running: Boolean = false, var remaining: Long = 0, var startedAt: Long = 0) : JsonWritable {
        override fun toJson() = linkedMapOf("running" to running, "remaining" to remaining, "startedAt" to startedAt)
    }

    class Player(
        val id: Int, var num: String, var name: String,
        var onCourt: Boolean = false, var points: Int = 0, var fouls: Int = 0
    ) : JsonWritable {
        override fun toJson() = linkedMapOf(
            "id" to id, "num" to num, "name" to name, "onCourt" to onCourt, "points" to points, "fouls" to fouls
        )

        companion object {
            fun from(m: Map<String, Any?>) = Player(
                id = m.num("id")?.toInt() ?: 0,
                num = m.str("num") ?: "0",
                name = m.str("name") ?: "",
                onCourt = m["onCourt"] == true,
                points = m.num("points")?.toInt() ?: 0,
                fouls = m.num("fouls")?.toInt() ?: 0
            )
        }
    }

    class Team(
        var name: String, var short: String, var color: String,
        var score: Int = 0, var fouls: Int = 0, var timeouts: Int = 0,
        var players: MutableList<Player> = mutableListOf()
    ) : JsonWritable {
        override fun toJson() = linkedMapOf(
            "name" to name, "short" to short, "color" to color, "score" to score,
            "fouls" to fouls, "timeouts" to timeouts, "players" to players
        )

        companion object {
            @Suppress("UNCHECKED_CAST")
            fun from(m: Map<String, Any?>) = Team(
                name = m.str("name") ?: "",
                short = m.str("short") ?: "",
                color = m.str("color") ?: "#888888",
                score = m.num("score")?.toInt() ?: 0,
                fouls = m.num("fouls")?.toInt() ?: 0,
                timeouts = m.num("timeouts")?.toInt() ?: 0,
                players = (m.list("players") ?: emptyList()).mapNotNull { (it as? Map<String, Any?>)?.let(Player::from) }.toMutableList()
            )
        }
    }

    class LogEntry(val p: String, val c: String, val text: String) : JsonWritable {
        override fun toJson() = linkedMapOf("p" to p, "c" to c, "text" to text)

        companion object {
            fun from(m: Map<String, Any?>) = LogEntry(m.str("p") ?: "", m.str("c") ?: "", m.str("text") ?: "")
        }
    }

    // ------------------------------------------------------------------ estado
    var title = "U13 Tira Blanca"; private set
    var period = 1; private set
    val periods = 4
    var periodLength = 8 * 60_000L; private set
    var otLength = 5 * 60_000L; private set
    val clock = Clock(remaining = periodLength)
    val shot = Clock(remaining = 24_000)
    var possession: String? = null; private set
    var home: Team; private set
    var away: Team; private set
    var buzzer = 0; private set
    private var log = ArrayList<LogEntry>()

    private var nextId = 1
    private val undoStack = ArrayDeque<String>()

    init {
        home = Team("Club Atlético Echesortu", "CAE", "#ef4444", players = samplePlayers())
        away = Team("Club Sorrento", "SOR", "#38bdf8", players = samplePlayers())
    }

    private fun samplePlayers() = (4..15).map { n ->
        Player(nextId++, n.toString(), "Jugador $n", onCourt = n <= 8)
    }.toMutableList()

    // ------------------------------------------------------------------ relojes
    // Cada reloj guarda lo que le quedaba y, si corre, cuándo arrancó. Las pantallas
    // calculan el tiempo por su cuenta; el servidor no necesita mandar cada décima.
    fun rem(c: Clock): Long = if (c.running) max(0L, c.remaining - (now() - c.startedAt)) else c.remaining
    private fun pause(c: Clock) { c.remaining = rem(c); c.running = false }
    private fun start(c: Clock) { if (c.remaining > 0) { c.startedAt = now(); c.running = true } }
    private fun set(c: Clock, ms: Long) { c.remaining = max(0L, ms); c.startedAt = now() }
    private fun curLength() = if (period <= periods) periodLength else otLength
    fun periodLabel() = if (period <= periods) "Q$period" else "OT${period - periods}"

    private fun fmtClock(ms: Long): String {
        val s = ceil(ms / 1000.0).toLong()
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    private fun addLog(text: String) {
        log.add(0, LogEntry(periodLabel(), fmtClock(rem(clock)), text))
        while (log.size > 300) log.removeAt(log.size - 1)
    }

    private fun team(k: String?): Team = when (k) {
        "home" -> home
        "away" -> away
        else -> throw IllegalArgumentException("Equipo inválido")
    }

    private fun player(t: Team, id: Double?): Player? = id?.let { v -> t.players.firstOrNull { it.id == v.toInt() } }

    @Synchronized
    fun isClockRunning() = clock.running

    // ------------------------------------------------------------------ acciones
    @Synchronized
    fun dispatch(msg: Map<String, Any?>) {
        val type = msg.str("type") ?: throw IllegalArgumentException("Falta el tipo de acción")
        val undoable = type in UNDOABLE
        if (undoable) {
            undoStack.addLast(snapshot())
            if (undoStack.size > 200) undoStack.removeFirst()
        }
        try {
            apply(type, msg)
        } catch (e: Exception) {
            if (undoable) undoStack.removeLast()
            throw e
        }
    }

    private fun apply(type: String, m: Map<String, Any?>) {
        when (type) {
            "clockToggle" -> {
                if (clock.running) { pause(clock); pause(shot); return }
                if (rem(clock) <= 0) return
                start(clock)
                if (shot.remaining > 0) start(shot)
            }
            "clockAdjust" -> set(clock, (rem(clock) + (m.num("ms") ?: 0.0).roundToLong()).coerceIn(0, curLength()))
            "clockSet" -> set(clock, (m.num("ms") ?: 0.0).roundToLong().coerceIn(0, 60 * 60_000L))

            "shotToggle" -> if (shot.running) pause(shot) else start(shot)
            "shotReset" -> shotReset(if (m.num("s") == 14.0) 14 else 24)
            "shotAdjust" -> set(shot, (rem(shot) + (m.num("ms") ?: 0.0).roundToLong()).coerceIn(0, 24_000))

            "period" -> {
                val n = (m.num("n") ?: 1.0).roundToInt().coerceIn(1, 12)
                pause(clock); pause(shot)
                period = n
                set(clock, curLength())
                set(shot, 24_000)
                // FIBA: las faltas de equipo se reinician cada cuarto; el suplementario continúa el 4º.
                if (n <= periods) { home.fouls = 0; away.fouls = 0 }
                addLog("Inicio de ${periodLabel()}")
            }

            "score" -> {
                val t = team(m.str("team"))
                val pts = (m.num("pts") ?: 0.0).toInt()
                if (pts == 0 || t.score + pts < 0) return
                val p = player(t, m.num("playerId"))
                t.score += pts
                p?.let { it.points = max(0, it.points + pts) }
                val sign = if (pts > 0) "+$pts" else "$pts"
                addLog("${t.short} $sign${p?.let { " · #${it.num} ${it.name}" } ?: ""}  (${home.score}-${away.score})")
            }

            "foul" -> {
                val t = team(m.str("team"))
                val delta = if ((m.num("delta") ?: 1.0) < 0) -1 else 1
                val p = player(t, m.num("playerId"))
                t.fouls = max(0, t.fouls + delta)
                p?.let { it.fouls = max(0, it.fouls + delta) }
                if (delta > 0) {
                    val who = p?.let { " · #${it.num} ${it.name} (${it.fouls})" } ?: " (equipo)"
                    addLog("Falta ${t.short}$who${if (p != null && p.fouls >= 5) " — 5 FALTAS" else ""}")
                } else addLog("Corrección: −1 falta ${t.short}")
            }

            "timeout" -> {
                val t = team(m.str("team"))
                val delta = if ((m.num("delta") ?: 1.0) < 0) -1 else 1
                t.timeouts = max(0, t.timeouts + delta)
                if (delta > 0) {
                    pause(clock); pause(shot)
                    addLog("Tiempo muerto ${t.short} (${t.timeouts})")
                } else addLog("Corrección: −1 tiempo muerto ${t.short}")
            }

            "sub" -> {
                val t = team(m.str("team"))
                val p = player(t, m.num("playerId")) ?: return
                p.onCourt = !p.onCourt
                addLog("${if (p.onCourt) "Ingresa" else "Sale"} ${t.short} · #${p.num} ${p.name}")
            }

            "possession" -> possession = m.str("team").takeIf { it == "home" || it == "away" }
            "swapPossession" -> {
                possession = if (possession == "home") "away" else "home"
                shotReset(24)
                addLog("Cambio de posesión → ${team(possession).short}")
            }

            "buzzer" -> buzzer++
            "config" -> config(m)
            "newGame" -> newGame()
            "undo" -> undoStack.removeLastOrNull()?.let(::restore)
            else -> throw IllegalArgumentException("Acción desconocida: $type")
        }
    }

    private fun shotReset(sec: Int) {
        set(shot, sec * 1000L)
        shot.running = clock.running
    }

    private fun config(m: Map<String, Any?>) {
        m.str("title")?.let { title = it.trim().take(60) }
        val fullBefore = !clock.running && clock.remaining == curLength()
        m.num("periodMinutes")?.takeIf { it > 0 && it <= 30 }?.let { periodLength = (it * 60_000).roundToLong() }
        m.num("otMinutes")?.takeIf { it > 0 && it <= 15 }?.let { otLength = (it * 60_000).roundToLong() }
        if (fullBefore) set(clock, curLength())
        for (side in listOf("home", "away")) {
            val c = m.obj(side) ?: continue
            val t = team(side)
            c.str("name")?.trim()?.takeIf { it.isNotEmpty() }?.let { t.name = it.take(60) }
            c.str("short")?.trim()?.takeIf { it.isNotEmpty() }?.let { t.short = it.take(4).uppercase() }
            c.str("color")?.takeIf { COLOR.matches(it) }?.let { t.color = it }
            c.str("roster")?.let { t.players = parseRoster(it, t.players) }
        }
        addLog("Configuración actualizada")
    }

    private fun parseRoster(text: String, old: List<Player>): MutableList<Player> {
        val out = mutableListOf<Player>()
        for (raw in text.lines()) {
            val mt = ROSTER_LINE.matchEntire(raw.trim()) ?: continue
            val num = mt.groupValues[1]
            val name = mt.groupValues[2].trim().take(40).ifEmpty { "Jugador $num" }
            val prev = old.firstOrNull { it.num == num }
            out += prev?.also { it.name = name } ?: Player(nextId++, num, name)
            if (out.size >= 20) break
        }
        return out
    }

    private fun newGame() {
        for (t in listOf(home, away)) {
            t.score = 0; t.fouls = 0; t.timeouts = 0
            t.players.forEach { it.points = 0; it.fouls = 0 }
        }
        period = 1
        pause(clock); pause(shot)
        set(clock, periodLength)
        set(shot, 24_000)
        possession = null
        log.clear()
        undoStack.clear()
        addLog("Nuevo partido")
    }

    // ------------------------------------------------------------------ deshacer
    // Se deshacen puntos, faltas, tiempos, cambios y posesión; los relojes no.
    private fun snapshot(): String = Json.stringify(
        linkedMapOf("home" to home, "away" to away, "possession" to possession, "log" to log)
    )

    @Suppress("UNCHECKED_CAST")
    private fun restore(snap: String) {
        val m = Json.parseObject(snap)
        home = Team.from(m.obj("home")!!)
        away = Team.from(m.obj("away")!!)
        possession = m.str("possession")
        log = ArrayList((m.list("log") ?: emptyList()).mapNotNull { (it as? Map<String, Any?>)?.let(LogEntry::from) })
    }

    // ------------------------------------------------------------------ vencimientos
    /** Se llama cada pocos milisegundos. Devuelve true si cambió algo que haya que publicar. */
    @Synchronized
    fun tick(): Boolean {
        var changed = false
        if (clock.running && rem(clock) <= 0) {
            pause(clock); pause(shot)
            buzzer++
            addLog("Fin de ${periodLabel()}")
            changed = true
        }
        if (shot.running && rem(shot) <= 0) {
            pause(shot); pause(clock)
            buzzer++
            addLog("Violación de reloj de tiro")
            changed = true
        }
        return changed
    }

    // ------------------------------------------------------------------ JSON
    private fun stateMap(forSave: Boolean): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>(
            "title" to title, "period" to period, "periods" to periods,
            "periodLength" to periodLength, "otLength" to otLength,
            "clock" to if (forSave) Clock(false, rem(clock)) else clock,
            "shot" to if (forSave) Clock(false, rem(shot)) else shot,
            "possession" to possession, "home" to home, "away" to away,
            "buzzer" to buzzer, "log" to log
        )
        if (!forSave) m["now"] = now()
        return m
    }

    /** Lo que reciben las pantallas (incluye la hora del servidor para sincronizar). */
    @Synchronized
    fun publicJson(): String = Json.stringify(stateMap(false))

    /** Lo que se guarda en disco: los relojes quedan en pausa con el tiempo que tenían. */
    @Synchronized
    fun saveJson(): String = Json.stringify(stateMap(true))

    /** Recupera un partido guardado. Si el texto no es válido, no cambia nada. */
    @Synchronized
    @Suppress("UNCHECKED_CAST")
    fun load(json: String): Boolean = try {
        val m = Json.parseObject(json)
        val newHome = Team.from(m.obj("home") ?: error("sin local"))
        val newAway = Team.from(m.obj("away") ?: error("sin visitante"))
        home = newHome
        away = newAway
        m.str("title")?.let { title = it }
        m.num("period")?.let { period = it.toInt().coerceIn(1, 12) }
        m.num("periodLength")?.let { periodLength = it.toLong() }
        m.num("otLength")?.let { otLength = it.toLong() }
        m.obj("clock")?.num("remaining")?.let { clock.remaining = it.toLong(); clock.running = false }
        m.obj("shot")?.num("remaining")?.let { shot.remaining = it.toLong(); shot.running = false }
        possession = m.str("possession")
        buzzer = m.num("buzzer")?.toInt() ?: 0
        log = ArrayList((m.list("log") ?: emptyList()).mapNotNull { (it as? Map<String, Any?>)?.let(LogEntry::from) })
        nextId = (home.players + away.players).maxOfOrNull { it.id }?.plus(1) ?: 1
        undoStack.clear()
        true
    } catch (e: Exception) {
        false
    }

    companion object {
        private val UNDOABLE = setOf("score", "foul", "timeout", "sub", "possession", "swapPossession")
        private val COLOR = Regex("^#[0-9a-fA-F]{6}$")
        private val ROSTER_LINE = Regex("^#?(\\d{1,3})\\s*[-.)]?\\s*(.*)$")
    }
}

