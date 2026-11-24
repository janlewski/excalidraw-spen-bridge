package dev.excalidraw.spenbridge.pen

import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class RawInputEvent(val timeUs: Long, val type: Int, val code: Int, val value: Int)

internal sealed interface ButtonUpdate {
    data class Level(val down: Boolean, val timeUs: Long) : ButtonUpdate
    data object Dropped : ButtonUpdate
}

internal fun parseEvdev(buffer: ByteArray, length: Int, is64: Boolean): List<RawInputEvent> {
    val size = if (is64) 24 else 16
    val events = mutableListOf<RawInputEvent>()
    var offset = 0
    while (offset + size <= length) {
        val view = ByteBuffer.wrap(buffer, offset, size).order(ByteOrder.LITTLE_ENDIAN)
        val seconds: Long
        val micros: Long
        if (is64) {
            seconds = view.long
            micros = view.long
        } else {
            seconds = view.int.toLong()
            micros = view.int.toLong()
        }
        val type = view.short.toInt() and 0xffff
        val code = view.short.toInt() and 0xffff
        val value = view.int
        events += RawInputEvent(seconds * 1_000_000L + micros, type, code, value)
        offset += size
    }
    return events
}

/** Commits a key only on SYN_REPORT. SYN_DROPPED forgets the partial frame. */
internal class EvdevAssembler(private val keyCode: Int) {
    private var pending: Boolean? = null
    private var pendingTimeUs = 0L
    private var dropping = false

    fun push(event: RawInputEvent): ButtonUpdate? = when {
        event.type == EV_SYN && event.code == SYN_DROPPED -> {
            dropping = true
            pending = null
            ButtonUpdate.Dropped
        }
        event.type == EV_SYN && event.code == SYN_REPORT -> {
            val update = if (!dropping) pending?.let { ButtonUpdate.Level(it, pendingTimeUs) } else null
            pending = null
            dropping = false
            update
        }
        event.type == EV_KEY && event.code == keyCode && event.value != 2 -> {
            pending = event.value == 1
            pendingTimeUs = event.timeUs
            null
        }
        else -> null
    }

    companion object {
        const val EV_SYN = 0
        const val EV_KEY = 1
        const val SYN_REPORT = 0
        const val SYN_DROPPED = 3
        const val BTN_TOUCH = 0x14a
        const val BTN_STYLUS = 0x14b

        fun ignoredLearnKey(code: Int): Boolean = code == BTN_TOUCH || code in 0x140..0x147
    }
}

/** Blocking reader of struct input_event. Never grabs the device. */
internal class EvdevReader(
    private val path: String,
    private val is64: Boolean = android.os.Process.is64Bit(),
) {
    @Volatile private var stream: FileInputStream? = null

    fun run(keyCode: Int, onButton: (Boolean, Long) -> Unit, onDropped: () -> Unit) {
        FileInputStream(path).use { input ->
            stream = input
            try {
                val size = if (is64) 24 else 16
                val buffer = ByteArray(size * 64)
                val assembler = EvdevAssembler(keyCode)
                while (!Thread.currentThread().isInterrupted) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    for (event in parseEvdev(buffer, count, is64)) {
                        when (val update = assembler.push(event)) {
                            is ButtonUpdate.Level -> onButton(update.down, update.timeUs)
                            ButtonUpdate.Dropped -> onDropped()
                            null -> Unit
                        }
                    }
                }
            } finally {
                stream = null
            }
        }
    }

    fun close() {
        try {
            stream?.close()
        } catch (_: Exception) {
        }
    }
}
