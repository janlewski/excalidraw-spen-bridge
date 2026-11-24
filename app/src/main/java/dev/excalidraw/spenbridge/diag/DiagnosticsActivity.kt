package dev.excalidraw.spenbridge.diag

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.excalidraw.spenbridge.BridgeEndpoint
import dev.excalidraw.spenbridge.BridgeService
import dev.excalidraw.spenbridge.BuildConfig
import dev.excalidraw.spenbridge.SecretStore
import dev.excalidraw.spenbridge.VaultBridge
import dev.excalidraw.spenbridge.ws.PenAuth
import dev.excalidraw.spenbridge.ws.PenLoopback
import rikka.shizuku.Shizuku

class DiagnosticsActivity : AppCompatActivity() {
    private lateinit var body: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@DiagnosticsActivity).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (16 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
                addView(Button(context).apply {
                    text = "Copy report"
                    setOnClickListener { copyReport() }
                })
                addView(Button(context).apply {
                    text = "Self-test"
                    setOnClickListener { selfTest() }
                })
                addView(Button(context).apply {
                    text = "Clear log"
                    setOnClickListener {
                        DiagLog.clear()
                        render()
                    }
                })
                body = TextView(context).apply {
                    textSize = 13f
                    setPadding(0, pad, 0, 0)
                }
                addView(body)
            })
        })
    }

    override fun onStart() {
        super.onStart()
        handler.post(refresh)
    }

    override fun onStop() {
        handler.removeCallbacks(refresh)
        super.onStop()
    }

    private fun render() {
        body.text = DiagnosticsReport.text(currentInput())
    }

    private fun copyReport() {
        val text = DiagnosticsReport.text(currentInput())
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("S Pen bridge diagnostics", text))
        body.text = "Report copied.\n\n${body.text}"
    }

    private fun selfTest() {
        val lines = mutableListOf<String>()
        val live = BridgeStatus.get()
        lines += if (live.listening) "✅ server bound ${BridgeEndpoint.HOST}:${live.port}" else "❌ server not listening ${live.bindError.orEmpty()}"
        val shizuku = runCatching {
            val running = Shizuku.pingBinder()
            val permitted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            if (!running) "❌ Shizuku not running" else if (!permitted) "❌ Shizuku permission needed" else "✅ Shizuku running uid ${Shizuku.getUid()}"
        }.getOrElse { "❌ Shizuku not installed" }
        lines += shizuku
        ContextCompat.startForegroundService(this, Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_DESCRIBE))
        ContextCompat.startForegroundService(this, Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_PROBE))
        lines += "Hover the S Pen, then press the side button. Probe and learn were requested."
        ContextCompat.startForegroundService(this, Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_LEARN))
        val secret = runCatching { SecretStore(this).load() }.getOrNull()
        lines += if (secret == null || !live.listening) {
            "❌ loopback handshake not run"
        } else {
            val result = PenLoopback.handshake(live.port, secret, timeoutMs = 2_000)
            if (result.ready != null && result.serverProofOk) "✅ loopback handshake" else "❌ loopback handshake http ${result.httpStatus} close ${result.closeCode}"
        }
        body.text = lines.joinToString("\n") + "\n\n" + DiagnosticsReport.text(currentInput())
    }

    private fun currentInput(): ReportInput {
        val live = BridgeStatus.get()
        val pm = getSystemService(PowerManager::class.java)
        return ReportInput(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            proto = BuildConfig.PROTO,
            buildType = BuildConfig.BUILD_TYPE,
            gitSha = BuildConfig.GIT_SHA,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            oneUi = live.oneUi.ifBlank { DiagnosticsReport.formatOneUi(systemProperty("ro.build.version.oneui")) },
            securityPatch = Build.VERSION.SECURITY_PATCH,
            shizukuInstalled = packageInstalled("moe.shizuku.privileged.api"),
            shizukuVersion = packageVersion("moe.shizuku.privileged.api"),
            shizukuRunning = runCatching { Shizuku.pingBinder() }.getOrDefault(false),
            shizukuUid = runCatching { Shizuku.getUid() }.getOrNull(),
            shizukuPermission = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false),
            userServiceBound = live.sourceState == "ok",
            devices = live.devices,
            readAccess = live.readAccess,
            buttonDown = live.buttonDown,
            buttonSeq = live.buttonSeq,
            lastKeyHex = live.lastKeyHex,
            latencyMs = live.lastLatencyMs,
            pressCount = live.pressCount,
            learnResult = live.learnResult,
            listening = live.listening,
            bindError = live.bindError,
            port = live.port,
            penClients = live.penClientCount,
            clientSummary = live.clientSummary,
            lastRejection = live.lastRejection,
            fingerprint = live.fingerprint.ifBlank { runCatching { SecretStore(this).load()?.let(PenAuth::fingerprint) }.getOrNull().orEmpty() },
            ignoringBattery = runCatching { pm.isIgnoringBatteryOptimizations(packageName) }.getOrNull(),
            serviceRunning = live.serviceRunning,
            notifications = if (Build.VERSION.SDK_INT >= 33) {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                NotificationManagerCompat.from(this).areNotificationsEnabled()
            },
            airCommand = readAirCommand(),
            vaultSelected = VaultBridge(this).selectedTree() != null,
            log = DiagLog.snapshot(100),
        )
    }

    private fun packageInstalled(name: String): Boolean = packageVersion(name) != "not installed"

    private fun packageVersion(name: String): String = try {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(name, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(name, 0)
        }
        info.versionName ?: "installed"
    } catch (_: PackageManager.NameNotFoundException) {
        "not installed"
    }

    private fun readAirCommand(): String {
        val keys = listOf("air_cmd_use", "spen_air_command")
        return try {
            for (key in keys) {
                val system = Settings.System.getString(contentResolver, key)
                if (!system.isNullOrBlank()) return "$key=$system"
                val secure = Settings.Secure.getString(contentResolver, key)
                if (!secure.isNullOrBlank()) return "$key=$secure"
            }
            "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun systemProperty(key: String): String? = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val method = clazz.getMethod("get", String::class.java)
        method.invoke(null, key) as? String
    } catch (_: Exception) {
        null
    }
}
