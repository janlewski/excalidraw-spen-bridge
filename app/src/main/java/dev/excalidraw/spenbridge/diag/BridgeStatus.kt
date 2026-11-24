package dev.excalidraw.spenbridge.diag

internal data class BridgeLive(
    val listening: Boolean = false,
    val bindError: String? = null,
    val port: Int = 47631,
    val sourceState: String = "unavailable",
    val sourceReason: String? = "shizuku-not-running",
    val sourceNode: String? = null,
    val sourceName: String? = null,
    val buttonDown: Boolean = false,
    val buttonSeq: Int = 0,
    val lastLatencyMs: Long? = null,
    val pressCount: Int = 0,
    val lastKeyHex: String? = null,
    val fingerprint: String = "",
    val serviceRunning: Boolean = false,
    val penClientCount: Int = 0,
    val clientSummary: String = "",
    val lastRejection: String? = null,
    val devices: String = "not checked",
    val readAccess: String = "not-tried",
    val learnResult: String = "not run",
    val oneUi: String = "unknown",
)

internal object BridgeStatus {
    private val lock = Any()
    private var live = BridgeLive()

    fun get(): BridgeLive = synchronized(lock) { live }

    fun update(block: (BridgeLive) -> BridgeLive) {
        synchronized(lock) { live = block(live) }
    }
}
