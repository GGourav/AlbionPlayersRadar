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
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.albionplayersradar.R
import com.albionplayersradar.data.Player
import com.albionplayersradar.vpn.AlbionVpnService

class MainActivity : Activity() {

    private var vpnService: AlbionVpnService? = null
    private var vpnBound = false
    private lateinit var renderer: PlayerRendererView
    private lateinit var statusText: TextView
    private lateinit var playerCount: TextView
    private lateinit var zoneText: TextView
    private lateinit var toggleBtn: Button

    private val svcConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as? AlbionVpnService.LocalBinder
            vpnService = b?.getService()
            vpnBound = true
            setupService()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            vpnService = null
            vpnBound = false
        }
    }

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1a1a2e"))
        }

        val topBar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 8)
            setBackgroundColor(Color.parseColor("#2d2d2d"))
        }

        toggleBtn = Button(this).apply {
            text = "Start Radar"
            setOnClickListener { toggleVpn() }
        }

        zoneText = TextView(this).apply {
            text = "Zone: --"
            setTextColor(Color.WHITE)
            setPadding(16, 0, 16, 0)
        }

        playerCount = TextView(this).apply {
            text = "Players: 0"
            setTextColor(Color.parseColor("#00ff88"))
        }

        topBar.addView(toggleBtn)
        topBar.addView(zoneText)
        topBar.addView(playerCount)

        renderer = PlayerRendererView(this)
        renderer.layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        )

        statusText = TextView(this).apply {
            text = "Tap START to activate radar"
            setTextColor(Color.parseColor("#666666"))
            textSize = 12f
            setPadding(16, 8, 16, 8)
            gravity = android.view.Gravity.CENTER
        }

        root.addView(topBar)
        root.addView(renderer)
        root.addView(statusText)
        setContentView(root)

        createNotificationChannel()
        requestPermissions()
    }

    private fun requestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun toggleVpn() {
        if (vpnService != null) stopVpn() else launchVpn()
    }

    private fun launchVpn() {
        val intent = android.net.VpnService.prepare(this)
        if (intent != null) {
            vpnPermission.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, AlbionVpnService::class.java)
        startService(intent)
        bindService(intent, svcConnection, Context.BIND_AUTO_CREATE)
        vpnBound = true
    }

    private fun stopVpn() {
        if (vpnBound) {
            unbindService(svcConnection)
            vpnBound = false
        }
        vpnService?.let {
            it.setPlayerUpdateListener {}
            it.stopRun()
        }
        vpnService = null
    }

    private fun setupService() {
        vpnService?.setPlayerUpdateListener { data ->
            runOnUiThread {
                val parts = data.split(":", limit = 2)
                if (parts.size < 2) return@runOnUiThread
                when (parts[0]) {
                    "SPAWN" -> {
                        val p = parts[1].split("|")
                        if (p.size >= 5) {
                            statusText.text = "Player: ${p[1]}"
                        }
                    }
                    "ZONE" -> {
                        zoneText.text = "Zone: ${parts[1]}"
                        renderer.setPvPType(vpnService?.getCurrentZonePvpType() ?: "safe")
                    }
                    "LOCAL" -> {
                        val coords = parts[1].split("|")
                        if (coords.size >= 2) {
                            val x = coords[0].toFloatOrNull() ?: 0f
                            val y = coords[1].toFloatOrNull() ?: 0f
                            renderer.updateLocalPosition(x, y)
                        }
                    }
                    "LEAVE" -> statusText.text = "Player left: ${parts[1]}"
                    "MOVE" -> updatePlayerCount()
                }
                updatePlayerCount()
            }
        }

        val players = vpnService?.getAllPlayers() ?: emptyList()
        val zone = vpnService?.getCurrentZone() ?: ""
        val pvpType = vpnService?.getCurrentZonePvpType() ?: "safe"

        zoneText.text = "Zone: $zone"
        renderer.setPvPType(pvpType)
        renderer.updatePlayers(players)

        toggleBtn.text = "Stop Radar"
        statusText.text = "Radar active..."
        statusText.setTextColor(Color.parseColor("#00ff88"))
        updatePlayerCount()
    }

    private fun updatePlayerCount() {
        val count = vpnService?.getAllPlayers()?.size ?: 0
        playerCount.text = "Players: $count"
        vpnService?.getAllPlayers()?.let { renderer.updatePlayers(it) }
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "radar_channel", "Albion Radar", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        if (vpnBound) {
            unbindService(svcConnection)
            vpnBound = false
        }
        super.onDestroy()
    }

    companion object {
        fun broadcastPlayerJoined(id: Long, name: String, guild: String, posX: Float, posY: Float, faction: Int) {}
        fun broadcastPlayerMove(id: Long, posX: Float, posY: Float) {}
        fun broadcastPlayerLeave(id: Long) {}
        fun broadcastLocalPlayer(id: Long, posX: Float, posY: Float) {}
        fun broadcastFactionChanged(id: Long, faction: Int) {}
        fun broadcastZone(zoneId: String) {}
    }
}
