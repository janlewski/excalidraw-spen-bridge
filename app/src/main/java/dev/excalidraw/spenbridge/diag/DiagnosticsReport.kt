package dev.excalidraw.spenbridge.diag

internal data class ReportInput(
    val versionName: String,
    val versionCode: Int,
    val proto: Int,
    val buildType: String,
    val gitSha: String,
    val manufacturer: String,
    val model: String,
    val device: String,
    val androidRelease: String,
    val sdkInt: Int,
    val oneUi: String,
    val securityPatch: String,
    val shizukuInstalled: Boolean,
    val shizukuVersion: String,
    val shizukuRunning: Boolean,
    val shizukuUid: Int?,
    val shizukuPermission: Boolean,
    val userServiceBound: Boolean,
    val devices: String,
    val readAccess: String,
    val buttonDown: Boolean,
    val buttonSeq: Int,
    val lastKeyHex: String?,
    val latencyMs: Long?,
    val pressCount: Int,
    val learnResult: String,
    val listening: Boolean,
    val bindError: String?,
    val port: Int,
    val penClients: Int,
    val clientSummary: String,
    val lastRejection: String?,
    val fingerprint: String,
    val ignoringBattery: Boolean?,
    val serviceRunning: Boolean,
    val notifications: Boolean?,
    val airCommand: String,
    val vaultSelected: Boolean,
    val log: List<String>,
    val pairingCode: String = "",
    val vaultPath: String = "",
    val serial: String = "",
)

internal object DiagnosticsReport {
    fun from(input: ReportInput): Map<String, Any?> = linkedMapOf(
        "report" to "spen-bridge-diag",
        "schema" to 1,
        "versionName" to input.versionName,
        "versionCode" to input.versionCode,
        "proto" to input.proto,
        "buildType" to input.buildType,
        "gitSha" to input.gitSha,
        "manufacturer" to input.manufacturer,
        "model" to input.model,
        "device" to input.device,
        "android" to input.androidRelease,
        "sdk" to input.sdkInt,
        "oneUi" to input.oneUi,
        "securityPatch" to input.securityPatch,
        "shizukuInstalled" to input.shizukuInstalled,
        "shizukuVersion" to input.shizukuVersion,
        "shizukuRunning" to input.shizukuRunning,
        "shizukuUid" to input.shizukuUid,
        "shizukuPermission" to input.shizukuPermission,
        "userServiceBound" to input.userServiceBound,
        "devices" to input.devices,
        "readAccess" to input.readAccess,
        "buttonDown" to input.buttonDown,
        "buttonSeq" to input.buttonSeq,
        "lastKey" to input.lastKeyHex,
        "latencyMs" to input.latencyMs,
        "pressCount" to input.pressCount,
        "learn" to input.learnResult,
        "listening" to input.listening,
        "bindError" to input.bindError,
        "port" to input.port,
        "penClients" to input.penClients,
        "clients" to input.clientSummary,
        "lastRejection" to input.lastRejection,
        "fingerprint" to input.fingerprint,
        "ignoringBattery" to input.ignoringBattery,
        "serviceRunning" to input.serviceRunning,
        "notifications" to input.notifications,
        "airCommand" to input.airCommand,
        "vaultSelected" to if (input.vaultSelected) "yes" else "no",
        "log" to input.log.takeLast(100),
    )

    fun text(input: ReportInput): String {
        val json = json(input)
        return buildString {
            appendLine("S Pen bridge ${input.versionName} (${input.gitSha})")
            appendLine("${input.manufacturer} ${input.model} Android ${input.androidRelease} One UI ${input.oneUi}")
            appendLine("Shizuku installed=${input.shizukuInstalled} running=${input.shizukuRunning} uid=${input.shizukuUid ?: "none"} permission=${input.shizukuPermission}")
            appendLine("Server listening=${input.listening} port=${input.port} clients=${input.penClients}")
            appendLine("Pen ${input.sourceLine()} read=${input.readAccess} learn=${input.learnResult}")
            appendLine("Fingerprint ${input.fingerprint.ifBlank { "unknown" }} vault selected: ${if (input.vaultSelected) "yes" else "no"}")
            appendLine("```json")
            appendLine(json)
            append("```")
        }
    }

    fun json(input: ReportInput): String = buildString {
        val fields = from(input)
        append('{')
        fields.entries.forEachIndexed { index, (key, value) ->
            if (index > 0) append(',')
            append('"').append(key).append('"').append(':').append(jsonValue(value))
        }
        append('}')
    }

    fun formatOneUi(raw: String?): String {
        val digits = raw?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return "unknown"
        val number = digits.toIntOrNull() ?: return "unknown"
        if (digits.length < 5) return "unknown"
        return "${number / 10000}.${(number / 100) % 100}.${number % 100}"
    }

    private fun ReportInput.sourceLine(): String =
        if (buttonDown) "down seq=$buttonSeq" else "up seq=$buttonSeq"

    private fun jsonValue(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        is Boolean -> value.toString()
        is Number -> value.toString()
        is List<*> -> value.joinToString(prefix = "[", postfix = "]") { jsonValue(it) }
        else -> "\"$value\""
    }
}
