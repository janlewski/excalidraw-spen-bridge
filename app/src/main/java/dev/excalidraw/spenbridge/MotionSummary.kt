package dev.excalidraw.spenbridge

import android.view.MotionEvent
import java.util.Locale

/** Names the native button bits WebView does not preserve. Coordinates are intentionally omitted. */
internal fun describeMotion(action: Int, toolType: Int, buttonState: Int, pressure: Float): String {
    val actionName = when (action and MotionEvent.ACTION_MASK) {
        MotionEvent.ACTION_DOWN -> "DOWN"
        MotionEvent.ACTION_UP -> "UP"
        MotionEvent.ACTION_MOVE -> "MOVE"
        MotionEvent.ACTION_CANCEL -> "CANCEL"
        MotionEvent.ACTION_HOVER_ENTER -> "HOVER_ENTER"
        MotionEvent.ACTION_HOVER_MOVE -> "HOVER_MOVE"
        MotionEvent.ACTION_HOVER_EXIT -> "HOVER_EXIT"
        MotionEvent.ACTION_BUTTON_PRESS -> "BUTTON_PRESS"
        MotionEvent.ACTION_BUTTON_RELEASE -> "BUTTON_RELEASE"
        else -> "ACTION_${action and MotionEvent.ACTION_MASK}"
    }
    val tool = when (toolType) {
        MotionEvent.TOOL_TYPE_STYLUS -> "STYLUS"
        MotionEvent.TOOL_TYPE_ERASER -> "ERASER"
        MotionEvent.TOOL_TYPE_FINGER -> "FINGER"
        MotionEvent.TOOL_TYPE_MOUSE -> "MOUSE"
        else -> "TOOL_$toolType"
    }
    val pressureText = String.format(Locale.US, "%.2f", pressure)
    return "$actionName $tool buttons=${describeButtonState(buttonState)} pressure=$pressureText"
}

internal fun describeButtonState(buttonState: Int): String {
    if (buttonState == 0) return "none"
    val names = buildList {
        if (buttonState and MotionEvent.BUTTON_PRIMARY != 0) add("PRIMARY")
        if (buttonState and MotionEvent.BUTTON_SECONDARY != 0) add("SECONDARY")
        if (buttonState and MotionEvent.BUTTON_TERTIARY != 0) add("TERTIARY")
        if (buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0) add("STYLUS_PRIMARY")
        if (buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY != 0) add("STYLUS_SECONDARY")
    }
    val known = if (names.isEmpty()) "other" else names.joinToString("+")
    return "$known(0x${buttonState.toString(16)})"
}
