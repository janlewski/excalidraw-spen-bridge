package dev.excalidraw.spenbridge

/** Vault-relative mailbox. The plugin reads [PENDING_FILE] and ignores [INCOMING_FILE]. */
object BridgePaths {
    val directorySegments: List<String> = listOf(
        ".obsidian",
        "plugins",
        "excalidraw-stylus-controls",
        "spen-bridge",
    )
    const val PENDING_FILE = "pending.json"
    const val INCOMING_FILE = "pending-incoming.json"

    fun pendingDisplayPath(): String = (directorySegments + PENDING_FILE).joinToString("/")
}
