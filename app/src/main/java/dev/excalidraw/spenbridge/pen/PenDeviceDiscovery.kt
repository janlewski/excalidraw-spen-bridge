package dev.excalidraw.spenbridge.pen

internal data class InputDeviceInfo(
    val index: Int,
    val node: String,
    val name: String,
    val keys: Set<String>,
    val abs: Set<String>,
)

internal object PenDeviceDiscovery {
    private val namePreference = Regex("(e-?pen|wacom|stylus|pen)", RegexOption.IGNORE_CASE)
    private val deviceHeader = Regex("""add device (\d+):\s+(\S+)""")
    private val nameLine = Regex("""name:\s+"(.*)"""")
    private val labels = setOf(
        "BTN_STYLUS",
        "BTN_STYLUS2",
        "BTN_STYLUS3",
        "BTN_TOOL_PEN",
        "BTN_TOUCH",
        "ABS_X",
        "ABS_Y",
        "ABS_PRESSURE",
        "ABS_MT_POSITION_X",
        "ABS_MT_POSITION_Y",
        "ABS_MT_PRESSURE",
    )

    fun parseGeteventLp(text: String): List<InputDeviceInfo> {
        val devices = mutableListOf<MutableDevice>()
        var current: MutableDevice? = null
        var section = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            val header = deviceHeader.find(line)
            if (header != null) {
                current = MutableDevice(header.groupValues[1].toInt(), header.groupValues[2])
                devices += current
                section = ""
                continue
            }
            val device = current ?: continue
            val named = nameLine.find(line)
            if (named != null) {
                device.name = named.groupValues[1]
                continue
            }
            if (line.startsWith("KEY")) section = "KEY"
            else if (line.startsWith("ABS")) section = "ABS"
            for (label in labels) {
                if (!line.contains(label)) continue
                if (section == "ABS" || label.startsWith("ABS_")) device.abs += label
                else device.keys += label
            }
        }
        return devices.map { InputDeviceInfo(it.index, it.node, it.name, it.keys, it.abs) }
    }

    fun pickPenNode(devices: List<InputDeviceInfo>, override: String?): InputDeviceInfo? {
        val chosen = override?.takeIf { it.isNotBlank() }
        if (chosen != null) {
            return devices.firstOrNull { it.node == chosen || it.name == chosen || it.node.endsWith(chosen) }
        }
        val stylus = devices.filter { "BTN_STYLUS" in it.keys }
        val pens = stylus.filter { "BTN_TOOL_PEN" in it.keys }
        val pool = pens.ifEmpty { stylus }
        return pool.firstOrNull { namePreference.containsMatchIn(it.name) } ?: pool.firstOrNull()
    }

    private class MutableDevice(val index: Int, val node: String) {
        var name: String = ""
        val keys = mutableSetOf<String>()
        val abs = mutableSetOf<String>()
    }
}
