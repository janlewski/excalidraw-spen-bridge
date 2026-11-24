package dev.excalidraw.spenbridge

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Accepts plugin connections on localhost only and pushes one server-sent event per command. */
internal class LoopbackHub(
    private val port: Int,
    private val token: () -> String,
) {
    private val server = ServerSocket()
    private val clients = LinkedHashSet<Socket>()
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
        }
    }

    fun publish(json: String) {
        writeToClients(sseData(json))
    }

    fun clientCount(): Int = synchronized(clients) { clients.size }

    fun stop() {
        running = false
        ping?.shutdownNow()
        ping = null
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
            val header = readHeader(socket)
            val requestLine = header.lineSequence().firstOrNull().orEmpty()
            val target = requestLine.split(' ').getOrNull(1).orEmpty()
            when (requestPath(target)) {
                BridgeEndpoint.HEALTH_PATH -> {
                    writeHttp(socket, 200, "application/json", """{"ok":true}""")
                    socket.close()
                }
                BridgeEndpoint.EVENTS_PATH -> {
                    if (!tokensMatch(token(), queryToken(target))) {
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
        val reason = if (status == 200) "OK" else if (status == 401) "Unauthorized" else "Not Found"
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
