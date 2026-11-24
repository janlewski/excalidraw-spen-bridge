package dev.excalidraw.spenbridge.diag

import java.util.ArrayDeque

/** In-memory ring. Never written to disk and never given coordinates or secrets. */
internal object DiagLog {
    private const val CAPACITY = 200
    private val lines = ArrayDeque<String>()

    fun log(level: String, tag: String, message: String) {
        val line = "${System.currentTimeMillis()} $level $tag $message"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > CAPACITY) lines.removeFirst()
        }
    }

    fun snapshot(limit: Int = 100): List<String> = synchronized(lines) { lines.toList().takeLast(limit) }

    fun clear() {
        synchronized(lines) { lines.clear() }
    }

    fun size(): Int = synchronized(lines) { lines.size }
}
