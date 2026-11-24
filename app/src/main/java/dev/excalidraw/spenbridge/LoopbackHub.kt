package dev.excalidraw.spenbridge

import dev.excalidraw.spenbridge.diag.DiagLog
import dev.excalidraw.spenbridge.pen.PenSnapshot
import dev.excalidraw.spenbridge.ws.ClientFrame
import dev.excalidraw.spenbridge.ws.PenAuth
import dev.excalidraw.spenbridge.ws.ProtocolJson
import dev.excalidraw.spenbridge.ws.WsFrame
import dev.excalidraw.spenbridge.ws.WsFrames
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

internal data class PenClientInfo(
    val origin: String,
    val authenticated: Boolean,
    val pongAgeMs: Long,
)

/** Localhost HTTP plus the authenticated /v1/pen WebSocket. */
internal class LoopbackHub(
    private val port: Int,
    private val secret: () -> ByteArray?,
    private val pen: () -> PenSnapshot = { PenSnapshot() },
    private val appVersion: String = "0.2.0-diag",
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val authTimeoutMs: Int = 3_000,
    private val onRejection: (Int, String) -> Unit = { _, _ -> },
) {
    private val server = ServerSocket()
    private val clients = LinkedHashSet<Socket>()
    private val penClients = LinkedHashSet<PenClient>()
    private var acceptThread: Thread? = null
    private var ping: ScheduledExecutorService? = null
    @Volatile private var running = false
    var boundPort: Int = port
        private set

    fun start() {
        server.reuseAddress = true
        server.bind(InetSocketAddress(BridgeEndpoint.HOST, port))
        boundPort = server.localPort
        running = true
        acceptThread = Thread({
            while (running) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                Thread { handle(socket) }.apply { isDaemon = true; start() }
            }
        }, "spen-bridge-accept").apply { isDaemon = true; start() }
        ping = Executors.newSingleThreadScheduledExecutor().also { scheduler ->
            scheduler.scheduleAtFixedRate({ writeToClients(": ping\n\n") }, 20, 20, TimeUnit.SECONDS)
            scheduler.scheduleAtFixedRate({ heartbeat() }, 1, 1, TimeUnit.SECONDS)
        }
    }

    fun publish(json: String) {
        writeToClients(sseData(json))
    }

    fun broadcastPen(json: String) {
        for (client in penSnapshot()) {
            try {
                client.send(json)
            } catch (_: IOException) {
                drop(client)
            }
        }
    }

    fun closePenClients(code: Int, reason: String) {
        for (client in penSnapshot()) {
            client.close(code, reason)
            drop(client)
        }
    }

    fun clientCount(): Int = synchronized(clients) { clients.size }
    fun penClientCount(): Int = synchronized(penClients) { penClients.size }

    fun penClientInfos(): List<PenClientInfo> {
        val now = clock()
        return penSnapshot().map { client ->
            PenClientInfo(client.origin, client.authenticated, now - client.lastPongAt)
        }
    }

    fun stop() {
        running = false
        ping?.shutdownNow()
        ping = null
        closePenClients(1001, "going away")
        try {
            server.close()
        } catch (_: IOException) {
        }
        synchronized(clients) {
            clients.forEach { socket ->
                try {
                    socket.close()
                } catch (_: IOException) {
                }
            }
            clients.clear()
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            val header = readHeader(socket)
            val requestLine = header.lineSequence().firstOrNull().orEmpty()
            val target = requestLine.split(' ').getOrNull(1).orEmpty()
            val headers = parseHeaders(header)
            when (requestPath(target)) {
                BridgeEndpoint.HEALTH_PATH -> {
                    writeHttp(socket, 200, "application/json", """{"ok":true}""")
                    socket.close()
                }
                BridgeEndpoint.EVENTS_PATH -> {
                    if (!tokensMatch(sseToken(), queryToken(target))) {
                        writeHttp(socket, 401, "text/plain", "unauthorized")
                        socket.close()
                        return
                    }
                    socket.soTimeout = 0
                    synchronized(clients) {
                        writeRaw(
                            socket,
                            "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: keep-alive\r\n\r\n",
                        )
                        clients.add(socket)
                    }
                }
                BridgeEndpoint.PEN_PATH -> handlePen(socket, headers)
                else -> {
                    writeHttp(socket, 404, "text/plain", "not found")
                    socket.close()
                }
            }
        } catch (_: IOException) {
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun handlePen(socket: Socket, headers: Map<String, String>) {
        val upgrade = headers["upgrade"]?.lowercase()
        val version = headers["sec-websocket-version"]
        val key = headers["sec-websocket-key"]
        if (upgrade != "websocket" || version != "13" || key.isNullOrBlank()) {
            writeHttp(socket, 400, "text/plain", "websocket required")
            socket.close()
            return
        }
        if (!BridgeEndpoint.originAllowed(headers["origin"])) {
            reject(4403, "origin")
            writeHttp(socket, 403, "text/plain", "origin rejected")
            socket.close()
            return
        }
        writeRaw(
            socket,
            "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${WsFrames.acceptKey(key)}\r\n\r\n",
        )
        val client = PenClient(socket, headers["origin"].orEmpty())
        try {
            val nonce = PenAuth.newNonce()
            client.send(ProtocolJson.hello(appVersion, nonce))
            socket.soTimeout = authTimeoutMs
            when (val frame = WsFrames.readFrame(socket.getInputStream(), requireMasked = true)) {
                null -> {
                    client.close(4408, "auth timeout")
                    reject(4408, "auth timeout")
                }
                is WsFrame.ProtocolError -> client.close(frame.code, frame.reason)
                is WsFrame.Text -> when (val parsed = ProtocolJson.parseClient(frame.text)) {
                    is ClientFrame.Auth -> authenticate(client, nonce, parsed)
                    is ClientFrame.Malformed -> {
                        client.close(4400, "malformed")
                        reject(4400, "malformed")
                    }
                    else -> {
                        client.close(4400, "expected auth")
                        reject(4400, "expected auth")
                    }
                }
                else -> {
                    client.close(4400, "expected auth")
                    reject(4400, "expected auth")
                }
            }
        } catch (_: SocketTimeoutException) {
            client.close(4408, "auth timeout")
            reject(4408, "auth timeout")
        } catch (_: IOException) {
            client.closeQuiet()
        }
    }

    private fun authenticate(client: PenClient, nonce: String, auth: ClientFrame.Auth) {
        if (auth.proto != ProtocolJson.PROTO) {
            client.close(4400, "proto")
            reject(4400, "proto")
            return
        }
        val secretBytes = secret()
        val expected = secretBytes?.let { PenAuth.proof(it, nonce, auth.clientNonce, "client") }
        if (secretBytes == null || expected == null || !MessageDigest.isEqual(expected.toByteArray(), auth.proof.toByteArray())) {
            client.close(4401, "auth failed")
            reject(4401, "auth failed")
            DiagLog.log("W", "ws", "pen auth failed")
            return
        }
        synchronized(penClients) {
            if (penClients.size >= 4) {
                client.close(1013, "busy")
                return
            }
            val serverProof = PenAuth.proof(secretBytes, nonce, auth.clientNonce, "server")
            client.send(ProtocolJson.ready(PenAuth.newNonce(8), serverProof, 1_000, pen()))
            client.authenticated = true
            client.lastPongAt = clock()
            penClients.add(client)
        }
        client.socket.soTimeout = 0
        DiagLog.log("I", "ws", "pen client ready origin=${client.origin.ifBlank { "none" }}")
        readLoop(client)
    }

    private fun readLoop(client: PenClient) {
        try {
            while (running) {
                when (val frame = WsFrames.readFrame(client.socket.getInputStream(), requireMasked = true)) {
                    null -> break
                    is WsFrame.Text -> when (val parsed = ProtocolJson.parseClient(frame.text)) {
                        is ClientFrame.Pong -> client.lastPongAt = clock()
                        is ClientFrame.Unknown -> Unit
                        is ClientFrame.Malformed -> {
                            client.close(4400, "malformed")
                            reject(4400, "malformed")
                            return
                        }
                        is ClientFrame.Auth -> Unit
                    }
                    is WsFrame.Ping -> client.sendRaw(WsFrames.encodePong(frame.payload))
                    is WsFrame.Pong -> client.lastPongAt = clock()
                    is WsFrame.Close -> break
                    is WsFrame.ProtocolError -> {
                        client.close(frame.code, frame.reason)
                        return
                    }
                }
            }
        } catch (_: IOException) {
        } finally {
            drop(client)
            client.closeQuiet()
        }
    }

    private fun heartbeat() {
        val now = clock()
        val snapshot = pen()
        val pingFrame = ProtocolJson.ping(snapshot.seq, snapshot.down, now)
        for (client in penSnapshot()) {
            if (!client.authenticated) continue
            if (now - client.lastPongAt > 5_000) {
                client.close(1001, "heartbeat")
                drop(client)
                continue
            }
            try {
                client.send(pingFrame)
            } catch (_: IOException) {
                drop(client)
            }
        }
    }

    private fun sseToken(): String {
        val value = secret() ?: return ""
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    }

    private fun reject(code: Int, reason: String) {
        onRejection(code, reason)
        DiagLog.log("W", "ws", "rejected $code $reason")
    }

    private fun penSnapshot(): List<PenClient> = synchronized(penClients) { penClients.toList() }

    private fun drop(client: PenClient) {
        synchronized(penClients) { penClients.remove(client) }
        try {
            client.socket.close()
        } catch (_: IOException) {
        }
    }

    private fun writeToClients(frame: String) {
        val snapshot = synchronized(clients) { clients.toList() }
        for (socket in snapshot) {
            try {
                writeRaw(socket, frame)
            } catch (_: IOException) {
                synchronized(clients) { clients.remove(socket) }
                try {
                    socket.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    private fun readHeader(socket: Socket): String {
        socket.soTimeout = 3_000
        val input = socket.getInputStream()
        val buffer = ByteArrayOutputStream()
        val one = ByteArray(1)
        while (buffer.size() < 8_192) {
            if (input.read(one) < 0) break
            buffer.write(one)
            if (buffer.toString(Charsets.UTF_8).contains("\r\n\r\n")) break
        }
        return buffer.toString(Charsets.UTF_8)
    }

    private fun writeHttp(socket: Socket, status: Int, type: String, body: String) {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            else -> "Not Found"
        }
        val payload = body.toByteArray(Charsets.UTF_8)
        writeRaw(
            socket,
            "HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n$body",
        )
    }

    private fun writeRaw(socket: Socket, text: String) {
        val output = socket.getOutputStream()
        output.write(text.toByteArray(Charsets.UTF_8))
        output.flush()
    }
}

internal class PenClient(val socket: Socket, val origin: String) {
    val lock = Any()
    private val output: OutputStream = socket.getOutputStream()
    @Volatile var authenticated = false
    @Volatile var lastPongAt = 0L

    fun send(json: String) {
        sendRaw(WsFrames.encodeText(json))
    }

    fun sendRaw(bytes: ByteArray) {
        synchronized(lock) {
            output.write(bytes)
            output.flush()
        }
    }

    fun close(code: Int, reason: String) {
        try {
            sendRaw(WsFrames.encodeClose(code, reason))
        } catch (_: IOException) {
        }
        closeQuiet()
    }

    fun closeQuiet() {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }
}

internal fun parseHeaders(raw: String): Map<String, String> {
    val headers = linkedMapOf<String, String>()
    for (line in raw.split("\r\n").drop(1)) {
        if (line.isEmpty()) break
        val split = line.indexOf(':')
        if (split <= 0) continue
        headers[line.substring(0, split).trim().lowercase()] = line.substring(split + 1).trim()
    }
    return headers
}
