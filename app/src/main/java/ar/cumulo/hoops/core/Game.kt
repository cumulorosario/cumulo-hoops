package ar.cumulo.hoops.core

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Estado y reglas del partido de básquet. Es la única fuente de verdad: las pantallas solo
 * muestran lo que esta clase publica.
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
        var onCourt: Boolean = false, var points: Int = 0, var fouls: Int = 0,
        /** Libres, dobles y triples convertidos (para la planilla). */
        var ft: Int = 0, var fg2: Int = 0, var fg3: Int = 0,
        var photo: String? = null
    ) : JsonWritable {
        override fun toJson() = linkedMapOf(
            "id" to id, "num" to num, "name" to name, "onCourt" to onCourt, "points" to points, "fouls" to fouls,
            "ft" to ft, "fg2" to fg2, "fg3" to fg3, "photo" to photo
        )

        fun statsJson() = linkedMapOf(
            "id" to id, "onCourt" to onCourt, "points" to points, "fouls" to fouls, "ft" to ft, "fg2" to fg2, "fg3" to fg3
        )

        fun applyStats(m: Map<String, Any?>) {
            onCourt = m["onCourt"] == true
            points = m.int("points"); fouls = m.int("fouls")
            ft = m.int("ft"); fg2 = m.int("fg2"); fg3 = m.int("fg3")
        }

        companion object {
            fun from(m: Map<String, Any?>) = Player(
                id = m.int("id"),
                num = m.str("num") ?: "0",
                name = m.str("name") ?: "",
                photo = m.str("photo")?.takeIf { MEDIA.matches(it) }
            ).also { it.applyStats(m) }
        }
    }

    class Team(
        var name: String, var short: String, var color: String,
        var score: Int = 0, var fouls: Int = 0, var timeouts: Int = 0,
        var players: MutableList<Player> = mutableListOf(),
        var logo: String? = null,
        /** Puntos convertidos en cada período (índice 0 = primer cuarto). */
        var byPeriod: MutableList<Int> = mutableListOf()
    ) : JsonWritable {
        override fun toJson() = linkedMapOf(
            "name" to name, "short" to short, "color" to color, "logo" to logo, "score" to score,
            "fouls" to fouls, "timeouts" to timeouts, "byPeriod" to byPeriod, "players" to players
        )

        fun statsJson() = linkedMapOf(
            "score" to score, "fouls" to fouls, "timeouts" to timeouts, "byPeriod" to byPeriod,
            "players" to players.map { it.statsJson() }
        )

        fun applyStats(m: Map<String, Any?>) {
            score = m.int("score"); fouls = m.int("fouls"); timeouts = m.int("timeouts")
            byPeriod = (m.list("byPeriod") ?: emptyList()).map { (it as? Number)?.toInt() ?: 0 }.toMutableList()
        }

        companion object {
            @Suppress("UNCHECKED_CAST")
            fun from(m: Map<String, Any?>) = Team(
                name = m.str("name") ?: "",
                short = m.str("short") ?: "",
                color = m.str("color")?.takeIf { COLOR.matches(it) } ?: "#888888",
                players = (m.list("players") ?: emptyList()).mapNotNull { (it as? Map<String, Any?>)?.let(Player::from) }.toMutableList(),
                logo = m.str("logo")?.takeIf { MEDIA.matches(it) }
            ).also { it.applyStats(m) }
        }
    }

    class LogEntry(val p: String, val c: String, val text: String) : JsonWritable {
        override fun toJson() = linkedMapOf("p" to p, "c" to c, "text" to text)

        companion object {
            fun from(m: Map<String, Any?>) = LogEntry(m.str("p") ?: "", m.str("c") ?: "", m.str("text") ?: "")
        }
    }

    // ------------------------------------------------------------------ estado
    val sport = "basquet"
    var title = "U13 Tira Blanca"; private set
    /** Cuándo empezó este partido (para la planilla y lo que se comparte). */
    var gameDate = now(); private set
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
        while (log.size > 500) log.removeAt(log.size - 1)
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
                val pts = (m.num("pts") ?: 0.0).toInt().coerceIn(-3, 3)
                if (pts == 0 || t.score + pts < 0) return
                val p = player(t, m.num("playerId"))
                t.score += pts
                while (t.byPeriod.size < period) t.byPeriod.add(0)
                t.byPeriod[period - 1] += pts
                p?.let {
                    it.points = max(0, it.points + pts)
                    when (pts) { 1 -> it.ft++; 2 -> it.fg2++; 3 -> it.fg3++ }
                }
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

            "team" -> editTeam(team(m.str("team")), m)
            "player" -> editPlayer(team(m.str("team")), m)
            "removePlayer" -> {
                val t = team(m.str("team"))
                val p = player(t, m.num("playerId")) ?: throw IllegalArgumentException("Jugador inexistente")
                t.players.remove(p)
                addLog("Se quitó del plantel ${t.short} · #${p.num} ${p.name}")
            }
            "removePhoto" -> {
                val t = team(m.str("team"))
                if (m["playerId"] == null) t.logo = null
                else (player(t, m.num("playerId")) ?: throw IllegalArgumentException("Jugador inexistente")).photo = null
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

    private fun editTeam(t: Team, c: Map<String, Any?>) {
        c.str("name")?.trim()?.takeIf { it.isNotEmpty() }?.let { t.name = it.take(60) }
        c.str("short")?.trim()?.takeIf { it.isNotEmpty() }?.let { t.short = it.take(4).uppercase() }
        c.str("color")?.takeIf { COLOR.matches(it) }?.let { t.color = it }
    }

    /** Alta (sin playerId) o modificación de un jugador: número, nombre y si está en cancha. */
    private fun editPlayer(t: Team, m: Map<String, Any?>) {
        val existing = m["playerId"]?.let { player(t, m.num("playerId")) ?: throw IllegalArgumentException("Jugador inexistente") }
        val num = (m.str("num") ?: existing?.num ?: "").trim().removePrefix("#")
        if (!NUMBER.matches(num)) throw IllegalArgumentException("El número tiene que tener de 1 a 3 cifras")
        if (t.players.any { it.num == num && it !== existing }) throw IllegalArgumentException("Ya hay un jugador con el número $num en ${t.short}")
        val name = (m.str("name") ?: existing?.name ?: "").trim().replace(Regex("\\s+"), " ").take(40).ifEmpty { "Jugador $num" }
        if (existing == null) {
            if (t.players.size >= MAX_PLAYERS) throw IllegalArgumentException("Máximo $MAX_PLAYERS jugadores por equipo")
            val p = Player(nextId++, num, name, onCourt = m["onCourt"] == true)
            t.players.add(p)
            addLog("Se agregó al plantel ${t.short} · #${p.num} ${p.name}")
        } else {
            existing.num = num
            existing.name = name
            (m["onCourt"] as? Boolean)?.let { existing.onCourt = it }
        }
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
            editTeam(t, c)
            c.str("roster")?.let { t.players = parseRoster(it, t.players) }
        }
        addLog("Configuración actualizada")
    }

    private fun parseRoster(text: String, old: List<Player>): MutableList<Player> {
        val out = mutableListOf<Player>()
        for (raw in text.lines()) {
            val mt = ROSTER_LINE.matchEntire(raw.trim()) ?: continue
            val num = mt.groupValues[1]
            if (out.any { it.num == num }) continue
            val name = mt.groupValues[2].trim().take(40).ifEmpty { "Jugador $num" }
            val prev = old.firstOrNull { it.num == num }
            out += prev?.also { it.name = name } ?: Player(nextId++, num, name)
            if (out.size >= MAX_PLAYERS) break
        }
        return out
    }

    private fun newGame() {
        for (t in listOf(home, away)) {
            t.score = 0; t.fouls = 0; t.timeouts = 0
            t.byPeriod.clear()
            t.players.forEach { it.points = 0; it.fouls = 0; it.ft = 0; it.fg2 = 0; it.fg3 = 0 }
        }
        period = 1
        pause(clock); pause(shot)
        set(clock, periodLength)
        set(shot, 24_000)
        possession = null
        gameDate = now()
        log.clear()
        undoStack.clear()
        addLog("Nuevo partido")
    }

    // ------------------------------------------------------------------ fotos
    /** Cambia el escudo de un equipo. Solo acepta direcciones generadas por el servidor. */
    @Synchronized
    fun setTeamLogo(side: String, url: String?) {
        require(url == null || MEDIA.matches(url)) { "Imagen inválida" }
        team(side).logo = url
    }

    @Synchronized
    fun setPlayerPhoto(side: String, playerId: Int, url: String?) {
        require(url == null || MEDIA.matches(url)) { "Imagen inválida" }
        val p = team(side).players.firstOrNull { it.id == playerId } ?: throw IllegalArgumentException("Jugador inexistente")
        p.photo = url
    }

    /** Imágenes que usa el partido; las demás se pueden borrar. */
    @Synchronized
    fun mediaInUse(): Set<String> = listOf(home, away).flatMap { t -> listOf(t.logo) + t.players.map { it.photo } }
        .filterNotNull().map { it.substringAfterLast('/') }.toSet()

    // ------------------------------------------------------------------ deshacer
    // Se deshacen marcador, faltas, tiempos, cambios y posesión. No se tocan los relojes,
    // ni los nombres, números o fotos (si después de anotar se corrige un nombre, deshacer
    // el doble no debe volver a escribirlo mal).
    private fun snapshot(): String = Json.stringify(
        linkedMapOf("home" to home.statsJson(), "away" to away.statsJson(), "possession" to possession, "log" to log)
    )

    @Suppress("UNCHECKED_CAST")
    private fun restore(snap: String) {
        val m = Json.parseObject(snap)
        for ((side, t) in listOf("home" to home, "away" to away)) {
            val s = m.obj(side) ?: continue
            t.applyStats(s)
            val byId = (s.list("players") ?: emptyList()).mapNotNull { it as? Map<String, Any?> }.associateBy { it.int("id") }
            for (p in t.players) byId[p.id]?.let(p::applyStats)
        }
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
            "sport" to sport, "title" to title, "gameDate" to gameDate, "period" to period, "periods" to periods,
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
        m.num("gameDate")?.let { gameDate = it.toLong() }
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
        const val MAX_PLAYERS = 20
        private val UNDOABLE = setOf("score", "foul", "timeout", "sub", "possession", "swapPossession")
        private val COLOR = Regex("^#[0-9a-fA-F]{6}$")
        private val NUMBER = Regex("^\\d{1,3}$")
        private val ROSTER_LINE = Regex("^#?(\\d{1,3})\\s*[-.)]?\\s*(.*)$")
        /** Direcciones de imágenes guardadas por el servidor (ver MediaStore). */
        val MEDIA = Regex("^/media/[0-9a-f]{16,40}\\.(jpg|png)$")
    }
}

private fun Map<String, Any?>.int(key: String): Int = num(key)?.toInt() ?: 0
