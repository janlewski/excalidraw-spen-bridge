package dev.excalidraw.spenbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Stays resident the way LibrePods does: a sticky foreground service and a quiet ongoing
 * notification. The process is idle except for the localhost accept loop. It still does not
 * receive S Pen button events while another app is on screen.
 */
class BridgeService : Service() {
    private lateinit var secrets: SecretStore
    private lateinit var bridge: VaultBridge
    private lateinit var prefs: android.content.SharedPreferences
    private val worker = Executors.newSingleThreadExecutor()
    private var hub: LoopbackHub? = null
    private var statusLine = "Starting"

    override fun onCreate() {
        super.onCreate()
        secrets = SecretStore(applicationContext)
        bridge = VaultBridge(applicationContext)
        prefs = getSharedPreferences(BridgeState.PREFS, MODE_PRIVATE)
        createChannel()
        showForeground(statusLine)
        val started = runCatching {
            val endpoint = LoopbackHub(BridgeEndpoint.PORT) { pairingToken().orEmpty() }
            endpoint.start()
            hub = endpoint
            statusLine = "Idle on ${BridgeEndpoint.HOST}:${endpoint.boundPort}"
        }
        if (started.isFailure) statusLine = "Listener failed: ${started.exceptionOrNull()?.message}"
        showForeground(statusLine)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showForeground(statusLine)
        when (intent?.action) {
            ACTION_STOP -> {
                shutdownChannel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_OPEN_MENU -> worker.execute {
                emit(intent.getStringExtra(EXTRA_ORIGIN) ?: "Bridge")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        shutdownChannel()
        worker.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun emit(origin: String) {
        val result = runCatching {
            val secret = secrets.loadOrCreate()
            val sequence = prefs.getLong(BridgeState.KEY_SEQUENCE, 0) + 1
            val event = BridgeEvent(deviceId(), sequence, System.currentTimeMillis(), BridgeAction.OPEN_MENU)
            bridge.writePending(event, secret).getOrThrow()
            prefs.edit().putLong(BridgeState.KEY_SEQUENCE, sequence).commit()
            hub?.publish(event.signedJson(secret))
            sequence
        }
        statusLine = result.fold(
            onSuccess = { sequence -> "open-menu #$sequence from $origin" },
            onFailure = { failure -> "Command failed: ${failure.message}" },
        )
        showForeground(statusLine)
    }

    private fun shutdownChannel() {
        hub?.stop()
        hub = null
    }

    private fun deviceId(): String {
        prefs.getString(BridgeState.KEY_DEVICE_ID, null)?.let { return it }
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(BridgeState.KEY_DEVICE_ID, created).commit()
        return created
    }

    private fun pairingToken(): String? {
        val secret = secrets.load() ?: return null
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
    }

    private fun showForeground(text: String) {
        val notification = notification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openMenu = PendingIntent.getService(
            this,
            1,
            Intent(this, BridgeService::class.java)
                .setAction(ACTION_OPEN_MENU)
                .putExtra(EXTRA_ORIGIN, "Notification"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle("S Pen bridge")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_stat_bridge, "Open menu", openMenu)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "S Pen bridge", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Keeps the localhost channel open for Obsidian. No sound."
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_OPEN_MENU = "dev.excalidraw.spenbridge.OPEN_MENU"
        const val ACTION_STOP = "dev.excalidraw.spenbridge.STOP"
        const val EXTRA_ORIGIN = "origin"
        private const val CHANNEL_ID = "bridge"
        private const val NOTIFICATION_ID = 1
    }
}
