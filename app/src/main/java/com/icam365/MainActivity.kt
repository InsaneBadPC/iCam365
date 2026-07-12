package com.icam365

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.source.rtsp.RtspMediaSource
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private var cameraIp: String? = null
    private val username = "admin"
    private val password = "admin"
    private val statusText by lazy { findViewById<TextView>(R.id.status_text) }
    private val executor = Executors.newFixedThreadPool(10)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText.text = "Hledám kameru..."
        startCameraDiscovery()

        findViewById<Button>(R.id.btn_up).setOnClickListener { sendPTZCommand("0") }
        findViewById<Button>(R.id.btn_down).setOnClickListener { sendPTZCommand("2") }
        findViewById<Button>(R.id.btn_left).setOnClickListener { sendPTZCommand("4") }
        findViewById<Button>(R.id.btn_right).setOnClickListener { sendPTZCommand("6") }
        findViewById<Button>(R.id.btn_stop).setOnClickListener { sendPTZCommand("1") }
    }

    private fun startCameraDiscovery() {
        Thread {
            val ip = findCameraIp()
            if (ip != null) {
                cameraIp = ip
                runOnUiThread {
                    statusText.text = "Kamera nalezena: $ip"
                    Toast.makeText(this, "Kamera: $ip", Toast.LENGTH_SHORT).show()
                    initPlayer()
                }
            } else {
                runOnUiThread {
                    statusText.text = "Kamera nenalezena - zkontroluj WiFi"
                    Toast.makeText(this, "Kamera nenalezena!", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun findCameraIp(): String? {
        val localIp = getLocalIpAddress() ?: return null
        val subnet = localIp.substringBeforeLast(".")
        val arpTable = readArpTable()

        // Hledej v ARP tabulce podle MAC prefix 24:72:60 (EYEPLUS)
        for (entry in arpTable) {
            if (entry.mac.startsWith("24:72:60") || entry.mac.startsWith("24:72:60:6f")) {
                return entry.ip
            }
        }

        // Zkus proscanovat subnet a hledat RTSP port 554
        val candidates = mutableListOf<String>()
        for (i in 1..254) {
            val ip = "$subnet.$i"
            executor.submit {
                if (isPortOpen(ip, 554, 500)) {
                    synchronized(candidates) {
                        candidates.add(ip)
                    }
                }
            }
        }
        Thread.sleep(5000L)

        // Otestuj RTSP na nalezených kandidátech
        for (ip in candidates) {
            if (testRtsp(ip)) {
                return ip
            }
        }

        return null
    }

    private fun getLocalIpAddress(): String? {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wifiManager.connectionInfo.ipAddress
        return String.format(
            "%d.%d.%d.%d",
            ip and 0xff,
            ip shr 8 and 0xff,
            ip shr 16 and 0xff,
            ip shr 24 and 0xff
        )
    }

    data class ArpEntry(val ip: String, val mac: String)

    private fun readArpTable(): List<ArpEntry> {
        val entries = mutableListOf<ArpEntry>()
        try {
            val process = Runtime.getRuntime().exec("ip neigh")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line = reader.readLine()
            while (line != null) {
                val parts = line.split("\\s+".toRegex())
                if (parts.size >= 5 && parts[3] == "lladdr") {
                    entries.add(ArpEntry(parts[0], parts[4].lowercase()))
                } else if (parts.size >= 5 && parts[2] == "lladdr") {
                    entries.add(ArpEntry(parts[0], parts[1].lowercase()))
                }
                line = reader.readLine()
            }
            process.waitFor()
        } catch (_: Exception) {}
        return entries
    }

    private fun isPortOpen(host: String, port: Int, timeout: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeout)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun testRtsp(ip: String): Boolean {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(java.time.Duration.ofMillis(3000))
                .build()
            val request = Request.Builder()
                .url("http://$username:$password@$ip:554/")
                .build()
            client.newCall(request).execute().use { response ->
                response.isSuccessful || response.code in 400..401
            }
        } catch (_: Exception) {
            false
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
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                player?.clearVideoSurface()
            }
        })
    }

    private fun playStream() {
        val ip = cameraIp ?: return
        try {
            val mediaItem = MediaItem.fromUri("rtsp://$username:$password@$ip:554/0/av0")
            val mediaSource = RtspMediaSource.Factory().createMediaSource(mediaItem)
            player?.setMediaSource(mediaSource)
            player?.prepare()
            player?.playWhenReady = true
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "Chyba: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun sendPTZCommand(command: String) {
        val ip = cameraIp ?: return
        Thread {
            try {
                val url = "http://$username:$password@$ip/decoder_control.cgi?command=$command"
                val client = OkHttpClient()
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { }
            } catch (_: Exception) {}
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
        executor.shutdownNow()
    }
}
