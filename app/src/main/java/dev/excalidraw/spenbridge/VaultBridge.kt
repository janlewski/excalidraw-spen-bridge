package dev.excalidraw.spenbridge

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

class VaultBridge(private val context: Context) {
    private val prefs = context.getSharedPreferences("bridge-vault", Context.MODE_PRIVATE)

    fun selectedTree(): Uri? = prefs.getString("tree", null)?.let(Uri::parse)

    fun setTree(uri: Uri) {
        val resolver = context.contentResolver
        resolver.takePersistableUriPermission(uri, IntentFlags)
        val previous = selectedTree()
        prefs.edit().putString("tree", uri.toString()).apply()
        if (previous != null && previous != uri) {
            runCatching { resolver.releasePersistableUriPermission(previous, IntentFlags) }
        }
    }

    fun writePending(event: BridgeEvent, secret: ByteArray): Result<Unit> = runCatching {
        val root = selectedTree()?.let { DocumentFile.fromTreeUri(context, it) }
            ?: error("Choose an Obsidian vault first")
        val directory = BridgePaths.directorySegments.fold(root) { parent, name ->
            parent.findFile(name) ?: parent.createDirectory(name) ?: error("Cannot create $name")
        }
        val resolver = context.contentResolver
        val json = event.signedJson(secret)
        // application/json + a .json name so DocumentsProvider does not append another extension.
        directory.findFile(BridgePaths.INCOMING_FILE)?.delete()
        val temporary = directory.createFile("application/json", BridgePaths.INCOMING_FILE)
            ?: error("Cannot create ${BridgePaths.INCOMING_FILE}")
        writeText(resolver, temporary.uri, json)
        directory.findFile(BridgePaths.PENDING_FILE)?.delete()
        if (!temporary.renameTo(BridgePaths.PENDING_FILE)) {
            val pending = directory.createFile("application/json", BridgePaths.PENDING_FILE)
                ?: error("Cannot create ${BridgePaths.PENDING_FILE}")
            writeText(resolver, pending.uri, json)
            temporary.delete()
        }
    }

    private fun writeText(resolver: android.content.ContentResolver, uri: Uri, json: String) {
        resolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(json) }
            ?: error("Cannot open $uri")
    }

    companion object {
        private const val IntentFlags =
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
