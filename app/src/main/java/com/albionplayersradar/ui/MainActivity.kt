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
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.albionplayersradar.R
import com.albionplayersradar.vpn.AlbionVpnService

class MainActivity : Activity() {

    private var vpnService: AlbionVpnService? = null
    private var vpnBound = false

    private val svcConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as? AlbionVpnService.LocalBinder
            vpnService = b?.getService()
            vpnBound = true
            setupVpnCallbacks()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            vpnService = null
            vpnBound = false
        }
    }

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) startVpnService()
        else Toast.makeText(this, "VPN permission required", Toast.LENGTH_SHORT).show()
    }

    private lateinit var renderer: PlayerRendererView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#111111"))
            setPadding(32, 32, 32, 32)
        }

        val topBar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
        }

        val vpnBtn = android.widget.Button(this).apply {
            text = "Start Radar"
            setOnClickListener { toggleVpn() }
        }

        val zoneLabel = android.widget.TextView(this).apply {
            text = "Zone: --"
            setTextColor(android.graphics.Color.WHITE)
            setPadding(24, 0, 24, 0)
        }

        val countLabel = android.widget.TextView(this).apply {
            text = "Players: 0"
            setTextColor(android.graphics.Color.parseColor("#00FF88"))
        }

        topBar.addView(vpnBtn)
        topBar.addView(zoneLabel)
        topBar.addView(countLabel)

        renderer = PlayerRendererView(this)

        root.addView(topBar)
        root.addView(renderer)

        setContentView(root)

        // Inject views for VPN callbacks
        addContentView(
            android.widget.TextView(this).apply {
                tag = "zoneLabel"
                setTextColor(android.graphics.Color.WHITE)
                setPadding(16, 8, 16, 8)
                setBackgroundColor(android.graphics.Color.parseColor("#2d2d2d"))
            },
            android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 }
        )
    }

    private fun setupVpnCallbacks() {
        vpnService?.setOnUpdateListener { msg ->
            runOnUiThread {
                when {
                    msg.startsWith("ZONE:") -> {
                        val zone = msg.substringAfter("ZONE:")
                        findViewWithTag<android.widget.TextView>("zoneLabel")?.text = "Zone: $zone"
                    }
                    msg.startsWith("PLAYER:") -> {
                        val parts = msg.substringAfter("PLAYER:").split("|")
                        if (parts.size >= 4) {
                            val id = parts[0].toLongOrNull() ?: return@runOnUiThread
                            val name = parts[1]
                            val guild = parts.getOrNull(2) ?: ""
                            val faction = parts.getOrNull(3)?.toIntOrNull() ?: 0
                            val player = com.albionplayersradar.data.Player(
                                id = id,
                                name = name,
                                guildName = guild.ifEmpty { null },
                                allianceName = null,
                                faction = faction
                            )
                            renderer.updatePlayer(player)
                            findViewById<android.widget.TextView>(R.id.tv_count)?.text = "Players: ${vpnService?.getPlayerCount() ?: 0}"
                        }
                    }
                    msg.startsWith("MOVE:") -> {
                        val parts = msg.substringAfter("MOVE:").split("|")
                        if (parts.size >= 3) {
                            val id = parts[0].toLongOrNull() ?: return@runOnUiThread
                            val x = parts[1].toFloatOrNull() ?: return@runOnUiThread
                            val y = parts[2].toFloatOrNull() ?: return@runOnUiThread
                            val p = vpnService?.getPlayer(id)
                            if (p != null) {
                                p.posX = x
                                p.posY = y
                                renderer.updatePlayer(p)
                            }
                        }
                    }
                    msg.startsWith("LEFT:") -> {
                        val id = msg.substringAfter("LEFT:").toLongOrNull() ?: return@runOnUiThread
                        renderer.removePlayer(id)
                        findViewById<android.widget.TextView>(R.id.tv_count)?.text = "Players: ${vpnService?.getPlayerCount() ?: 0}"
                    }
                }
            }
        }
    }

    private fun toggleVpn() {
        if (vpnService != null) {
            vpnService?.stopRun()
            vpnService = null
        } else {
            val intent = VpnService.prepare(this)
            if (intent != null) vpnPermission.launch(intent)
            else startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, AlbionVpnService::class.java)
        startService(intent)
        bindService(intent, svcConn, Context.BIND_AUTO_CREATE)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("radar", "Albion Radar", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (vpnBound) { unbindService(svcConn); vpnBound = false }
    }
}
