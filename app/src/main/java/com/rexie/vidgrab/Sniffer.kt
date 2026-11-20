package com.rexie.vidgrab

object Sniffer {
    val directExts = setOf("mp4", "m4v", "webm", "mkv", "mov", "flv", "avi", "3gp", "mp3", "m4a", "aac", "ogg", "wav")
    private val keepHeaders = setOf("user-agent", "referer", "origin", "cookie", "authorization")

    fun classify(url: String): Kind? {
        if (!url.startsWith("http")) return null
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        val ext = path.substringAfterLast('.', "")
        return when {
            ext == "m3u8" -> Kind.HLS
            ext in directExts -> Kind.DIRECT
            else -> null
        }
    }

    // Only forward headers that matter; forwarding Accept-Encoding would break OkHttp's gzip handling.
    fun cleanHeaders(h: Map<String, String>): Map<String, String> =
        h.filterKeys { it.lowercase() in keepHeaders }

    fun fileName(url: String): String =
        url.substringBefore('#').substringBefore('?').substringAfterLast('/').ifEmpty { "video" }
}
