package dev.excalidraw.spenbridge.pen

internal data class PenSnapshot(
    val down: Boolean = false,
    val seq: Int = 0,
    val tMs: Double = 0.0,
    val sourceState: String = "unavailable",
    val sourceReason: String? = "shizuku-not-running",
    val sourceNode: String? = null,
    val sourceName: String? = null,
)

internal class PenState {
    private var down = false
    private var seq = 0
    private var tMs = 0.0
    private var sourceState = "unavailable"
    private var sourceReason: String? = "shizuku-not-running"
    private var sourceNode: String? = null
    private var sourceName: String? = null

    @Synchronized fun snapshot(): PenSnapshot =
        PenSnapshot(down, seq, tMs, sourceState, sourceReason, sourceNode, sourceName)

    /** Returns a button frame only when the level actually changes. */
    @Synchronized fun apply(levelDown: Boolean, tUs: Long): PenSnapshot? {
        if (levelDown == down) return null
        down = levelDown
        seq += 1
        tMs = tUs / 1000.0
        return snapshot()
    }

    /** An unavailable source forces the button up so a client cannot stay stuck down. */
    @Synchronized fun source(state: String, reason: String?, node: String?, name: String?): PenSnapshot? {
        sourceState = state
        sourceReason = reason
        sourceNode = node
        sourceName = name
        if (state == "unavailable" && down) {
            down = false
            seq += 1
            return snapshot()
        }
        return null
    }
}
