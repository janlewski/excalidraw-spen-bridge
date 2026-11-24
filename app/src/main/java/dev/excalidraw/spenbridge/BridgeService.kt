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
import dev.excalidraw.spenbridge.diag.BridgeStatus
import dev.excalidraw.spenbridge.diag.DiagLog
import dev.excalidraw.spenbridge.pen.PenSink
import dev.excalidraw.spenbridge.pen.PenState
import dev.excalidraw.spenbridge.pen.ShizukuPenSource
import dev.excalidraw.spenbridge.ws.PenAuth
import dev.excalidraw.spenbridge.ws.ProtocolJson
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Sticky foreground service. It hosts the localhost WebSocket and the Shizuku pen binding.
 * The evdev reader sees the barrel button while Obsidian is in front.
 */
class BridgeService : Service(), PenSink {
    private lateinit var secrets: SecretStore
    private lateinit var bridge: VaultBridge
    private lateinit var prefs: android.content.SharedPreferences
    private val worker = Executors.newSingleThreadExecutor()
    private val pen = PenState()
    private var hub: LoopbackHub? = null
    private var penSource: ShizukuPenSource? = null
    private var statusLine = "Starting"
    private var commandSeq = 0

    override fun onCreate() {
        super.onCreate()
        secrets = SecretStore(applicationContext)
        bridge = VaultBridge(applicationContext)
        prefs = getSharedPreferences(BridgeState.PREFS, MODE_PRIVATE)
        createChannel()
        showForeground(statusLine)
        BridgeStatus.update { it.copy(serviceRunning = true, fingerprint = fingerprint()) }
        val started = runCatching {
            val endpoint = LoopbackHub(
                port = BridgeEndpoint.PORT,
                secret = { secrets.load() },
                pen = { pen.snapshot() },
                appVersion = BuildConfig.VERSION_NAME,
                onRejection = { code, reason ->
                    BridgeStatus.update { it.copy(lastRejection = "$code $reason") }
                },
            )
            endpoint.start()
            hub = endpoint
            statusLine = "Idle on ${BridgeEndpoint.HOST}:${endpoint.boundPort}"
            BridgeStatus.update { it.copy(listening = true, port = endpoint.boundPort, bindError = null) }
        }
        if (started.isFailure) {
            val message = started.exceptionOrNull()?.message
            statusLine = "Listener failed: $message"
            BridgeStatus.update { it.copy(listening = false, bindError = message) }
            DiagLog.log("E", "hub", message ?: "bind failed")
        }
        penSource = ShizukuPenSource(applicationContext, this).also { it.start() }
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
            ACTION_RECONNECT_PEN -> penSource?.rebind()
            ACTION_RESET_PAIRING -> {
                hub?.closePenClients(4401, "pairing reset")
                BridgeStatus.update { it.copy(fingerprint = fingerprint()) }
                DiagLog.log("I", "pairing", "clients closed after reset")
            }
            ACTION_PROBE -> worker.execute { penSource?.probe() }
            ACTION_LEARN -> worker.execute { penSource?.learn() }
            ACTION_DESCRIBE -> worker.execute { penSource?.describe() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        BridgeStatus.update { it.copy(serviceRunning = false, listening = false) }
        shutdownChannel()
        worker.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun button(down: Boolean, timeUs: Long) {
        val frame = pen.apply(down, timeUs) ?: return
        val sent = System.currentTimeMillis()
        hub?.broadcastPen(ProtocolJson.button(frame.down, frame.seq, frame.tMs, sent))
        val latency = sent - frame.tMs.toLong()
        BridgeStatus.update {
            it.copy(
                buttonDown = frame.down,
                buttonSeq = frame.seq,
                lastLatencyMs = latency,
                pressCount = if (frame.down) it.pressCount + 1 else it.pressCount,
                lastKeyHex = "0x14b",
            )
        }
        refreshClients()
        DiagLog.log("I", "pen", "button down=${frame.down} seq=${frame.seq} latencyMs=$latency")
    }

    override fun source(state: String, reason: String?, node: String?, name: String?) {
        val forced = pen.source(state, reason, node, name)
        hub?.broadcastPen(ProtocolJson.source(state, reason, node, name))
        if (forced != null) {
            val sent = System.currentTimeMillis()
            hub?.broadcastPen(ProtocolJson.button(false, forced.seq, forced.tMs, sent))
        }
        statusLine = if (state == "ok") "Pen: ok (${name ?: node ?: "pen"})" else "Pen: waiting for Shizuku"
        BridgeStatus.update {
            it.copy(sourceState = state, sourceReason = reason, sourceNode = node, sourceName = name, buttonDown = pen.snapshot().down)
        }
        showForeground(statusLine)
        DiagLog.log("I", "pen", "source $state ${reason.orEmpty()} ${name.orEmpty()}")
    }

    private fun emit(origin: String) {
        val result = runCatching {
            val secret = secrets.loadOrCreate()
            val sequence = prefs.getLong(BridgeState.KEY_SEQUENCE, 0) + 1
            val event = BridgeEvent(deviceId(), sequence, System.currentTimeMillis(), BridgeAction.OPEN_MENU)
            bridge.writePending(event, secret).getOrThrow()
            prefs.edit().putLong(BridgeState.KEY_SEQUENCE, sequence).commit()
            hub?.publish(event.signedJson(secret))
            commandSeq += 1
            hub?.broadcastPen(ProtocolJson.command("open-menu", commandSeq, System.currentTimeMillis()))
            sequence
        }
        statusLine = result.fold(
            onSuccess = { sequence -> "open-menu #$sequence from $origin" },
            onFailure = { failure -> "Command failed: ${failure.message}" },
        )
        showForeground(statusLine)
    }

    private fun shutdownChannel() {
        penSource?.stop()
        penSource = null
        hub?.stop()
        hub = null
    }

    private fun refreshClients() {
        val infos = hub?.penClientInfos().orEmpty()
        BridgeStatus.update {
            it.copy(
                penClientCount = infos.size,
                clientSummary = infos.joinToString("; ") { info ->
                    "origin=${info.origin.ifBlank { "none" }} auth=${info.authenticated} pongAgeMs=${info.pongAgeMs}"
                },
            )
        }
    }

    private fun fingerprint(): String = secrets.load()?.let { PenAuth.fingerprint(it) }.orEmpty()

    private fun deviceId(): String {
        prefs.getString(BridgeState.KEY_DEVICE_ID, null)?.let { return it }
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(BridgeState.KEY_DEVICE_ID, created).commit()
        return created
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
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle("S Pen bridge")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_stat_bridge, "Open menu", openMenu)
        if (pen.snapshot().sourceReason == "shizuku-not-running") {
            val launch = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (launch != null) {
                val openShizuku = PendingIntent.getActivity(
                    this,
                    2,
                    launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                builder.addAction(R.drawable.ic_stat_bridge, "Open Shizuku", openShizuku)
            }
        }
        return builder.build()
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
        const val ACTION_RECONNECT_PEN = "dev.excalidraw.spenbridge.RECONNECT_PEN"
        const val ACTION_RESET_PAIRING = "dev.excalidraw.spenbridge.RESET_PAIRING"
        const val ACTION_PROBE = "dev.excalidraw.spenbridge.PROBE"
        const val ACTION_LEARN = "dev.excalidraw.spenbridge.LEARN"
        const val ACTION_DESCRIBE = "dev.excalidraw.spenbridge.DESCRIBE"
        const val EXTRA_ORIGIN = "origin"
        private const val CHANNEL_ID = "bridge"
        private const val NOTIFICATION_ID = 1
    }
}
