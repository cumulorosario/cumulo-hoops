package ar.cumulo.hoops.core

import java.io.File
import java.security.SecureRandom

/**
 * Fotos de escudos y jugadores, guardadas solo en este equipo (nunca salen a internet).
 * Cada imagen nueva recibe un nombre al azar, así las pantallas no muestran una versión vieja
 * guardada en caché y nadie puede adivinar direcciones.
 */
class MediaStore(private val dir: File) {
    private val random = SecureRandom()

    init { dir.mkdirs() }

    /** Guarda la imagen y devuelve su dirección (/media/...). Solo acepta JPEG y PNG. */
    fun save(bytes: ByteArray): String {
        val ext = when {
            bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "jpg"
            bytes.size > 8 && bytes.copyOfRange(0, 8).contentEquals(PNG) -> "png"
            else -> throw IllegalArgumentException("La imagen tiene que ser JPG o PNG")
        }
        require(bytes.size <= MAX_BYTES) { "La imagen es demasiado grande" }
        val id = ByteArray(10).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val file = File(dir, "$id.$ext")
        val tmp = File(dir, "$id.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        return "/media/${file.name}"
    }

    fun read(name: String): ByteArray? {
        if (!NAME.matches(name)) return null
        val f = File(dir, name)
        return if (f.isFile) f.readBytes() else null
    }

    /** Borra las imágenes que ya no usa nadie (fotos reemplazadas o jugadores quitados). */
    fun cleanup(inUse: Set<String>) {
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name !in inUse && (NAME.matches(f.name) || f.name.endsWith(".tmp"))) f.delete()
        }
    }

    companion object {
        const val MAX_BYTES = 2_000_000
        private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val NAME = Regex("^[0-9a-f]{16,40}\\.(jpg|png)$")
    }
}
