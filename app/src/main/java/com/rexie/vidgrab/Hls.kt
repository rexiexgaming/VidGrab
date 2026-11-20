package com.rexie.vidgrab

import java.io.IOException
import java.net.URI
import java.net.URL

class Seg(val url: String, val keyUri: String?, val iv: ByteArray?, val seq: Long)
class MediaPlaylist(val segs: List<Seg>, val mapUrl: String?)

object Hls {
    private val attrRe = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")

    fun attrs(s: String): Map<String, String> =
        attrRe.findAll(s).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    fun resolve(base: String, ref: String): String = try {
        URI(base).resolve(ref).toString()
    } catch (e: Exception) {
        URL(URL(base), ref).toString()
    }

    fun hexToBytes(s: String): ByteArray {
        val h = s.removePrefix("0x").removePrefix("0X").padStart(32, '0')
        return ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun seqIv(seq: Long): ByteArray {
        val b = ByteArray(16)
        for (i in 0 until 8) b[15 - i] = (seq shr (8 * i)).toByte()
        return b
    }

    fun variantsFromText(url: String, text: String): List<Variant> {
        val lines = text.lines()
        val out = mutableListOf<Variant>()
        for (i in lines.indices) {
            val l = lines[i].trim()
            if (!l.startsWith("#EXT-X-STREAM-INF")) continue
            val a = attrs(l.substringAfter(':'))
            val bw = a["BANDWIDTH"]?.toIntOrNull() ?: 0
            val h = a["RESOLUTION"]?.substringAfter('x')?.toIntOrNull()
            val rate = if (bw > 0) String.format("%.1f Mbps", bw / 1_000_000.0) else ""
            val label = when {
                h != null && rate.isNotEmpty() -> "${h}p · $rate"
                h != null -> "${h}p"
                rate.isNotEmpty() -> rate
                else -> "Auto"
            }
            var j = i + 1
            while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
            if (j < lines.size) out += Variant(label, resolve(url, lines[j].trim()), bw)
        }
        return out.distinctBy { it.url }.sortedByDescending { it.bandwidth }
    }

    fun variants(url: String, headers: Map<String, String>): List<Variant> =
        variantsFromText(url, Http.getText(url, headers))

    /** Returns (final media-playlist url, text). If given a master playlist, picks the best variant. */
    fun fetchMedia(url: String, headers: Map<String, String>): Pair<String, String> {
        var u = url
        var text = Http.getText(u, headers)
        if (text.contains("#EXT-X-STREAM-INF")) {
            val best = variantsFromText(u, text).firstOrNull() ?: throw IOException("no variants")
            u = best.url
            text = Http.getText(u, headers)
        }
        return u to text
    }

    fun parseMedia(base: String, text: String): MediaPlaylist {
        var seq = 0L
        var idx = 0L
        var keyUri: String? = null
        var keyIv: ByteArray? = null
        var mapUrl: String? = null
        val segs = mutableListOf<Seg>()
        for (raw in text.lines()) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            if (l.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                seq = l.substringAfter(':').trim().toLongOrNull() ?: 0L
            } else if (l.startsWith("#EXT-X-KEY:")) {
                val a = attrs(l.substringAfter(':'))
                when (val m = a["METHOD"]) {
                    null, "NONE" -> { keyUri = null; keyIv = null }
                    "AES-128" -> {
                        keyUri = a["URI"]?.let { resolve(base, it) }
                        keyIv = a["IV"]?.let { hexToBytes(it) }
                    }
                    else -> throw IOException("بث محمي ($m) غير مدعوم")
                }
            } else if (l.startsWith("#EXT-X-MAP:")) {
                mapUrl = attrs(l.substringAfter(':'))["URI"]?.let { resolve(base, it) }
            } else if (!l.startsWith("#")) {
                segs += Seg(resolve(base, l), keyUri, keyIv, seq + idx)
                idx++
            }
        }
        return MediaPlaylist(segs, mapUrl)
    }
}
