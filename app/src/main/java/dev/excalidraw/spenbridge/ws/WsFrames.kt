package dev.excalidraw.spenbridge.ws

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64

internal sealed interface WsFrame {
    data class Text(val text: String) : WsFrame
    data class Ping(val payload: ByteArray) : WsFrame
    data class Pong(val payload: ByteArray) : WsFrame
    data class Close(val code: Int, val reason: String) : WsFrame
    data class ProtocolError(val code: Int, val reason: String) : WsFrame
}

internal object WsFrames {
    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    const val MAX_BYTES = 1024

    fun acceptKey(secWebSocketKey: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update((secWebSocketKey + GUID).toByteArray(Charsets.US_ASCII))
        return Base64.getEncoder().encodeToString(digest.digest())
    }

    fun encodeText(json: String): ByteArray = encodeFrame(0x1, json.toByteArray(Charsets.UTF_8), masked = false)

    fun encodeClose(code: Int, reason: String): ByteArray {
        val reasonBytes = reason.toByteArray(Charsets.UTF_8).let { bytes ->
            if (bytes.size > 123) bytes.copyOf(123) else bytes
        }
        val payload = ByteArray(2 + reasonBytes.size)
        payload[0] = (code shr 8).toByte()
        payload[1] = (code and 0xff).toByte()
        reasonBytes.copyInto(payload, 2)
        return encodeFrame(0x8, payload, masked = false)
    }

    fun encodePong(payload: ByteArray): ByteArray = encodeFrame(0xA, payload, masked = false)

    fun encodeMaskedText(json: String, mask: ByteArray = byteArrayOf(1, 2, 3, 4)): ByteArray =
        encodeFrame(0x1, json.toByteArray(Charsets.UTF_8), masked = true, mask = mask)

    fun readFrame(input: InputStream, requireMasked: Boolean): WsFrame? {
        val header = ByteArray(2)
        if (!readFully(input, header)) return null
        val first = header[0].toInt() and 0xff
        val second = header[1].toInt() and 0xff
        val fin = first and 0x80 != 0
        val opcode = first and 0x0f
        val masked = second and 0x80 != 0
        var length = second and 0x7f
        if (!fin) return WsFrame.ProtocolError(1003, "fragmented")
        if (length == 127) return WsFrame.ProtocolError(1009, "too large")
        if (length == 126) {
            val extended = ByteArray(2)
            if (!readFully(input, extended)) return null
            length = ((extended[0].toInt() and 0xff) shl 8) or (extended[1].toInt() and 0xff)
        }
        if (length > MAX_BYTES) return WsFrame.ProtocolError(1009, "too large")
        if (requireMasked && !masked) return WsFrame.ProtocolError(1002, "unmasked")
        if (!requireMasked && masked) return WsFrame.ProtocolError(1002, "masked server frame")
        val mask = ByteArray(4)
        if (masked && !readFully(input, mask)) return null
        val payload = ByteArray(length)
        if (length > 0 && !readFully(input, payload)) return null
        if (masked) {
            for (index in payload.indices) {
                payload[index] = (payload[index].toInt() xor (mask[index % 4].toInt() and 0xff)).toByte()
            }
        }
        return when (opcode) {
            0x1 -> WsFrame.Text(payload.toString(Charsets.UTF_8))
            0x8 -> closePayload(payload)
            0x9 -> WsFrame.Ping(payload)
            0xA -> WsFrame.Pong(payload)
            else -> WsFrame.ProtocolError(1003, "unsupported")
        }
    }

    private fun closePayload(payload: ByteArray): WsFrame {
        if (payload.size < 2) return WsFrame.Close(1005, "")
        val code = ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
        val reason = if (payload.size > 2) payload.copyOfRange(2, payload.size).toString(Charsets.UTF_8) else ""
        return WsFrame.Close(code, reason)
    }

    private fun encodeFrame(opcode: Int, payload: ByteArray, masked: Boolean, mask: ByteArray = ByteArray(4)): ByteArray {
        if (payload.size > MAX_BYTES) error("frame exceeds $MAX_BYTES bytes")
        val output = ByteArrayOutputStream()
        output.write(0x80 or opcode)
        val lengthByte = if (payload.size < 126) payload.size else 126
        output.write(if (masked) lengthByte or 0x80 else lengthByte)
        if (payload.size >= 126) {
            output.write((payload.size shr 8) and 0xff)
            output.write(payload.size and 0xff)
        }
        val body = payload.copyOf()
        if (masked) {
            output.write(mask)
            for (index in body.indices) {
                body[index] = (body[index].toInt() xor (mask[index % 4].toInt() and 0xff)).toByte()
            }
        }
        output.write(body)
        return output.toByteArray()
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count < 0) return false
            offset += count
        }
        return true
    }
}
