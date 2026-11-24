package dev.excalidraw.spenbridge

import android.content.Intent
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket

class BridgeEventTest {
    @Test fun canonicalPayloadIsStableAndSignable() {
        val event = BridgeEvent("device", 42, 1730000000000, BridgeAction.OPEN_MENU)
        val payload = "{\"action\":\"open-menu\",\"deviceId\":\"device\",\"issuedAtMs\":1730000000000,\"sequence\":42,\"version\":1}"
        assertEquals(payload, event.canonicalPayload())
        assertEquals(
            "TbL_EvrNehm94SHcm6MQ1ecEIiDyhnNimy1HhJmKdV4",
            BridgeEvent.hmacSha256Base64Url(event.canonicalPayload(), "secret".toByteArray()),
        )
        assertEquals(
            payload.removeSuffix("}") + ",\"signature\":\"TbL_EvrNehm94SHcm6MQ1ecEIiDyhnNimy1HhJmKdV4\"}",
            event.signedJson("secret".toByteArray()),
        )
    }

    @Test fun jsonStringEscapesQuotesSlashesAndControls() {
        assertEquals("\"a\\\"b\\\\c\"", jsonString("a\"b\\c"))
        assertEquals("\"line\\u000a\"", jsonString("line\n"))
    }

    @Test fun mailboxPathMatchesThePluginFolder() {
        assertEquals(
            ".obsidian/plugins/excalidraw-stylus-controls/spen-bridge/pending.json",
            BridgePaths.pendingDisplayPath(),
        )
    }

    @Test fun stylusPrimaryStaysDistinctFromPrimary() {
        assertEquals(32, MotionEvent.BUTTON_STYLUS_PRIMARY)
        assertEquals(1, MotionEvent.BUTTON_PRIMARY)
        assertEquals("none", describeButtonState(0))
        assertEquals("STYLUS_PRIMARY(0x20)", describeButtonState(MotionEvent.BUTTON_STYLUS_PRIMARY))
        assertEquals(
            "PRIMARY+STYLUS_PRIMARY(0x21)",
            describeButtonState(MotionEvent.BUTTON_PRIMARY or MotionEvent.BUTTON_STYLUS_PRIMARY),
        )
    }

    @Test fun rebootOpensOnlyAfterUnlockWhenOptedIn() {
        assertFalse(BootStart.shouldOpen(false, Intent.ACTION_BOOT_COMPLETED))
        assertFalse(BootStart.shouldOpen(true, Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertFalse(BootStart.shouldOpen(true, null))
        assertTrue(BootStart.shouldOpen(true, Intent.ACTION_BOOT_COMPLETED))
    }

    @Test fun localhostRejectsABadTokenAndStreamsAGoodOne() {
        assertFalse(tokensMatch("secret", "nope"))
        assertTrue(tokensMatch("secret", "secret"))
        val secretBytes = "secret".toByteArray()
        val token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes)
        val hub = LoopbackHub(0, { secretBytes })
        hub.start()
        try {
            Socket(BridgeEndpoint.HOST, hub.boundPort).use { denied ->
                denied.soTimeout = 2000
                denied.getOutputStream().write("GET /events?token=nope HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                val status = denied.getInputStream().bufferedReader().readLine()
                assertTrue(status.startsWith("HTTP/1.1 401"))
            }
            Socket(BridgeEndpoint.HOST, hub.boundPort).use { allowed ->
                allowed.soTimeout = 2000
                allowed.getOutputStream().write("GET /events?token=$token HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                val deadline = System.currentTimeMillis() + 2000
                while (hub.clientCount() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
                assertEquals(1, hub.clientCount())
                hub.publish("{\"action\":\"open-menu\"}")
                val buffer = StringBuilder()
                val body = ByteArray(512)
                while (!buffer.contains("open-menu")) {
                    val count = allowed.getInputStream().read(body)
                    if (count < 0) break
                    buffer.append(String(body, 0, count, Charsets.UTF_8))
                }
                assertTrue(buffer.toString().contains("data: {\"action\":\"open-menu\"}"))
            }
        } finally {
            hub.stop()
        }
    }

    @Test fun motionSummaryOmitsCoordinates() {
        val summary = describeMotion(
            MotionEvent.ACTION_BUTTON_PRESS,
            MotionEvent.TOOL_TYPE_STYLUS,
            MotionEvent.BUTTON_STYLUS_PRIMARY,
            0f,
        )
        assertEquals("BUTTON_PRESS STYLUS buttons=STYLUS_PRIMARY(0x20) pressure=0.00", summary)
    }
}
