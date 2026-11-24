package dev.excalidraw.spenbridge

import dev.excalidraw.spenbridge.diag.DiagLog
import dev.excalidraw.spenbridge.diag.DiagnosticsReport
import dev.excalidraw.spenbridge.diag.ReportInput
import dev.excalidraw.spenbridge.pen.ButtonUpdate
import dev.excalidraw.spenbridge.pen.EvdevAssembler
import dev.excalidraw.spenbridge.pen.PenDeviceDiscovery
import dev.excalidraw.spenbridge.pen.PenState
import dev.excalidraw.spenbridge.pen.parseEvdev
import dev.excalidraw.spenbridge.ws.ClientFrame
import dev.excalidraw.spenbridge.ws.PenAuth
import dev.excalidraw.spenbridge.ws.PenLoopback
import dev.excalidraw.spenbridge.ws.ProtocolJson
import dev.excalidraw.spenbridge.ws.WsFrame
import dev.excalidraw.spenbridge.ws.WsFrames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PenProtocolTest {
    private val secret = ByteArray(32) { it.toByte() }

    @Test fun sharedAuthVectors() {
        assertEquals("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", PenAuth.pairingCode(secret))
        val nonce = "AAAAAAAAAAAAAAAAAAAAAA"
        val clientNonce = "AQEBAQEBAQEBAQEBAQEBAQ"
        assertEquals(
            "mL30Scw9qhI2CwHAUh6P1JmgTJEUSN5xtvhBEB20-oE",
            PenAuth.proof(secret, nonce, clientNonce, "client"),
        )
        assertEquals(
            "d1mX914nyIaHxRXrKF0yk1AnUp7-zeU3FRBWUqnil-U",
            PenAuth.proof(secret, nonce, clientNonce, "server"),
        )
        assertEquals("MMG4-2KLG", PenAuth.fingerprint(secret))
    }

    @Test fun websocketAcceptKeyMatchesRfc6455() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WsFrames.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test fun maskedTextRoundTripAndRejectsUnmaskedClientFrames() {
        val encoded = WsFrames.encodeMaskedText("{\"type\":\"pong\",\"sent\":7}")
        val frame = WsFrames.readFrame(ByteArrayInputStream(encoded), requireMasked = true)
        assertEquals("{\"type\":\"pong\",\"sent\":7}", (frame as WsFrame.Text).text)
        val unmasked = WsFrames.encodeText("nope")
        val rejected = WsFrames.readFrame(ByteArrayInputStream(unmasked), requireMasked = true)
        assertEquals(1002, (rejected as WsFrame.ProtocolError).code)
        val wide = WsFrames.encodeText("x".repeat(200))
        assertEquals(126, wide[1].toInt() and 0x7f)
        val close = WsFrames.encodeClose(4401, "auth failed")
        val decoded = WsFrames.readFrame(ByteArrayInputStream(close), requireMasked = false) as WsFrame.Close
        assertEquals(4401, decoded.code)
        assertEquals("auth failed", decoded.reason)
    }

    @Test fun protocolMessagesRejectABadAuth() {
        val hello = ProtocolJson.hello("0.2.0-diag", "AAAAAAAAAAAAAAAAAAAAAA")
        assertTrue(hello.contains("\"proto\":1"))
        assertTrue(hello.contains("\"caps\":[\"button\",\"command\"]"))
        val button = ProtocolJson.button(true, 1, 1764000000000.0, 1764000000001)
        assertEquals(
            """{"type":"button","button":"primary","down":true,"seq":1,"t":1764000000000,"sent":1764000000001}""",
            button,
        )
        assertTrue(ProtocolJson.parseClient("""{"type":"auth","proto":1,"client":"c","clientVersion":"1","clientNonce":"n"}""") is ClientFrame.Malformed)
        val wrongProto = ProtocolJson.parseClient(
            """{"type":"auth","proto":2,"client":"c","clientVersion":"1","clientNonce":"n","proof":"x"}""",
        ) as ClientFrame.Auth
        assertEquals(2, wrongProto.proto)
        assertTrue(ProtocolJson.parseClient("""{"type":"later"}""") is ClientFrame.Unknown)
    }

    @Test fun samsungPenNodeIsChosenOverTheTouchscreen() {
        val text = javaClass.classLoader!!.getResource("getevent-lp-samsung.txt")!!.readText()
        val devices = PenDeviceDiscovery.parseGeteventLp(text)
        val picked = PenDeviceDiscovery.pickPenNode(devices, null)
        assertEquals("/dev/input/event5", picked?.node)
        assertEquals("sec_e-pen", picked?.name)
        assertEquals("/dev/input/event1", PenDeviceDiscovery.pickPenNode(devices, "/dev/input/event1")?.node)
    }

    @Test fun evdevEmitsTheButtonOnlyOnSynReport() {
        val down = record64(1_764_000_000L, 123_000L, EvdevAssembler.EV_KEY, EvdevAssembler.BTN_STYLUS, 1)
        val repeat = record64(1_764_000_000L, 124_000L, EvdevAssembler.EV_KEY, EvdevAssembler.BTN_STYLUS, 2)
        val report = record64(1_764_000_000L, 125_000L, EvdevAssembler.EV_SYN, EvdevAssembler.SYN_REPORT, 0)
        val dropped = record64(1_764_000_000L, 126_000L, EvdevAssembler.EV_SYN, EvdevAssembler.SYN_DROPPED, 0)
        val assembler = EvdevAssembler(EvdevAssembler.BTN_STYLUS)
        val events = parseEvdev(down + repeat + report + dropped, down.size * 3 + dropped.size, is64 = true)
        assertNull(assembler.push(events[0]))
        assertNull(assembler.push(events[1]))
        val level = assembler.push(events[2]) as ButtonUpdate.Level
        assertTrue(level.down)
        assertEquals(1_764_000_000L * 1_000_000L + 123_000L, level.timeUs)
        assertTrue(assembler.push(events[3]) is ButtonUpdate.Dropped)
        val narrow = record32(10, 20, EvdevAssembler.EV_KEY, EvdevAssembler.BTN_STYLUS, 0)
        assertEquals(1, parseEvdev(narrow, narrow.size, is64 = false).size)
    }

    @Test fun penStateIncrementsOnlyOnChangeAndUnavailableForcesUp() {
        val state = PenState()
        assertNull(state.apply(false, 0))
        val down = state.apply(true, 5_000)
        assertEquals(1, down?.seq)
        assertNull(state.apply(true, 6_000))
        val forced = state.source("unavailable", "shizuku-not-running", null, null)
        assertEquals(false, forced?.down)
        assertEquals(2, forced?.seq)
    }

    @Test fun originAllowList() {
        assertTrue(BridgeEndpoint.originAllowed(null))
        assertTrue(BridgeEndpoint.originAllowed("http://localhost"))
        assertTrue(BridgeEndpoint.originAllowed("capacitor://localhost"))
        assertTrue(BridgeEndpoint.originAllowed("app://obsidian.md"))
        assertFalse(BridgeEndpoint.originAllowed("https://evil.example"))
    }

    @Test fun badOriginIs403WrongProofIs4401AndMissingAuthIs4408() {
        val hub = LoopbackHub(0, { secret }, authTimeoutMs = 300)
        hub.start()
        try {
            val forbidden = PenLoopback.handshake(hub.boundPort, secret, origin = "https://evil.example", timeoutMs = 1000)
            assertEquals(403, forbidden.httpStatus)
            val bad = PenLoopback.handshake(hub.boundPort, ByteArray(32) { 9 }, timeoutMs = 1000)
            assertEquals(4401, bad.closeCode)
            val timeout = PenLoopback.handshake(hub.boundPort, secret, timeoutMs = 1000, sendAuth = false)
            assertEquals(4408, timeout.closeCode)
            val ok = PenLoopback.handshake(hub.boundPort, secret, timeoutMs = 1000)
            assertTrue(ok.serverProofOk)
            assertTrue(ok.ready?.contains("\"type\":\"ready\"") == true)
        } finally {
            hub.stop()
        }
    }

    @Test fun reportOmitsSecretsAndDiagLogWraps() {
        assertEquals("6.1.0", DiagnosticsReport.formatOneUi("60100"))
        assertEquals("unknown", DiagnosticsReport.formatOneUi("nope"))
        val secretCode = PenAuth.pairingCode(secret)
        val input = ReportInput(
            versionName = "0.2.0-diag",
            versionCode = 2,
            proto = 1,
            buildType = "debug",
            gitSha = "abc1234",
            manufacturer = "samsung",
            model = "SM-X910",
            device = "gts9wifi",
            androidRelease = "14",
            sdkInt = 34,
            oneUi = "6.1.0",
            securityPatch = "2026-01-01",
            shizukuInstalled = true,
            shizukuVersion = "13.1.5",
            shizukuRunning = true,
            shizukuUid = 2000,
            shizukuPermission = true,
            userServiceBound = true,
            devices = "/dev/input/event5 sec_e-pen BTN_STYLUS=yes",
            readAccess = "ok:1",
            buttonDown = false,
            buttonSeq = 3,
            lastKeyHex = "0x14b",
            latencyMs = 2,
            pressCount = 3,
            learnResult = "code 0x14b on event5 (sec_e-pen)",
            listening = true,
            bindError = null,
            port = 47631,
            penClients = 1,
            clientSummary = "origin=http://localhost auth=true pongAgeMs=10",
            lastRejection = null,
            fingerprint = "MMG4-2KLG",
            ignoringBattery = true,
            serviceRunning = true,
            notifications = true,
            airCommand = "unknown",
            vaultSelected = true,
            log = listOf("button down=true seq=3"),
            pairingCode = secretCode,
            vaultPath = "/storage/emulated/0/Vault",
            serial = "SERIAL123",
        )
        val text = DiagnosticsReport.text(input)
        assertFalse(text.contains(secretCode))
        assertFalse(text.contains("/storage/emulated"))
        assertFalse(text.contains("SERIAL123"))
        assertFalse(text.contains("ABS_X"))
        assertTrue(text.contains("vault selected: yes"))
        assertTrue(text.contains("SM-X910"))
        DiagLog.clear()
        repeat(205) { DiagLog.log("I", "pen", "line $it") }
        assertEquals(200, DiagLog.size())
        assertTrue(DiagLog.snapshot(1).single().endsWith("line 204"))
    }

    private fun record64(sec: Long, usec: Long, type: Int, code: Int, value: Int): ByteArray =
        ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(sec).putLong(usec).putShort(type.toShort()).putShort(code.toShort()).putInt(value)
            .array()

    private fun record32(sec: Int, usec: Int, type: Int, code: Int, value: Int): ByteArray =
        ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(sec).putInt(usec).putShort(type.toShort()).putShort(code.toShort()).putInt(value)
            .array()
}
