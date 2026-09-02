package com.tvcast.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Фоновый сервис: держит Ktor-сервер и регистрацию mDNS, пока приложение открыто.
 * Foreground service нужен, чтобы система не убила сервер во время долгой загрузки файла.
 */
class ServerService : Service() {

    private var server: WebServer? = null
    private var nsd: NsdHelper? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())

        MediaRepo.refresh()

        val ip = NetUtils.localIp()
        CastState.serverUrl.value = if (ip != null) "http://$ip:${WebServer.PORT}" else ""
        CastState.lastError.value = if (ip == null) "Нет подключения к сети. Подключите телевизор к Wi-Fi или кабелю." else ""

        server = WebServer(applicationContext).also { it.start() }
        nsd = NsdHelper(applicationContext).also {
            it.register(WebServer.PORT, "TVCast ${Build.MODEL ?: "Android TV"}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        nsd?.unregister()
        server?.stop()
        nsd = null
        server = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(CastState.serverUrl.value.ifBlank { "Ожидание сети…" })
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "tvcast_server"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, ServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ServerService::class.java))
        }
    }
}
