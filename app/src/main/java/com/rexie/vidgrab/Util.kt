package com.rexie.vidgrab

import java.io.File

object Util {
    fun sanitize(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(80)
            .ifEmpty { "video" }

    fun uniqueFile(dir: File, base: String, ext: String): File {
        var f = File(dir, "$base.$ext")
        var n = 1
        while (f.exists()) { f = File(dir, "$base ($n).$ext"); n++ }
        return f
    }

    fun size(bytes: Long): String {
        if (bytes <= 0) return ""
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> String.format("%.2f GB", gb)
            mb >= 1 -> String.format("%.1f MB", mb)
            else -> String.format("%.0f KB", kb)
        }
    }

    fun mime(ext: String): String = when (ext.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "flv" -> "video/x-flv"
        "avi" -> "video/x-msvideo"
        "3gp" -> "video/3gpp"
        "ts" -> "video/mp2t"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/x-wav"
        else -> "video/mp4"
    }
}
