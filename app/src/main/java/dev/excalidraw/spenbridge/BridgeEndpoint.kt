package dev.excalidraw.spenbridge

import java.net.URLDecoder
import java.security.MessageDigest

/** Loopback channel the Obsidian plugin can hold open while Obsidian is in front. */
object BridgeEndpoint {
    const val HOST = "127.0.0.1"
    const val PORT = 47631
    const val EVENTS_PATH = "/events"
    const val HEALTH_PATH = "/health"
    const val PEN_PATH = "/v1/pen"
    val ALLOWED_ORIGINS: Set<String> = setOf(
        "http://localhost",
        "capacitor://localhost",
        "app://obsidian.md",
    )

    fun eventsUrl(token: String): String = "http://$HOST:$PORT$EVENTS_PATH?token=$token"

    fun originAllowed(origin: String?): Boolean = origin == null || origin in ALLOWED_ORIGINS
}

internal fun tokensMatch(expected: String, presented: String?): Boolean {
    if (expected.isEmpty() || presented == null) return false
    return MessageDigest.isEqual(
        expected.toByteArray(Charsets.UTF_8),
        presented.toByteArray(Charsets.UTF_8),
    )
}

internal fun requestPath(target: String): String = target.substringBefore('?')

internal fun queryToken(target: String): String? {
    val query = target.substringAfter('?', "")
    if (query.isEmpty()) return null
    return query.split('&').firstNotNullOfOrNull { part ->
        val pieces = part.split('=', limit = 2)
        if (pieces.size == 2 && pieces[0] == "token") {
            URLDecoder.decode(pieces[1], "UTF-8")
        } else {
            null
        }
    }
}

internal fun sseData(json: String): String = "data: ${json.replace("\n", "")}\n\n"
