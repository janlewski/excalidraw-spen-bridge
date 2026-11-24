package dev.excalidraw.spenbridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import java.util.Base64
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var inputLog: TextView
    private lateinit var bridge: VaultBridge
    private lateinit var secrets: SecretStore
    private lateinit var statePrefs: android.content.SharedPreferences
    private val bridgeWriter: ExecutorService = Executors.newSingleThreadExecutor()
    private val inputLines = ArrayDeque<String>()
    private var lastLoggedButtons: Int? = null
    private val vaultPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching { bridge.setTree(uri) }
            .onSuccess { renderStatus("Vault selected: ${uri.lastPathSegment}") }
            .onFailure { failure -> renderStatus("Could not keep vault permission: ${failure.message}") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bridge = VaultBridge(applicationContext)
        secrets = SecretStore(applicationContext)
        statePrefs = getSharedPreferences(BridgeState.PREFS, MODE_PRIVATE)
        BootStart.setEnabled(this, BootStart.isEnabled(this))
        setContentView(buildContent())
        val secretMessage = runCatching { secrets.loadOrCreate() }.fold(
            onSuccess = { "Pairing secret is stored in Android Keystore." },
            onFailure = { "Pairing secret error: ${it.message}" },
        )
        val vault = if (bridge.selectedTree() == null) "not selected" else "selected"
        val reboot = if (intent.getBooleanExtra(BootStart.EXTRA_OPENED_FROM_REBOOT, false)) {
            " Opened after restart."
        } else {
            ""
        }
        renderStatus("Ready. $secretMessage Vault: $vault.$reboot")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (
            event.keyCode == KeyEvent.KEYCODE_F8 &&
            event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0
        ) {
            sendOpenMenu("Air Action")
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        noteMotion(event)
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        noteMotion(event)
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onDestroy() {
        bridgeWriter.shutdown()
        super.onDestroy()
    }

    private fun buildContent(): View = ScrollView(this).apply {
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(TextView(context).apply {
                text = "S Pen → Obsidian Bridge"
                textSize = 24f
            })
            addView(TextView(context).apply {
                text = "Signs one open-menu command into a vault you select. Samsung delivers S Pen actions only while this screen is in front. Nothing keeps running after you leave, so this uses no power in the background."
                setPadding(0, pad / 2, 0, pad / 2)
            })
            addView(Button(context).apply {
                text = "Choose Obsidian vault"
                setOnClickListener { vaultPicker.launch(null) }
            })
            addView(Button(context).apply {
                text = "Send signed test: Open menu"
                setOnClickListener { sendOpenMenu("Manual test") }
            })
            addView(Button(context).apply {
                text = "Copy pairing code"
                setOnClickListener { copyPairingCode() }
            })
            addView(Button(context).apply {
                text = "Open companion app settings"
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            })
            addView(SwitchCompat(context).apply {
                text = "Open this screen after the tablet restarts"
                isSingleLine = false
                isChecked = BootStart.isEnabled(context)
                setPadding(0, pad / 2, 0, 0)
                setOnCheckedChangeListener { _, checked ->
                    BootStart.setEnabled(this@MainActivity, checked)
                    renderStatus(
                        if (checked) {
                            "Will try to open once after the next restart. Android can block that launch. The app still stops when you leave it."
                        } else {
                            "Stays closed after a restart."
                        },
                    )
                }
            })
            addView(TextView(context).apply {
                text = "Off by default. The reboot receiver is disabled until you turn this on. It does not leave a listener running."
                setPadding(0, pad / 4, 0, 0)
            })
            status = TextView(context).apply {
                textSize = 14f
                setPadding(0, pad, 0, pad / 2)
            }
            addView(status)
            addView(TextView(context).apply {
                text = "Native button log"
                textSize = 18f
            })
            inputLog = TextView(context).apply {
                text = "Draw here with the S Pen. A barrel press should include STYLUS_PRIMARY."
                textSize = 14f
                setPadding(0, pad / 4, 0, 0)
            }
            addView(inputLog)
        })
    }

    private fun noteMotion(event: MotionEvent) {
        if (event.pointerCount == 0) return
        val action = event.actionMasked
        val buttons = event.buttonState
        val buttonChanged = lastLoggedButtons != buttons
        val sparse = action != MotionEvent.ACTION_MOVE && action != MotionEvent.ACTION_HOVER_MOVE
        if (!sparse && !buttonChanged) return
        lastLoggedButtons = buttons
        inputLines.addLast(describeMotion(action, event.getToolType(0), buttons, event.getPressure(0)))
        while (inputLines.size > MAX_INPUT_LINES) inputLines.removeFirst()
        inputLog.text = inputLines.joinToString("\n")
    }

    private fun sendOpenMenu(origin: String) {
        renderStatus("Writing signed open-menu…")
        bridgeWriter.execute {
            val result = runCatching {
                val secret = secrets.loadOrCreate()
                val sequence = statePrefs.getLong(BridgeState.KEY_SEQUENCE, 0) + 1
                val event = BridgeEvent(deviceId(), sequence, System.currentTimeMillis(), BridgeAction.OPEN_MENU)
                bridge.writePending(event, secret).getOrThrow()
                statePrefs.edit().putLong(BridgeState.KEY_SEQUENCE, sequence).commit()
                sequence
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { sequence ->
                    renderStatus("$origin wrote open-menu #$sequence to ${BridgePaths.pendingDisplayPath()}")
                }.onFailure { failure ->
                    renderStatus("Write failed: ${failure.message}")
                }
            }
        }
    }

    private fun deviceId(): String {
        statePrefs.getString(BridgeState.KEY_DEVICE_ID, null)?.let { return it }
        val created = UUID.randomUUID().toString()
        statePrefs.edit().putString(BridgeState.KEY_DEVICE_ID, created).commit()
        return created
    }

    private fun copyPairingCode() {
        val secret = runCatching { secrets.loadOrCreate() }.getOrElse { failure ->
            renderStatus("Pairing secret error: ${failure.message}")
            return
        }
        val code = Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("S Pen bridge pairing code", code))
        renderStatus("Pairing code copied. It is not written into the vault.")
    }

    private fun renderStatus(message: String) {
        status.text = "Status\n$message"
    }

    companion object {
        private const val MAX_INPUT_LINES = 8
    }
}
