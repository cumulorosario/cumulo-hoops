package ar.cumulo.hoops

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import ar.cumulo.hoops.core.AssetSource
import ar.cumulo.hoops.core.Game
import ar.cumulo.hoops.core.MediaStore
import ar.cumulo.hoops.core.Security
import ar.cumulo.hoops.core.WebServer
import java.io.File
import java.io.IOException

/**
 * Servicio en primer plano que mantiene vivo el servidor del tablero aunque la app
 * quede en segundo plano o se apague la pantalla.
 */
class ServerService : Service() {

    private var server: WebServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        goForeground(getString(R.string.notif_starting))
        if (server == null) startServer()
        return START_STICKY
    }

    private fun startServer() {
        val game = Game()
        val saveFile = File(filesDir, "partido.json")
        if (saveFile.isFile) game.load(saveFile.readText())

        val assetSource = AssetSource { path ->
            try { assets.open(path).use { it.readBytes() } } catch (_: IOException) { null }
        }
        // Contraseñas y sesiones de los dispositivos, y fotos de escudos y jugadores: quedan en el celular.
        val secFile = File(filesDir, "seguridad.json")
        val security = Security(persist = { json -> writeAtomic(secFile, json) })
        if (secFile.isFile) security.load(secFile.readText())
        val media = MediaStore(File(filesDir, "media"))
        val ws = WebServer(game, assetSource, PORT, persist = { json -> writeAtomic(saveFile, json) },
            security = security, media = media)
        try {
            ws.start()
        } catch (e: IOException) {
            Log.e(TAG, "No se pudo abrir el puerto $PORT", e)
            lastError = getString(R.string.error_port, PORT)
            stopEverything()
            return
        }
        server = ws
        instance = this
        lastError = null
        acquireLocks()
        goForeground(addressText())
    }

    fun addressText(): String {
        val ip = server?.lanIPs()?.firstOrNull()
        return if (ip != null) getString(R.string.notif_running, "http://$ip:$PORT") else getString(R.string.notif_running_noip)
    }

    fun ips(): List<String> = server?.lanIPs().orEmpty()
    fun screens(): Int = server?.screenCount ?: 0

    private fun goForeground(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    /** Refresca el texto de la notificación (por ejemplo cuando aparece la IP del hotspot). */
    fun refreshNotification() {
        if (server != null) goForeground(addressText())
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CumuloHoops:servidor").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "CumuloHoops:wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun stopEverything() {
        server?.stop()
        server = null
        instance = null
        try { wakeLock?.release() } catch (_: Exception) { }
        try { wifiLock?.release() } catch (_: Exception) { }
        wakeLock = null
        wifiLock = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
        listener?.invoke()
    }

    override fun onDestroy() {
        if (server != null) stopEverything()
        super.onDestroy()
    }

    @Synchronized
    private fun writeAtomic(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        const val PORT = 3000
        const val ACTION_STOP = "ar.cumulo.hoops.STOP"
        private const val CHANNEL = "servidor"
        private const val NOTIF_ID = 1
        private const val TAG = "CumuloHoops"

        @Volatile var instance: ServerService? = null
            private set
        @Volatile var lastError: String? = null
        /** La pantalla principal se entera cuando el servidor se detiene desde la notificación. */
        @Volatile var listener: (() -> Unit)? = null

        val isRunning: Boolean get() = instance?.server != null

        fun start(ctx: Context) {
            val i = Intent(ctx, ServerService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ServerService::class.java).setAction(ACTION_STOP))
        }
    }
}
