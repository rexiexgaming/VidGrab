package com.rexie.vidgrab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DownloadService : Service() {

    companion object {
        private const val CHANNEL = "downloads"
        private const val NID = 1
        val stops = ConcurrentHashMap<String, AtomicBoolean>()

        fun start(ctx: Context, id: String) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, DownloadService::class.java).putExtra("id", id)
            )
        }

        fun pause(id: String) { stops[id]?.set(true) }
    }

    private val pool = Executors.newFixedThreadPool(3)
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { refreshNotif(); handler.postDelayed(this, 1000) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DownloadRepo.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "التحميلات", NotificationManager.IMPORTANCE_LOW)
        )
        handler.postDelayed(ticker, 1000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NID, buildNotif("جارٍ التحضير…", 0, true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        val id = intent?.getStringExtra("id")
        val task = DownloadRepo.tasks.firstOrNull { it.id == id }
        if (task != null && !stops.containsKey(task.id)) {
            val stop = AtomicBoolean(false)
            stops[task.id] = stop
            task.status = Status.QUEUED
            task.error = null
            DownloadRepo.changed(true)
            pool.execute { work(task, stop) }
        }
        refreshNotif()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        pool.shutdownNow()
        super.onDestroy()
    }

    // ---------- notification ----------

    private fun buildNotif(text: String, pct: Int, indeterminate: Boolean): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("VidGrab")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, indeterminate)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, DownloadsActivity::class.java), PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun refreshNotif() {
        val active = DownloadRepo.tasks.filter { it.status == Status.RUNNING || it.status == Status.QUEUED }
        if (active.isEmpty() && stops.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val first = active.firstOrNull()
        val text = if (first != null) "${active.size} تحميل · ${first.title}" else "جارٍ الإنهاء…"
        val n = buildNotif(text, first?.percent ?: 0, first == null || (first.total <= 0 && first.kind == Kind.DIRECT))
        getSystemService(NotificationManager::class.java).notify(NID, n)
    }

    // ---------- worker ----------

    private fun work(task: DownloadTask, stop: AtomicBoolean) {
        try {
            if (stop.get()) { task.status = Status.PAUSED; return }
            task.status = Status.RUNNING
            task.error = null
            DownloadRepo.changed(true)
            if (task.kind == Kind.HLS) downloadHls(task, stop) else downloadDirect(task, stop)
            if (stop.get()) {
                task.status = Status.PAUSED
            } else {
                publish(task)
                task.status = Status.DONE
            }
        } catch (e: Exception) {
            if (stop.get()) {
                task.status = Status.PAUSED
            } else {
                task.status = Status.FAILED
                task.error = e.message ?: e.javaClass.simpleName
            }
        } finally {
            stops.remove(task.id)
            DownloadRepo.changed(true)
            handler.post { refreshNotif() }
        }
    }

    private fun downloadDirect(task: DownloadTask, stop: AtomicBoolean) {
        val file = File(task.filePath)
        file.parentFile?.mkdirs()
        val existing = if (file.exists()) file.length() else 0L
        val extra = if (existing > 0) mapOf("Range" to "bytes=$existing-") else emptyMap()

        Http.client.newCall(Http.request(task.url, task.headers, extra)).execute().use { resp ->
            if (resp.code == 416 && existing > 0) {
                task.total = existing; task.downloaded = existing
                return
            }
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val ct = resp.header("Content-Type") ?: ""
            if (ct.startsWith("text/html")) throw IOException("الرابط لا يعطي ملف فيديو (قد يكون منتهي الصلاحية)")
            val body = resp.body ?: throw IOException("empty body")

            val resuming = resp.code == 206
            val offset = if (resuming) existing else 0L
            val len = body.contentLength()
            task.total = if (len > 0) len + offset else -1L

            var done = offset
            var last = 0L
            FileOutputStream(file, resuming).use { out ->
                val input = body.byteStream()
                val buf = ByteArray(64 * 1024)
                while (!stop.get()) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    task.downloaded = done
                    val now = System.currentTimeMillis()
                    if (now - last > 400) { last = now; DownloadRepo.changed() }
                }
            }
            if (!stop.get() && task.total > 0 && done < task.total) throw IOException("انقطع التحميل")
        }
    }

    private fun <T> retry(n: Int, block: () -> T): T {
        var last: Exception? = null
        repeat(n) {
            try { return block() } catch (e: Exception) { last = e; Thread.sleep(800) }
        }
        throw last ?: IOException("failed")
    }

    private fun downloadHls(task: DownloadTask, stop: AtomicBoolean) {
        val (mediaUrl, text) = Hls.fetchMedia(task.url, task.headers)
        val pl = Hls.parseMedia(mediaUrl, text)
        if (pl.segs.isEmpty()) throw IOException("لم يتم العثور على مقاطع في القائمة")
        task.segTotal = pl.segs.size

        var file = File(task.filePath)
        file.parentFile?.mkdirs()
        if (task.segDone == 0 || !file.exists()) {
            task.segDone = 0
            if (pl.mapUrl != null && file.extension != "mp4") {
                file = File(file.parentFile, file.nameWithoutExtension + ".mp4")
                task.filePath = file.absolutePath
            }
            file.delete()
            if (pl.mapUrl != null) {
                FileOutputStream(file).use { it.write(Http.getBytes(pl.mapUrl, task.headers)) }
            }
        }

        val keys = HashMap<String, ByteArray>()
        FileOutputStream(file, true).use { out ->
            var i = task.segDone
            while (i < pl.segs.size) {
                if (stop.get()) return
                val seg = pl.segs[i]
                var data = retry(3) { Http.getBytes(seg.url, task.headers) }
                val keyUri = seg.keyUri
                if (keyUri != null) {
                    val key = keys.getOrPut(keyUri) { Http.getBytes(keyUri, task.headers) }
                    val iv = seg.iv ?: Hls.seqIv(seg.seq)
                    val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                    data = c.doFinal(data)
                }
                out.write(data)
                task.downloaded += data.size
                i++
                task.segDone = i
                DownloadRepo.changed()
            }
        }
    }

    /** Move the finished file into the public Movies/Music folder so it shows in the gallery. */
    private fun publish(task: DownloadTask) {
        val src = File(task.filePath)
        if (!src.exists()) return
        try {
            val audio = task.isAudio
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, src.name)
                put(MediaStore.MediaColumns.MIME_TYPE, Util.mime(src.extension))
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    (if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) + "/VidGrab"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = if (audio)
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = contentResolver.insert(collection, values) ?: return
            contentResolver.openOutputStream(uri)!!.use { o -> src.inputStream().use { it.copyTo(o) } }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            contentResolver.update(uri, done, null, null)
            task.publicUri = uri.toString()
            src.delete()
        } catch (e: Exception) {
            // keep the private copy if publishing fails
        }
    }
}
