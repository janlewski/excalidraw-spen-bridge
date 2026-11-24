package dev.excalidraw.spenbridge

import android.content.Intent
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
