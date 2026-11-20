package com.rexie.vidgrab

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class DownloadsActivity : AppCompatActivity() {

    private lateinit var adapter: TaskAdapter
    private lateinit var empty: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var pending = false

    private val refresh = Runnable {
        pending = false
        adapter.notifyDataSetChanged()
        empty.visibility = if (DownloadRepo.tasks.isEmpty()) View.VISIBLE else View.GONE
    }
    private val listener: () -> Unit = {
        if (!pending) { pending = true; handler.postDelayed(refresh, 300) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)
        DownloadRepo.init(this)
        empty = findViewById(R.id.empty)
        adapter = TaskAdapter()
        val rv = findViewById<RecyclerView>(R.id.rv)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        refresh.run()
    }

    override fun onStart() { super.onStart(); DownloadRepo.addListener(listener); refresh.run() }
    override fun onStop() { DownloadRepo.removeListener(listener); super.onStop() }

    private fun play(t: DownloadTask) {
        val uri = t.publicUri ?: Uri.fromFile(File(t.filePath)).toString()
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("uri", uri).putExtra("title", t.title))
    }

    private fun confirmDelete(t: DownloadTask) {
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_q)
            .setPositiveButton(R.string.yes) { _, _ ->
                DownloadService.pause(t.id)
                DownloadRepo.remove(t)
                try { File(t.filePath).delete() } catch (e: Exception) { }
                t.publicUri?.let { try { contentResolver.delete(Uri.parse(it), null, null) } catch (e: Exception) { } }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    class TaskVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.title)
        val status: TextView = v.findViewById(R.id.status)
        val bar: ProgressBar = v.findViewById(R.id.bar)
        val action: ImageButton = v.findViewById(R.id.btnAction)
        val play: ImageButton = v.findViewById(R.id.btnPlay)
        val delete: ImageButton = v.findViewById(R.id.btnDelete)
    }

    inner class TaskAdapter : RecyclerView.Adapter<TaskVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            TaskVH(LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false))

        override fun getItemCount() = DownloadRepo.tasks.size

        override fun onBindViewHolder(h: TaskVH, position: Int) {
            val t = DownloadRepo.tasks.getOrNull(DownloadRepo.tasks.size - 1 - position) ?: return
            h.title.text = t.title
            h.bar.progress = t.percent
            h.status.text = when (t.status) {
                Status.QUEUED -> "في الانتظار…"
                Status.RUNNING -> "${t.percent}% · ${Util.size(t.downloaded)}"
                Status.PAUSED -> "متوقف مؤقتًا · ${t.percent}%"
                Status.DONE -> "تم التحميل" + (Util.size(t.downloaded).let { if (it.isEmpty()) "" else " · $it" })
                Status.FAILED -> "فشل: ${t.error ?: ""}"
            }
            val done = t.status == Status.DONE
            h.action.visibility = if (done) View.GONE else View.VISIBLE
            h.play.visibility = if (done) View.VISIBLE else View.GONE
            val running = t.status == Status.RUNNING || t.status == Status.QUEUED
            h.action.setImageResource(if (running) R.drawable.ic_pause else R.drawable.ic_play)
            h.action.setOnClickListener {
                if (running) DownloadService.pause(t.id) else DownloadService.start(this@DownloadsActivity, t.id)
            }
            h.play.setOnClickListener { play(t) }
            h.delete.setOnClickListener { confirmDelete(t) }
        }
    }
}
