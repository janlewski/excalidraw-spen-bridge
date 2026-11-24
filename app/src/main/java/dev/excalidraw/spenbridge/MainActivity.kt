package dev.excalidraw.spenbridge

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import androidx.core.content.ContextCompat
import java.util.ArrayDeque
import java.util.Base64

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var inputLog: TextView
    private lateinit var bridge: VaultBridge
    private lateinit var secrets: SecretStore
    private val inputLines = ArrayDeque<String>()
    private var lastLoggedButtons: Int? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startBridgeService()
        else renderStatus("Allow notifications to keep the bridge running. Android requires that quiet notification.")
    }
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
        BootStart.setEnabled(this, BootStart.isEnabled(this))
        setContentView(buildContent())
        val secretMessage = runCatching { secrets.loadOrCreate() }.fold(
            onSuccess = { "Pairing secret is stored in Android Keystore." },
            onFailure = { "Pairing secret error: ${it.message}" },
        )
        val vault = if (bridge.selectedTree() == null) "not selected" else "selected"
        renderStatus("Ready. $secretMessage Vault: $vault.")
        if (BootStart.isEnabled(this)) ensureBridgeRunning()
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
                text = "Stays running under a quiet notification, the way a Bluetooth companion does. The plugin connects to 127.0.0.1:${BridgeEndpoint.PORT}. Samsung still delivers the S Pen button only while this screen is in front."
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
                text = "Allow unrestricted battery"
                setOnClickListener {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName"),
                        ),
                    )
                }
            })
            addView(Button(context).apply {
                text = "Open companion app settings"
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            })
            addView(SwitchCompat(context).apply {
                text = "Keep the bridge running"
                isSingleLine = false
                isChecked = BootStart.isEnabled(context)
                setPadding(0, pad / 2, 0, 0)
                setOnCheckedChangeListener { _, checked ->
                    BootStart.setEnabled(this@MainActivity, checked)
                    if (checked) {
                        ensureBridgeRunning()
                        renderStatus("Bridge stays up, including after a restart. It waits idle until a command is sent.")
                    } else {
                        stopService(Intent(this@MainActivity, BridgeService::class.java))
                        renderStatus("Bridge stopped. It will not come back after a restart.")
                    }
                }
            })
            addView(TextView(context).apply {
                text = "On by default. A quiet notification stays in the shade. The process does not poll or draw while it waits. Samsung can still freeze it unless battery use is unrestricted."
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

    private fun ensureBridgeRunning() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startBridgeService()
    }

    private fun startBridgeService() {
        ContextCompat.startForegroundService(this, Intent(this, BridgeService::class.java))
    }

    private fun sendOpenMenu(origin: String) {
        val command = Intent(this, BridgeService::class.java)
            .setAction(BridgeService.ACTION_OPEN_MENU)
            .putExtra(BridgeService.EXTRA_ORIGIN, origin)
        ContextCompat.startForegroundService(this, command)
        renderStatus("$origin sent open-menu to the running bridge. A connected plugin gets it on localhost; the vault file is updated too.")
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
