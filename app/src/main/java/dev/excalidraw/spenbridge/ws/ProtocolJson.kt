package dev.excalidraw.spenbridge.ws

import dev.excalidraw.spenbridge.jsonString
import dev.excalidraw.spenbridge.pen.PenSnapshot
import java.util.Locale

internal sealed interface ClientFrame {
    data class Auth(
        val proto: Int,
        val client: String,
        val clientVersion: String,
        val clientNonce: String,
        val proof: String,
    ) : ClientFrame

    data class Pong(val sent: Long) : ClientFrame
    data object Unknown : ClientFrame
    data object Malformed : ClientFrame
}

internal object ProtocolJson {
    const val PROTO = 1
    const val APP = "excalidraw-spen-bridge"

    fun hello(appVersion: String, nonce: String): String =
        """{"type":"hello","proto":1,"app":"$APP","appVersion":${jsonString(appVersion)},"nonce":${jsonString(nonce)},"caps":["button","command"]}"""

    fun ready(session: String, serverProof: String, heartbeatMs: Int, snap: PenSnapshot): String {
        val source = buildString {
            append("{\"state\":${jsonString(snap.sourceState)}")
            snap.sourceReason?.let { append(",\"reason\":${jsonString(it)}") }
            snap.sourceNode?.let { append(",\"node\":${jsonString(it)}") }
            snap.sourceName?.let { append(",\"name\":${jsonString(it)}") }
            append("}")
        }
        val button =
            """{"button":"primary","down":${snap.down},"seq":${snap.seq},"t":${jsonNumber(snap.tMs)}}"""
        return """{"type":"ready","session":${jsonString(session)},"serverProof":${jsonString(serverProof)},"heartbeatMs":$heartbeatMs,"source":$source,"button":$button}"""
    }

    fun button(down: Boolean, seq: Int, tMs: Double, sent: Long): String =
        """{"type":"button","button":"primary","down":$down,"seq":$seq,"t":${jsonNumber(tMs)},"sent":$sent}"""

    fun ping(seq: Int, down: Boolean, sent: Long): String =
        """{"type":"ping","seq":$seq,"down":$down,"sent":$sent}"""

    fun source(state: String, reason: String?, node: String?, name: String?): String = buildString {
        append("{\"type\":\"source\",\"state\":${jsonString(state)}")
        reason?.let { append(",\"reason\":${jsonString(it)}") }
        node?.let { append(",\"node\":${jsonString(it)}") }
        name?.let { append(",\"name\":${jsonString(it)}") }
        append("}")
    }

    fun command(action: String, seq: Int, sent: Long): String =
        """{"type":"command","action":${jsonString(action)},"seq":$seq,"sent":$sent}"""

    fun parseClient(text: String): ClientFrame {
        val json = Json.parse(text) as? Json.Obj ?: return ClientFrame.Malformed
        val type = json.string("type") ?: return ClientFrame.Malformed
        return when (type) {
            "auth" -> {
                val proto = json.int("proto") ?: return ClientFrame.Malformed
                val client = json.string("client") ?: return ClientFrame.Malformed
                val clientVersion = json.string("clientVersion") ?: return ClientFrame.Malformed
                val clientNonce = json.string("clientNonce") ?: return ClientFrame.Malformed
                val proof = json.string("proof") ?: return ClientFrame.Malformed
                ClientFrame.Auth(proto, client, clientVersion, clientNonce, proof)
            }
            "pong" -> {
                val sent = json.long("sent") ?: return ClientFrame.Malformed
                ClientFrame.Pong(sent)
            }
            else -> ClientFrame.Unknown
        }
    }

    fun jsonNumber(value: Double): String {
        if (!value.isFinite()) return "0"
        val asLong = value.toLong()
        if (value == asLong.toDouble()) return asLong.toString()
        return String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')
    }
}

internal sealed interface Json {
    data class Obj(val fields: Map<String, Json>) : Json {
        fun string(key: String): String? = (fields[key] as? Json.Str)?.value
        fun int(key: String): Int? {
            val number = (fields[key] as? Json.Num)?.value ?: return null
            if (number % 1.0 != 0.0) return null
            return number.toInt()
        }
        fun long(key: String): Long? {
            val number = fields[key] as? Json.Num ?: return null
            return number.value.toLong()
        }
    }

    data class Arr(val items: List<Json>) : Json
    data class Str(val value: String) : Json
    data class Num(val value: Double) : Json
    data class Bool(val value: Boolean) : Json
    data object Null : Json

    companion object {
        fun parse(text: String): Json? = JsonParser(text).parse()
    }
}

internal class JsonParser(private val text: String) {
    private var index = 0

    fun parse(): Json? = try {
        val value = parseValue()
        skip()
        if (index != text.length) null else value
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun parseValue(): Json {
        skip()
        if (index >= text.length) error("end")
        return when (val char = text[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> Json.Str(parseString())
            't' -> literal("true", Json.Bool(true))
            'f' -> literal("false", Json.Bool(false))
            'n' -> literal("null", Json.Null)
            else -> if (char == '-' || char.isDigit()) parseNumber() else error("value")
        }
    }

    private fun parseObject(): Json.Obj {
        expect('{')
        val fields = linkedMapOf<String, Json>()
        skip()
        if (peek('}')) {
            index += 1
            return Json.Obj(fields)
        }
        while (true) {
            skip()
            val key = parseString()
            skip()
            expect(':')
            fields[key] = parseValue()
            skip()
            when (text.getOrNull(index)) {
                ',' -> index += 1
                '}' -> {
                    index += 1
                    return Json.Obj(fields)
                }
                else -> error("object")
            }
        }
    }

    private fun parseArray(): Json.Arr {
        expect('[')
        val items = mutableListOf<Json>()
        skip()
        if (peek(']')) {
            index += 1
            return Json.Arr(items)
        }
        while (true) {
            items += parseValue()
            skip()
            when (text.getOrNull(index)) {
                ',' -> index += 1
                ']' -> {
                    index += 1
                    return Json.Arr(items)
                }
                else -> error("array")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val out = StringBuilder()
        while (index < text.length) {
            val char = text[index]
            index += 1
            when (char) {
                '"' -> return out.toString()
                '\\' -> {
                    val escaped = text.getOrNull(index) ?: error("escape")
                    index += 1
                    when (escaped) {
                        '"', '\\', '/' -> out.append(escaped)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            val hex = text.substring(index, (index + 4).coerceAtMost(text.length))
                            if (hex.length < 4) error("unicode")
                            out.append(hex.toInt(16).toChar())
                            index += 4
                        }
                        else -> error("escape")
                    }
                }
                else -> out.append(char)
            }
        }
        error("string")
    }

    private fun parseNumber(): Json.Num {
        val start = index
        if (peek('-')) index += 1
        while (index < text.length && text[index].isDigit()) index += 1
        if (peek('.')) {
            index += 1
            while (index < text.length && text[index].isDigit()) index += 1
        }
        if (index < text.length && (text[index] == 'e' || text[index] == 'E')) error("exponent")
        return Json.Num(text.substring(start, index).toDouble())
    }

    private fun literal(token: String, value: Json): Json {
        if (!text.startsWith(token, index)) error(token)
        index += token.length
        return value
    }

    private fun expect(char: Char) {
        if (!peek(char)) error(char.toString())
        index += 1
    }

    private fun peek(char: Char): Boolean = index < text.length && text[index] == char

    private fun skip() {
        while (index < text.length && text[index].isWhitespace()) index += 1
    }
}
