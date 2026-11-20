package com.rexie.vidgrab

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun addHeaders(b: Request.Builder, h: Map<String, String>) {
        for ((k, v) in h) {
            try { b.header(k, v) } catch (e: IllegalArgumentException) { /* skip invalid header */ }
        }
    }

    fun request(url: String, headers: Map<String, String>, extra: Map<String, String> = emptyMap()): Request {
        val b = Request.Builder().url(url)
        addHeaders(b, headers)
        addHeaders(b, extra)
        return b.build()
    }

    fun getText(url: String, headers: Map<String, String>): String =
        client.newCall(request(url, headers)).execute().use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
            it.body!!.string()
        }

    fun getBytes(url: String, headers: Map<String, String>): ByteArray =
        client.newCall(request(url, headers)).execute().use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
            it.body!!.bytes()
        }

    fun headSize(url: String, headers: Map<String, String>): Long = try {
        val b = Request.Builder().url(url).head()
        addHeaders(b, headers)
        client.newCall(b.build()).execute().use { r ->
            if (r.isSuccessful) (r.header("Content-Length")?.toLongOrNull() ?: -1L) else -1L
        }
    } catch (e: Exception) {
        -1L
    }
}
