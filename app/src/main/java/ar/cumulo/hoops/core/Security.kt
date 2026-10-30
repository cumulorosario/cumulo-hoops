package ar.cumulo.hoops.core

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Contraseñas por pantalla (operador, tablero, reloj de 24 y relato accesible) y sesiones de los dispositivos.
 *
 * - Una pantalla sin contraseña queda abierta para cualquiera conectado a la red.
 * - Quien entra como operador también puede abrir el tablero y el reloj de 24.
 * - Las contraseñas se guardan con sal y hash, nunca en texto plano.
 * - Las sesiones sobreviven a un reinicio del servidor, así las TVs no piden la clave cada vez.
 * - Si se cambia la contraseña de una pantalla, los dispositivos que entraron con la anterior
 *   tienen que volver a ingresarla.
 * - Demasiados intentos fallidos desde un mismo dispositivo lo bloquean unos minutos.
 */
class Security(
    private val now: () -> Long = { System.currentTimeMillis() },
    private val persist: ((String) -> Unit)? = null,
) {
    sealed class Login {
        class Ok(val token: String) : Login()
        object Open : Login()
        object Wrong : Login()
        class Blocked(val seconds: Long) : Login()
    }

    private class Secret(val salt: String, val hash: String)
    private class Session(val roles: MutableSet<String>, var lastSeen: Long)

    private val random = SecureRandom()
    private val secrets = HashMap<String, Secret>()
    private val sessions = LinkedHashMap<String, Session>()
    private val failures = HashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun isProtected(screen: String) = screen in secrets

    /** Qué pantallas tienen contraseña. */
    @Synchronized
    fun summary(): Map<String, Boolean> = SCREENS.associateWith { it in secrets }

    /** Cambia o quita (texto vacío) la contraseña de una pantalla. */
    @Synchronized
    fun setPassword(screen: String, password: String?) {
        require(screen in SCREENS) { "Pantalla inválida" }
        val pw = password?.trim().orEmpty()
        if (pw.isEmpty()) {
            secrets.remove(screen)
        } else {
            require(pw.length in 4..64) { "La contraseña debe tener entre 4 y 64 caracteres" }
            val salt = hex(ByteArray(16).also(random::nextBytes))
            secrets[screen] = Secret(salt, hash(salt, pw))
        }
        // Los que entraron con la contraseña anterior tienen que volver a ingresar
        for (s in sessions.values) s.roles.remove(screen)
        sessions.values.removeAll { it.roles.isEmpty() }
        save()
    }

    @Synchronized
    fun login(screen: String, password: String, client: String, currentToken: String?): Login {
        require(screen in SCREENS) { "Pantalla inválida" }
        val secret = secrets[screen] ?: return Login.Open
        val t = now()
        val fails = failures.getOrPut(client) { ArrayDeque() }
        while (fails.isNotEmpty() && t - fails.first() > WINDOW_MS) fails.removeFirst()
        if (fails.size >= MAX_FAILS) return Login.Blocked(((fails.first() + WINDOW_MS - t) / 1000).coerceAtLeast(1))

        if (!MessageDigest.isEqual(hash(secret.salt, password.trim()).toByteArray(), secret.hash.toByteArray())) {
            fails.addLast(t)
            return Login.Wrong
        }
        fails.clear()
        val token = currentToken?.takeIf { it in sessions } ?: newToken()
        val s = sessions.getOrPut(token) { Session(mutableSetOf(), t) }
        s.roles.add(screen)
        s.lastSeen = t
        while (sessions.size > MAX_SESSIONS) sessions.remove(sessions.keys.first())
        save()
        return Login.Ok(token)
    }

    @Synchronized
    fun logout(token: String?) {
        if (token != null && sessions.remove(token) != null) save()
    }

    /** ¿Este dispositivo puede abrir la pantalla? */
    @Synchronized
    fun allowed(screen: String, token: String?): Boolean {
        if (screen !in secrets) return true
        val roles = token?.let { sessions[it]?.roles } ?: return false
        return screen in roles || (screen != OPERATOR && OPERATOR in roles && OPERATOR in secrets)
    }

    /** Con qué pantallas entró este dispositivo. */
    @Synchronized
    fun rolesOf(token: String?): Set<String> = token?.let { sessions[it]?.roles?.toSet() }.orEmpty()

    // ------------------------------------------------------------------ guardado
    @Synchronized
    fun toJson(): String = Json.stringify(
        linkedMapOf(
            "passwords" to secrets.mapValues { (_, s) -> linkedMapOf("salt" to s.salt, "hash" to s.hash) },
            "sessions" to sessions.mapValues { (_, s) -> linkedMapOf("roles" to s.roles.toList(), "lastSeen" to s.lastSeen) }
        )
    )

    @Synchronized
    fun load(json: String): Boolean = try {
        val m = Json.parseObject(json)
        secrets.clear(); sessions.clear()
        m.obj("passwords")?.forEach { (k, v) ->
            val o = v as? Map<*, *> ?: return@forEach
            val salt = o["salt"] as? String; val h = o["hash"] as? String
            if (k in SCREENS && salt != null && h != null) secrets[k] = Secret(salt, h)
        }
        m.obj("sessions")?.forEach { (k, v) ->
            val o = v as? Map<*, *> ?: return@forEach
            val roles = (o["roles"] as? List<*>)?.filterIsInstance<String>()?.filter { it in SCREENS }?.toMutableSet()
            if (TOKEN.matches(k) && !roles.isNullOrEmpty()) sessions[k] = Session(roles, (o["lastSeen"] as? Number)?.toLong() ?: 0)
        }
        true
    } catch (e: Exception) {
        false
    }

    private fun save() { persist?.invoke(toJson()) }

    private fun newToken() = hex(ByteArray(24).also(random::nextBytes))

    private fun hash(salt: String, pw: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        var h = (salt + pw).toByteArray(Charsets.UTF_8)
        repeat(ROUNDS) { h = md.digest(h + salt.toByteArray()) }
        return hex(h)
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    companion object {
        const val OPERATOR = "operador"
        val SCREENS = listOf(OPERATOR, "tablero", "posesion", "relato")
        val TOKEN = Regex("^[0-9a-f]{48}$")
        private const val ROUNDS = 20_000
        private const val MAX_FAILS = 8
        private const val WINDOW_MS = 5 * 60_000L
        private const val MAX_SESSIONS = 200
    }
}
