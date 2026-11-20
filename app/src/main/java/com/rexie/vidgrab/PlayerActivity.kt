package com.rexie.vidgrab

import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class PlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        view = findViewById(R.id.player)
        title = intent.getStringExtra("title") ?: ""
    }

    override fun onStart() {
        super.onStart()
        val uri = intent.getStringExtra("uri") ?: return
        player = ExoPlayer.Builder(this).build().also {
            view.player = it
            it.setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
            it.prepare()
            it.playWhenReady = true
        }
    }

    override fun onStop() {
        player?.release()
        player = null
        super.onStop()
    }
}
