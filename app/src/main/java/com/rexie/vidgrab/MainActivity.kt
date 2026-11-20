package com.rexie.vidgrab

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import org.json.JSONArray
import org.json.JSONTokener
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var fab: ExtendedFloatingActionButton

    private val found = mutableListOf<DetectedMedia>()
    private val seen = HashSet<String>()
    private val io = Executors.newFixedThreadPool(2)
    private var sheetAdapter: MediaAdapter? = null

    @Volatile private var pageUrl = ""
    @Volatile private var pageTitle = ""
    @Volatile private var ua = ""

    private val scanJs =
        "(function(){var r=[];document.querySelectorAll('video,audio,source').forEach(function(e){" +
            "var s=e.currentSrc||e.src;if(s&&s.indexOf('http')===0)r.push(s)});return JSON.stringify(r)})()"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        DownloadRepo.init(this)

        web = findViewById(R.id.web)
        urlBar = findViewById(R.id.urlBar)
        progress = findViewById(R.id.progress)
        fab = findViewById(R.id.fab)
        setupWebView()

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { if (web.canGoBack()) web.goBack() }
        findViewById<ImageButton>(R.id.btnGo).setOnClickListener { load(urlBar.text.toString()) }
        findViewById<ImageButton>(R.id.btnDownloads).setOnClickListener {
            startActivity(Intent(this, DownloadsActivity::class.java))
        }
        urlBar.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) { load(urlBar.text.toString()); true } else false
        }
        fab.setOnClickListener { scanDom(); showSheet() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        if (!handleIntent(intent)) load("https://www.google.com")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        io.shutdownNow()
        web.destroy()
        super.onDestroy()
    }

    // ---------- navigation ----------

    private fun handleIntent(i: Intent?): Boolean {
        val text = when (i?.action) {
            Intent.ACTION_SEND -> i?.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> i?.dataString
            else -> null
        } ?: return false
        val m = Regex("https?://\\S+").find(text) ?: return false
        load(m.value)
        return true
    }

    private fun load(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(' ') || !t.contains('.') -> "https://www.google.com/search?q=" + Uri.encode(t)
            else -> "https://$t"
        }
        web.loadUrl(url)
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(urlBar.windowToken, 0)
        urlBar.clearFocus()
    }

    // ---------- WebView + video sniffing ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        ua = s.userAgentString
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onReceivedTitle(view: WebView, title: String?) { pageTitle = title ?: "" }
        }

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                pageUrl = url
                pageTitle = ""
                urlBar.setText(url)
                clearFound()
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageUrl = url
                view.postDelayed({ scanDom() }, 1500)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url.toString()
                val kind = Sniffer.classify(u)
                if (kind != null) onDetected(u, kind, request.requestHeaders)
                return null // let the WebView load it normally
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: ""
                return scheme != "http" && scheme != "https"
            }
        }
    }

    private fun onDetected(url: String, kind: Kind, reqHeaders: Map<String, String>) {
        val h = Sniffer.cleanHeaders(reqHeaders).toMutableMap()
        if (h.keys.none { it.equals("User-Agent", true) }) h["User-Agent"] = ua
        if (h.keys.none { it.equals("Referer", true) } && pageUrl.isNotEmpty()) h["Referer"] = pageUrl
        if (h.keys.none { it.equals("Cookie", true) }) {
            CookieManager.getInstance().getCookie(url)?.let { h["Cookie"] = it }
        }
        val media = DetectedMedia(url, kind, Sniffer.fileName(url), pageTitle, h)
        runOnUiThread { addMedia(media) }
    }

    private fun scanDom() {
        web.evaluateJavascript(scanJs) { res ->
            try {
                val raw = JSONTokener(res).nextValue() as? String ?: return@evaluateJavascript
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val u = arr.getString(i)
                    onDetected(u, Sniffer.classify(u) ?: Kind.DIRECT, emptyMap())
                }
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun addMedia(m: DetectedMedia) {
        if (!seen.add(m.url)) return
        found.add(m)
        updateFab()
        sheetAdapter?.notifyDataSetChanged()
        if (m.kind == Kind.DIRECT) {
            io.execute {
                m.size = Http.headSize(m.url, m.headers)
                runOnUiThread { sheetAdapter?.notifyDataSetChanged() }
            }
        }
    }

    private fun clearFound() {
        found.clear()
        seen.clear()
        updateFab()
    }

    private fun updateFab() {
        fab.text = "تحميل (${found.size})"
        fab.visibility = if (found.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---------- picking + enqueueing ----------

    private fun showSheet() {
        val dlg = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_media, null)
        val rv = v.findViewById<RecyclerView>(R.id.rv)
        v.findViewById<TextView>(R.id.empty).visibility = if (found.isEmpty()) View.VISIBLE else View.GONE
        val adapter = MediaAdapter(found) { m -> dlg.dismiss(); onPick(m) }
        sheetAdapter = adapter
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        dlg.setContentView(v)
        dlg.setOnDismissListener { sheetAdapter = null }
        dlg.show()
    }

    private fun onPick(m: DetectedMedia) {
        if (m.kind == Kind.DIRECT) { enqueue(m, m.url, null); return }
        val wait = AlertDialog.Builder(this).setMessage(R.string.loading_qualities).setCancelable(false).create()
        wait.show()
        io.execute {
            val vs = try { Hls.variants(m.url, m.headers) } catch (e: Exception) { emptyList() }
            runOnUiThread {
                wait.dismiss()
                when {
                    vs.isEmpty() -> enqueue(m, m.url, null)
                    vs.size == 1 -> enqueue(m, vs[0].url, vs[0].label)
                    else -> AlertDialog.Builder(this)
                        .setTitle(R.string.choose_quality)
                        .setItems(vs.map { it.label }.toTypedArray()) { _, i -> enqueue(m, vs[i].url, vs[i].label) }
                        .show()
                }
            }
        }
    }

    private fun enqueue(m: DetectedMedia, url: String, label: String?) {
        val title = m.pageTitle.ifBlank { m.name.substringBeforeLast('.') }
        val base = Util.sanitize(title) + (if (label != null) " [${label.substringBefore(' ')}]" else "")
        val ext = if (m.kind == Kind.HLS) "ts" else {
            val e = m.name.substringAfterLast('.', "").lowercase()
            if (e in Sniffer.directExts) e else "mp4"
        }
        val dir = getExternalFilesDir("downloads")!!.apply { mkdirs() }
        val file = Util.uniqueFile(dir, base, ext)
        val task = DownloadTask(UUID.randomUUID().toString(), file.nameWithoutExtension, url, m.kind, m.headers, file.absolutePath)
        DownloadRepo.add(task)
        DownloadService.start(this, task.id)
        Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
    }
}

class MediaAdapter(
    private val items: List<DetectedMedia>,
    private val onClick: (DetectedMedia) -> Unit
) : RecyclerView.Adapter<MediaAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.title)
        val sub: TextView = v.findViewById(R.id.sub)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_media, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        holder.title.text = m.pageTitle.ifBlank { m.name }
        val type = if (m.kind == Kind.HLS) "HLS (m3u8)" else m.name.substringAfterLast('.', "video").uppercase()
        val size = Util.size(m.size)
        holder.sub.text = listOf(type, size, m.name).filter { it.isNotEmpty() }.joinToString(" · ")
        holder.itemView.setOnClickListener { onClick(m) }
    }
}
