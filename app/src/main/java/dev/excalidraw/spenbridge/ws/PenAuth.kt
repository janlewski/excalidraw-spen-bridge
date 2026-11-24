package dev.excalidraw.spenbridge.ws

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object PenAuth {
    private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun pairingCode(secret: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(secret)

    fun proof(secret: ByteArray, nonce: String, clientNonce: String, role: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val message = "spen-bridge/1|$role|$nonce|$clientNonce"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            mac.doFinal(message.toByteArray(Charsets.UTF_8)),
        )
    }

    fun fingerprint(secret: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(secret)
        val encoded = base32(digest)
        return encoded.substring(0, 4) + "-" + encoded.substring(4, 8)
    }

    fun newNonce(size: Int = 16, random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(size)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun base32(data: ByteArray): String = buildString {
        var buffer = 0
        var bits = 0
        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                append(BASE32[(buffer shr bits) and 31])
            }
        }
        if (bits > 0) append(BASE32[(buffer shl (5 - bits)) and 31])
    }
}
