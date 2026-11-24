package dev.excalidraw.spenbridge

import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class BridgeAction(val wireValue: String) {
    OPEN_MENU("open-menu"),
}

data class BridgeEvent(
    val deviceId: String,
    val sequence: Long,
    val issuedAtMs: Long,
    val action: BridgeAction,
) {
    /** Fixed field order is the HMAC input. Do not sign serializer output. */
    fun canonicalPayload(): String =
        "{\"action\":${jsonString(action.wireValue)},\"deviceId\":${jsonString(deviceId)},\"issuedAtMs\":$issuedAtMs,\"sequence\":$sequence,\"version\":$VERSION}"

    fun signedJson(secret: ByteArray): String {
        val payload = canonicalPayload()
        val signature = hmacSha256Base64Url(payload, secret)
        return payload.removeSuffix("}") + ",\"signature\":${jsonString(signature)}}"
    }

    companion object {
        const val VERSION = 1
        const val MAX_AGE_MS = 5_000L

        fun hmacSha256Base64Url(payload: String, secret: ByteArray): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret, "HmacSHA256"))
            val digest = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }
    }
}

/** JSON string encoding shared with the plugin. Controls become `\u00xx`; other non-ASCII is left as UTF-8. */
internal fun jsonString(value: String): String = buildString {
    append('"')
    for (ch in value) {
        when {
            ch == '"' || ch == '\\' -> append('\\').append(ch)
            ch.code <= 0x1F -> append(String.format(Locale.US, "\\u%04x", ch.code))
            else -> append(ch)
        }
    }
    append('"')
}
