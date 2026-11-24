package dev.excalidraw.spenbridge.ws

import dev.excalidraw.spenbridge.BridgeEndpoint
import java.net.Socket

internal data class LoopbackResult(
    val httpStatus: Int,
    val hello: String? = null,
    val ready: String? = null,
    val closeCode: Int? = null,
    val serverProofOk: Boolean = false,
)

/** Small WebSocket client used by the self-test and JVM tests. */
internal object PenLoopback {
    fun handshake(
        port: Int,
        secret: ByteArray?,
        origin: String? = "http://localhost",
        timeoutMs: Int = 2_000,
        sendAuth: Boolean = secret != null,
    ): LoopbackResult {
        Socket(BridgeEndpoint.HOST, port).use { socket ->
            socket.soTimeout = timeoutMs
            socket.tcpNoDelay = true
            val key = "dGhlIHNhbXBsZSBub25jZQ=="
            val originHeader = if (origin == null) "" else "Origin: $origin\r\n"
            val request = "GET ${BridgeEndpoint.PEN_PATH} HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n${originHeader}\r\n"
            socket.getOutputStream().write(request.toByteArray())
            socket.getOutputStream().flush()
            val status = readHeaders(socket)
            val code = status.lineSequence().firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: 0
            if (code != 101) return LoopbackResult(httpStatus = code)
            val helloFrame = WsFrames.readFrame(socket.getInputStream(), requireMasked = false)
            val hello = (helloFrame as? WsFrame.Text)?.text
            if (!sendAuth || secret == null || hello == null) {
                val closed = WsFrames.readFrame(socket.getInputStream(), requireMasked = false)
                return LoopbackResult(code, hello, closeCode = (closed as? WsFrame.Close)?.code)
            }
            val parsed = Json.parse(hello) as? Json.Obj
            val nonce = parsed?.string("nonce") ?: return LoopbackResult(code, hello)
            val clientNonce = "AQEBAQEBAQEBAQEBAQEBAQ"
            val proof = PenAuth.proof(secret, nonce, clientNonce, "client")
            val auth = """{"type":"auth","proto":1,"client":"excalidraw-stylus-controls","clientVersion":"0.2.0","clientNonce":"$clientNonce","proof":"$proof"}"""
            socket.getOutputStream().write(WsFrames.encodeMaskedText(auth))
            socket.getOutputStream().flush()
            val next = WsFrames.readFrame(socket.getInputStream(), requireMasked = false)
            return when (next) {
                is WsFrame.Text -> {
                    val ready = Json.parse(next.text) as? Json.Obj
                    val serverProof = ready?.string("serverProof")
                    val expected = PenAuth.proof(secret, nonce, clientNonce, "server")
                    LoopbackResult(code, hello, next.text, serverProofOk = serverProof == expected)
                }
                is WsFrame.Close -> LoopbackResult(code, hello, closeCode = next.code)
                else -> LoopbackResult(code, hello)
            }
        }
    }

    private fun readHeaders(socket: Socket): String {
        val input = socket.getInputStream()
        val buffer = StringBuilder()
        while (!buffer.contains("\r\n\r\n") && buffer.length < 8_192) {
            val byte = input.read()
            if (byte < 0) break
            buffer.append(byte.toChar())
        }
        return buffer.toString()
    }
}
