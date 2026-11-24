package dev.excalidraw.spenbridge.pen

import android.content.Context
import android.os.IBinder
import dev.excalidraw.spenbridge.IPenInput
import dev.excalidraw.spenbridge.IPenListener
import java.io.FileInputStream
import java.io.FileNotFoundException

/** Runs as the Shizuku shell user. Only the configured key code leaves this process. */
class PenInputUserService() : IPenInput.Stub() {
    @Suppress("unused")
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this()

    private var reader: EvdevReader? = null
    private var thread: Thread? = null
    private var chosenNode: String? = null
    private var chosenName: String? = null
    private var death: IBinder.DeathRecipient? = null

    override fun start(listener: IPenListener, nodeOverride: String, keyCode: Int) {
        stop()
        watch(listener)
        thread = Thread {
            try {
                val text = runGetevent()
                val devices = PenDeviceDiscovery.parseGeteventLp(text)
                val chosen = PenDeviceDiscovery.pickPenNode(devices, nodeOverride.takeIf { it.isNotBlank() })
                if (chosen == null) {
                    listener.onSource("unavailable", "no-pen-node", "", "")
                    return@Thread
                }
                chosenNode = chosen.node
                chosenName = chosen.name
                listener.onSource("ok", "", chosen.node, chosen.name)
                val evdev = EvdevReader(chosen.node)
                reader = evdev
                evdev.run(
                    if (keyCode == 0) EvdevAssembler.BTN_STYLUS else keyCode,
                    { down, timeUs -> listener.onButton(down, timeUs) },
                    { listener.onButton(false, System.currentTimeMillis() * 1000) },
                )
            } catch (error: Exception) {
                listener.onSource("unavailable", reasonFor(error), chosenNode.orEmpty(), chosenName.orEmpty())
            }
        }.apply { isDaemon = true; name = "pen-evdev"; start() }
    }

    override fun stop() {
        thread?.interrupt()
        reader?.close()
        thread = null
        reader = null
        death?.let { recipient ->
            // The binder may already be dead.
        }
        death = null
    }

    override fun destroy() {
        stop()
        System.exit(0)
    }

    override fun describeDevices(): String = runGetevent()

    override fun learn(listener: IPenListener, timeoutMs: Int) {
        val node = chosenNode
        if (node.isNullOrBlank()) {
            listener.onSource("learned", "timeout", "", "")
            return
        }
        val worker = Thread {
            try {
                FileInputStream(node).use { input ->
                    val buffer = ByteArray(24 * 64)
                    val deadline = System.currentTimeMillis() + timeoutMs
                    var armed: Int? = null
                    while (System.currentTimeMillis() < deadline && !Thread.currentThread().isInterrupted) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        for (event in parseEvdev(buffer, count, android.os.Process.is64Bit())) {
                            if (event.type != EvdevAssembler.EV_KEY || EvdevAssembler.ignoredLearnKey(event.code)) continue
                            if (event.value == 1) armed = event.code
                            if (event.value == 0 && armed == event.code) {
                                listener.onSource("learned", "0x${event.code.toString(16)}", node, chosenName.orEmpty())
                                return@Thread
                            }
                        }
                    }
                }
                listener.onSource("learned", "timeout", node, chosenName.orEmpty())
            } catch (error: Exception) {
                listener.onSource("learned", "timeout", node, chosenName.orEmpty())
            }
        }
        worker.isDaemon = true
        worker.start()
        worker.join(timeoutMs.toLong() + 200)
        worker.interrupt()
    }

    override fun probeAccess(node: String, timeoutMs: Int): String {
        val target = node.ifBlank { chosenNode.orEmpty() }
        if (target.isBlank()) return "not-tried"
        return try {
            FileInputStream(target).use { input ->
                val buffer = ByteArray(64)
                val worker = Thread { input.read(buffer) }
                worker.isDaemon = true
                worker.start()
                worker.join(timeoutMs.toLong())
                if (worker.isAlive) {
                    input.close()
                    worker.join(200)
                    "zero-bytes"
                } else {
                    "ok:1"
                }
            }
        } catch (error: Exception) {
            val message = (error.message ?: "open failed").take(200)
            if (shellInInputGroup() && (message.contains("Permission", true) || message.contains("EACCES"))) {
                "selinux:$message"
            } else {
                "eacces:$message"
            }
        }
    }

    override fun getProp(key: String): String {
        if (key != "ro.build.version.oneui") return ""
        return try {
            val process = ProcessBuilder("getprop", key).start()
            process.inputStream.bufferedReader().readText().trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun watch(listener: IPenListener) {
        val recipient = IBinder.DeathRecipient { stop() }
        death = recipient
        try {
            listener.asBinder().linkToDeath(recipient, 0)
        } catch (_: Exception) {
        }
    }

    private fun runGetevent(): String = try {
        val process = ProcessBuilder("getevent", "-lp").redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor()
        text
    } catch (error: Exception) {
        error.message ?: "getevent failed"
    }

    private fun reasonFor(error: Exception): String {
        val message = error.message ?: ""
        return when {
            error is FileNotFoundException && !message.contains("Permission", true) -> "no-pen-node"
            shellInInputGroup() && message.contains("Permission", true) -> "selinux-denied"
            message.contains("Permission", true) || message.contains("EACCES") -> "permission-denied"
            else -> "read-error"
        }
    }

    private fun shellInInputGroup(): Boolean = try {
        val process = ProcessBuilder("id").redirectErrorStream(true).start()
        process.inputStream.bufferedReader().readText().contains("input")
    } catch (_: Exception) {
        false
    }
}
