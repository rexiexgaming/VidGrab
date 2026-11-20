package com.rexie.vidgrab

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet

enum class Kind { DIRECT, HLS }
enum class Status { QUEUED, RUNNING, PAUSED, DONE, FAILED }

data class Variant(val label: String, val url: String, val bandwidth: Int = 0)

data class DetectedMedia(
    val url: String,
    val kind: Kind,
    val name: String,
    val pageTitle: String,
    val headers: Map<String, String>,
    @Volatile var size: Long = -1
)

class DownloadTask(
    val id: String,
    var title: String,
    val url: String,
    val kind: Kind,
    val headers: Map<String, String>,
    @Volatile var filePath: String,
    @Volatile var status: Status = Status.QUEUED,
    @Volatile var downloaded: Long = 0,
    @Volatile var total: Long = -1,
    @Volatile var segDone: Int = 0,
    @Volatile var segTotal: Int = 0,
    @Volatile var publicUri: String? = null,
    @Volatile var error: String? = null
) {
    val isAudio: Boolean
        get() = filePath.substringAfterLast('.').lowercase() in setOf("mp3", "m4a", "aac", "ogg", "wav")

    val percent: Int
        get() = when {
            status == Status.DONE -> 100
            kind == Kind.HLS && segTotal > 0 -> segDone * 100 / segTotal
            total > 0 -> (downloaded * 100 / total).toInt()
            else -> 0
        }
}

object DownloadRepo {
    val tasks = CopyOnWriteArrayList<DownloadTask>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private var prefs: SharedPreferences? = null
    private var lastSave = 0L

    @Synchronized
    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("downloads", Context.MODE_PRIVATE)
        prefs = p
        try {
            val arr = JSONArray(p.getString("tasks", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val hm = mutableMapOf<String, String>()
                val h = o.optJSONObject("headers")
                h?.keys()?.forEach { k -> hm[k] = h.getString(k) }
                val t = DownloadTask(
                    o.getString("id"), o.getString("title"), o.getString("url"),
                    Kind.valueOf(o.getString("kind")), hm, o.getString("filePath"),
                    Status.valueOf(o.getString("status")),
                    o.optLong("downloaded"), o.optLong("total", -1),
                    o.optInt("segDone"), o.optInt("segTotal"),
                    o.optString("publicUri").ifEmpty { null },
                    o.optString("error").ifEmpty { null }
                )
                if (t.status == Status.RUNNING || t.status == Status.QUEUED) t.status = Status.PAUSED
                tasks.add(t)
            }
        } catch (e: Exception) {
            // corrupted state: start clean
        }
    }

    fun add(t: DownloadTask) { tasks.add(t); changed(true) }
    fun remove(t: DownloadTask) { tasks.remove(t); changed(true) }
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    @Synchronized
    private fun save() {
        val arr = JSONArray()
        for (t in tasks) {
            arr.put(JSONObject().apply {
                put("id", t.id); put("title", t.title); put("url", t.url)
                put("kind", t.kind.name); put("headers", JSONObject(t.headers))
                put("filePath", t.filePath); put("status", t.status.name)
                put("downloaded", t.downloaded); put("total", t.total)
                put("segDone", t.segDone); put("segTotal", t.segTotal)
                put("publicUri", t.publicUri ?: ""); put("error", t.error ?: "")
            })
        }
        prefs?.edit()?.putString("tasks", arr.toString())?.apply()
    }

    fun changed(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || now - lastSave > 1500) { lastSave = now; save() }
        for (l in listeners) l()
    }
}
