package dev.excalidraw.spenbridge.pen

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import dev.excalidraw.spenbridge.BridgeState
import dev.excalidraw.spenbridge.BuildConfig
import dev.excalidraw.spenbridge.IPenInput
import dev.excalidraw.spenbridge.IPenListener
import dev.excalidraw.spenbridge.diag.BridgeStatus
import dev.excalidraw.spenbridge.diag.DiagLog
import rikka.shizuku.Shizuku

internal interface PenSink {
    fun button(down: Boolean, timeUs: Long)
    fun source(state: String, reason: String?, node: String?, name: String?)
}

internal class ShizukuPenSource(private val context: Context, private val sink: PenSink) {
    private val args = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, PenInputUserService::class.java.name),
    )
        .tag("pen-input")
        .processNameSuffix("pen")
        .daemon(false)
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private var service: IPenInput? = null
    private val onBinder = Shizuku.OnBinderReceivedListener { bindIfPermitted() }
    private val onDead = Shizuku.OnBinderDeadListener {
        service = null
        sink.source("unavailable", "shizuku-not-running", null, null)
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val input = IPenInput.Stub.asInterface(binder) ?: return
            service = input
            val prefs = context.getSharedPreferences(BridgeState.PREFS, Context.MODE_PRIVATE)
            val node = prefs.getString(BridgeState.KEY_PEN_NODE, "").orEmpty()
            val key = prefs.getInt(BridgeState.KEY_PEN_KEYCODE, EvdevAssembler.BTN_STYLUS)
            runCatching {
                val described = input.describeDevices()
                BridgeStatus.update { it.copy(devices = summarizeDevices(described)) }
                input.start(listener, node, key)
            }.onFailure { error ->
                DiagLog.log("W", "shizuku", "start failed ${error.message}")
                sink.source("unavailable", "read-error", null, null)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            sink.source("unavailable", "shizuku-not-running", null, null)
        }
    }
    private val listener = object : IPenListener.Stub() {
        override fun onButton(down: Boolean, kernelTimeUs: Long) {
            sink.button(down, kernelTimeUs)
        }

        override fun onSource(state: String, reason: String, node: String, name: String) {
            if (state == "learned") {
                val code = reason.removePrefix("0x").toIntOrNull(16)
                if (code != null) {
                    context.getSharedPreferences(BridgeState.PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putInt(BridgeState.KEY_PEN_KEYCODE, code)
                        .putString(BridgeState.KEY_PEN_NODE, node)
                        .apply()
                }
                BridgeStatus.update { it.copy(learnResult = "code $reason on $node ($name)") }
                DiagLog.log("I", "pen", "learned $reason on $name")
                return
            }
            sink.source(
                state,
                reason.ifBlank { null },
                node.ifBlank { null },
                name.ifBlank { null },
            )
        }
    }

    fun start() {
        Shizuku.addBinderReceivedListenerSticky(onBinder)
        Shizuku.addBinderDeadListener(onDead)
    }

    fun stop() {
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        Shizuku.removeBinderReceivedListener(onBinder)
        Shizuku.removeBinderDeadListener(onDead)
        service = null
    }

    fun rebind() {
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        service = null
        bindIfPermitted()
    }

    fun probe() {
        val input = service
        val node = BridgeStatus.get().sourceNode.orEmpty()
        if (input == null) {
            BridgeStatus.update { it.copy(readAccess = "not-tried") }
            return
        }
        val result = runCatching { input.probeAccess(node, 2_000) }.getOrElse { error ->
            "eacces:${error.message?.take(200) ?: "probe failed"}"
        }
        BridgeStatus.update { it.copy(readAccess = result) }
        DiagLog.log("I", "pen", "probe $result")
    }

    fun learn() {
        val input = service ?: run {
            BridgeStatus.update { it.copy(learnResult = "not run") }
            return
        }
        BridgeStatus.update { it.copy(learnResult = "waiting") }
        runCatching { input.learn(listener, 5_000) }.onFailure { error ->
            BridgeStatus.update { it.copy(learnResult = "timeout") }
            DiagLog.log("W", "pen", "learn failed ${error.message}")
        }
    }

    fun describe() {
        val input = service ?: return
        val text = runCatching { input.describeDevices() }.getOrDefault("")
        BridgeStatus.update { it.copy(devices = summarizeDevices(text)) }
    }

    private fun bindIfPermitted() {
        try {
            if (Shizuku.isPreV11() || !Shizuku.pingBinder()) {
                sink.source("unavailable", "shizuku-not-running", null, null)
                return
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                sink.source("unavailable", "shizuku-permission-denied", null, null)
                return
            }
            Shizuku.bindUserService(args, connection)
        } catch (error: Exception) {
            DiagLog.log("W", "shizuku", error.message ?: "bind failed")
            sink.source("unavailable", "shizuku-not-running", null, null)
        }
    }
}

internal fun summarizeDevices(text: String): String {
    val devices = PenDeviceDiscovery.parseGeteventLp(text)
    if (devices.isEmpty()) return "no devices"
    return devices.joinToString("\n") { device ->
        fun flag(label: String) = if (label in device.keys) "yes" else "no"
        "${device.node} ${device.name} BTN_STYLUS=${flag("BTN_STYLUS")} BTN_STYLUS2=${flag("BTN_STYLUS2")} BTN_TOOL_PEN=${flag("BTN_TOOL_PEN")}"
    }
}
