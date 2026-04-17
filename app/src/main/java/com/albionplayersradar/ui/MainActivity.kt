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
import android.widget.Button
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
        if (result.resultCode == RESULT_OK) startVpnService() else Toast.makeText(this, "VPN permission required", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#1a1a1a")); setPadding(24, 48, 24, 24) }

        val topBar = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; setPadding(0, 0, 0, 16) }

        toggleBtn = Button(this).apply { text = "Start Radar"; setOnClickListener { toggleVpn() } }
        statusText = TextView(this).apply { text = "Tap START to begin"; setTextColor(Color.parseColor("#888888")); textSize = 13f }
        playerCount = TextView(this).apply { text = "Players: 0"; setTextColor(Color.parseColor("#00ff88")); textSize = 15f }
        zoneText = TextView(this).apply { text = "Zone: --"; setTextColor(Color.WHITE); textSize = 14f }

        topBar.addView(toggleBtn)
        topBar.addView(statusText)
        topBar.addView(playerCount)
        topBar.addView(zoneText)

        renderer = PlayerRendererView(this)
        renderer.layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)

        root.addView(topBar)
        root.addView(renderer)
        setContentView(root)
        createNotificationChannel()
    }

    private fun setupService() {
        vpnService?.setUpdateListener { data ->
            runOnUiThread {
                val parts = data.split(":", limit = 2)
                if (parts.size < 2) return@runOnUiThread
                when (parts[0]) {
                    "SPAWN" -> {
                        val p = parts[1].split("|")
                        if (p.size >= 5) {
                            val player = Player(
                                id = p[0].toLongOrNull() ?: 0,
                                name = p[1],
                                guildName = p[2].ifEmpty { null },
                                allianceName = null,
                                faction = p[4].toIntOrNull() ?: 0,
                                posX = 0f, posY = 0f, posZ = 0f,
                                currentHealth = 0, maxHealth = 0,
                                isMounted = false
                            )
                            renderer.updatePlayers(listOf(player))
                            playerCount.text = "Players: 1"
                        }
                    }
                    "ZONE" -> { zoneText.text = "Zone: ${parts[1]}" }
                    "MOVE" -> { }
                    "LEAVE" -> { }
                }
            }
        }
    }

    private fun toggleVpn() {
        if (vpnService != null) { stopVpn(); return }
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermission.launch(intent) else startVpnService()
    }

    private fun startVpnService() {
        val intent = Intent(this, AlbionVpnService::class.java)
        startService(intent)
        bindService(intent, svcConnection, Context.BIND_AUTO_CREATE)
        vpnService = AlbionVpnService()
        setupService()
        statusText.text = "Radar active..."
        statusText.setTextColor(Color.parseColor("#00ff88"))
        toggleBtn.text = "Stop Radar"
    }

    private fun stopVpn() {
        if (vpnBound) { unbindService(svcConnection); vpnBound = false }
        vpnService?.stopVpn()
        vpnService = null
        statusText.text = "Radar stopped"
        statusText.setTextColor(Color.parseColor("#888888"))
        toggleBtn.text = "Start Radar"
        playerCount.text = "Players: 0"
        renderer.updatePlayers(emptyList())
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel("radar_channel", "Albion Radar", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (vpnBound) { unbindService(svcConnection); vpnBound = false }
    }
}
