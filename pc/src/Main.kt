package ar.cumulo.hoops.pc

import ar.cumulo.hoops.core.AssetSource
import ar.cumulo.hoops.core.Game
import ar.cumulo.hoops.core.MediaStore
import ar.cumulo.hoops.core.Security
import ar.cumulo.hoops.core.WebServer
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.net.URI

/**
 * Cúmulo Hoops para PC: el mismo servidor que la app Android, para usar una notebook
 * como servidor. Las pantallas van dentro del .jar; el partido, las contraseñas y las
 * fotos se guardan en la carpeta "datos" junto al .jar.
 *
 *   java -jar cumulo-hoops-pc.jar            (puerto 3000)
 *   java -jar cumulo-hoops-pc.jar --abrir    (además abre la página Conectar)
 *   PORT=8080 java -jar cumulo-hoops-pc.jar
 */
fun main(args: Array<String>) {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 3000
    val home = File(Main::class.java.protectionDomain.codeSource.location.toURI()).parentFile ?: File(".")
    val data = File(home, "datos").apply { mkdirs() }

    val game = Game()
    val saveFile = File(data, "partido.json")
    if (saveFile.isFile && game.load(saveFile.readText())) println("  Partido anterior recuperado")
    val secFile = File(data, "seguridad.json")
    val security = Security(persist = { writeAtomic(secFile, it) })
    if (secFile.isFile) security.load(secFile.readText())

    val assets = AssetSource { p -> Main::class.java.getResourceAsStream("/web/$p")?.use { it.readBytes() } }
    val server = WebServer(game, assets, port, persist = { writeAtomic(saveFile, it) },
        security = security, media = MediaStore(File(data, "media")))
    try {
        server.start()
    } catch (e: IOException) {
        System.err.println("\n  No se pudo abrir el puerto $port. ¿Hay otro servidor abierto? Cerralo o usá otro puerto.\n")
        kotlin.system.exitProcess(1)
    }
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })

    val line = "  " + "─".repeat(58)
    println("\n$line\n   CÚMULO HOOPS · servidor de tablero listo\n   Desarrollado por Cúmulo Ingeniería\n$line")
    println("   En esta compu (QR y contraseñas):  http://localhost:$port")
    val ips = server.lanIPs()
    if (ips.isEmpty()) println("\n   ⚠ No se detectó red. Conectate a un WiFi o activá el hotspot.")
    for (ip in ips) {
        println("\n   Red $ip")
        println("     Tablero TV:   http://$ip:$port/tablero")
        println("     Reloj 24 s:   http://$ip:$port/posesion")
        println("     Operador:     http://$ip:$port/operador")
    }
    println("$line\n   Datos guardados en: ${data.absolutePath}\n   Dejá esta ventana abierta mientras dure el partido.\n")

    if ("--abrir" in args) {
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI("http://localhost:$port/"))
        } catch (_: Exception) { /* sin navegador */ }
    }
    Thread.currentThread().join()
}

private class Main

@Synchronized
private fun writeAtomic(file: File, text: String) {
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
}
