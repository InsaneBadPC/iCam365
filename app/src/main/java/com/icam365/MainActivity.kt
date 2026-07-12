package com.icam365

import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.source.rtsp.RtspMediaSource
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class MainActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private val cameraIp = "172.20.94.172"
    private val username = "admin"
    private val password = "admin"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initPlayer()

        findViewById<Button>(R.id.btn_up).setOnClickListener {
            sendPTZCommand("0")
        }
        findViewById<Button>(R.id.btn_down).setOnClickListener {
            sendPTZCommand("2")
        }
        findViewById<Button>(R.id.btn_left).setOnClickListener {
            sendPTZCommand("4")
        }
        findViewById<Button>(R.id.btn_right).setOnClickListener {
            sendPTZCommand("6")
        }
        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            sendPTZCommand("1")
        }
    }

    private fun initPlayer() {
        player = ExoPlayer.Builder(this).build()
        val surfaceView = findViewById<SurfaceView>(R.id.surface_view)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                player?.setVideoSurface(holder.surface)
                playStream()
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                player?.clearVideoSurface()
            }
        })
    }

    private fun playStream() {
        try {
            val mediaItem =
                MediaItem.fromUri("rtsp://$username:$password@$cameraIp:554/0/av0")
            val mediaSource = RtspMediaSource.Factory().createMediaSource(mediaItem)
            player?.setMediaSource(mediaSource)
            player?.prepare()
            player?.playWhenReady = true
            Toast.makeText(this, "Připojuji se k $cameraIp...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "Chyba: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun sendPTZCommand(command: String) {
        Thread {
            try {
                val url =
                    "http://$username:$password@$cameraIp/decoder_control.cgi?command=$command"
                val client = OkHttpClient()
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                "PTZ chyba: ${response.code}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            } catch (e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "PTZ selhalo", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
