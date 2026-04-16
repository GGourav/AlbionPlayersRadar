package com.albionplayersradar.ui

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.albionplayersradar.R
import com.albionplayersradar.parser.PhotonPacketParser
import com.albionplayersradar.vpn.AlbionVpnService

class MainActivity : AppCompatActivity(), PhotonPacketParser.PlayerListener {

    private var vpnService: AlbionVpnService? = null
    private var vpnBound = false
    private var radarView: RadarView? = null
    private var vpnButton: Button? = null
    private var statusText: TextView? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? AlbionVpnService.LocalBinder
            vpnService = binder?.getService()
            vpnBound = true
            vpnService?.setPlayerListener(this@MainActivity)
            updateVpnButton()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            vpnService = null
            vpnBound = false
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpn()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        setContentView(R.layout.activity_main)

        vpnButton = findViewById(R.id.btn_toggle)
        val clearBtn = findViewById<Button>(R.id.btn_clear)
        statusText = findViewById(R.id.status_text)

        vpnButton?.setOnClickListener { toggleVpn() }
        clearBtn?.setOnClickListener { radarView?.clearPlayers() }

        findViewById<FrameLayout>(R.id.radar_container)?.let { container ->
            radarView = RadarView(this).also { container.addView(it) }
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, AlbionVpnService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (vpnBound) {
            vpnService?.setPlayerListener(null)
            unbindService(serviceConnection)
            vpnBound = false
        }
    }

    private fun toggleVpn() {
        if (vpnBound && vpnService?.isRunning == true) {
            vpnService?.stopVpn()
        } else {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                vpnPermissionLauncher.launch(intent)
            } else {
                startVpn()
            }
        }
        updateVpnButton()
    }

    private fun startVpn() {
        if (!vpnBound) return
        vpnService?.startVpn()
        updateVpnButton()
    }

    private fun updateVpnButton() {
        val running = vpnBound && vpnService?.isRunning == true
        vpnButton?.text = if (running) "Stop Radar" else "Start Radar"
        statusText?.text = if (running) "Radar Active" else "Radar Inactive"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Albion Radar",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    override fun onPlayerFound(player: PhotonPacketParser.PlayerInfo) {
        runOnUiThread { radarView?.addPlayer(player) }
    }

    override fun onPlayerLeft(id: Int) {
        runOnUiThread { radarView?.removePlayer(id) }
    }

    override fun onPlayerMoved(player: PhotonPacketParser.PlayerInfo) {
        runOnUiThread { radarView?.updatePlayer(player) }
    }

    override fun onPlayerHealthChanged(id: Int, health: Float, maxHealth: Float) {}

    companion object {
        const val CHANNEL_ID = "albion_radar_channel"
    }
}

class RadarView(context: Context) : View(context) {

    private val players = mutableMapOf<Int, PhotonPacketParser.PlayerInfo>()
    private val paint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        isAntiAlias = true
    }
    private val playerDot = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.FILL
    }
    private val playerLine = Paint().apply {
        color = Color.GRAY
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val localPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
    }

    private var localX = 0f
    private var localY = 0f
    private var scale = 20f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f

        // Draw local player
        canvas.drawCircle(centerX, centerY, 8f, localPaint)

        // Draw players
        players.values.forEach { player ->
            val dx = (player.posX - localX) * scale
            val dy = (player.posY - localY) * scale
            val px = centerX + dx
            val py = centerY + dy

            if (px >= 0 && px <= width && py >= 0 && py <= height) {
                canvas.drawCircle(px, py, 6f, playerDot)
                canvas.drawText(player.name ?: "??", px + 10, py - 10, paint)
            }
        }
    }

    fun addPlayer(player: PhotonPacketParser.PlayerInfo) {
        players[player.id] = player
        invalidate()
    }

    fun removePlayer(id: Int) {
        players.remove(id)
        invalidate()
    }

    fun updatePlayer(player: PhotonPacketParser.PlayerInfo) {
        players[player.id] = player
        invalidate()
    }

    fun clearPlayers() {
        players.clear()
        invalidate()
    }

    fun setLocalPosition(x: Float, y: Float) {
        localX = x
        localY = y
        invalidate()
    }
}
