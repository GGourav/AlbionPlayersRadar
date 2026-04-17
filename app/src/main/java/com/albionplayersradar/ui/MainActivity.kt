package com.albionplayersradar.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.albionplayersradar.R
import com.albionplayersradar.data.Player
import com.albionplayersradar.vpn.AlbionVpnService

class MainActivity : AppCompatActivity() {

    private var vpnBound = false
    private val players = mutableListOf<Player>()
    private var localX = 0f
    private var localY = 0f
    private var currentZone = ""

    private lateinit var tvStatus: TextView
    private lateinit var btnVpn: Button
    private lateinit var tvZone: TextView
    private lateinit var tvCount: TextView
    private lateinit var radarView: PlayerRendererView
    private lateinit var tvLog: TextView

    private val vpnConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {
            vpnBound = true
            setupVpnCallbacks()
            updateStatus(true)
        }
        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            vpnBound = false
            updateStatus(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.status_text)
        btnVpn = findViewById(R.id.btn_vpn_toggle)
        tvZone = findViewById(R.id.zone_text)
        tvCount = findViewById(R.id.count_text)
        radarView = findViewById(R.id.radar_view)
        tvLog = findViewById(R.id.log_text)

        btnVpn.setOnClickListener { toggleVpn() }
    }

    private fun toggleVpn() {
        if (vpnBound) {
            stopVpn()
        } else {
            val intent = android.net.VpnService.prepare(this)
            if (intent != null) {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, VPN_REQUEST_CODE)
            } else {
                startVpn()
            }
        }
    }

    private fun startVpn() {
        val intent = Intent(this, AlbionVpnService::class.java)
        startForegroundService(intent)
        bindService(intent, vpnConnection, BIND_AUTO_CREATE)
        tvStatus.text = "Starting..."
    }

    private fun stopVpn() {
        if (vpnBound) {
            unbindService(vpnConnection)
            vpnBound = false
        }
        stopService(Intent(this, AlbionVpnService::class.java))
        AlbionVpnService.onUpdate = null
        players.clear()
        radarView.updateData(0f, 0f, emptyList(), "")
        updateStatus(false)
    }

    private fun setupVpnCallbacks() {
        AlbionVpnService.onUpdate = { msg ->
            runOnUiThread {
                when {
                    msg.startsWith("JOIN:") -> {
                        val p = msg.substringAfter("JOIN:").split("|")
                        if (p.size >= 4) {
                            val id = p[0].toLongOrNull() ?: return@runOnUiThread
                            val name = p[1]
                            val guild = p[2]
                            val faction = p[3].toIntOrNull() ?: 0
                            val posX = p.getOrNull(4)?.toFloatOrNull() ?: 0f
                            val posY = p.getOrNull(5)?.toFloatOrNull() ?: 0f
                            if (players.none { it.id == id }) {
                                players.add(Player(id, name, guild, null, faction, posX, posY))
                                tvCount.text = "Players: ${players.size}"
                                addLog("→ $name [$guild]")
                                radarView.updateData(localX, localY, players.toList(), currentZone)
                            }
                        }
                    }
                    msg.startsWith("ZONE:") -> {
                        currentZone = msg.substringAfter("ZONE:")
                        tvZone.text = "Zone: $currentZone"
                        players.clear()
                        tvCount.text = "Players: 0"
                        addLog("Zone: $currentZone")
                        radarView.updateData(localX, localY, emptyList(), currentZone)
                    }
                    msg.startsWith("LOCAL:") -> {
                        val c = msg.substringAfter("LOCAL:").split("|")
                        if (c.size >= 2) {
                            localX = c[0].toFloatOrNull() ?: 0f
                            localY = c[1].toFloatOrNull() ?: 0f
                            radarView.updateData(localX, localY, players.toList(), currentZone)
                        }
                    }
                    msg.startsWith("MOVE:") -> {
                        val p = msg.substringAfter("MOVE:").split("|")
                        if (p.size >= 3) {
                            val id = p[0].toLongOrNull() ?: return@runOnUiThread
                            val posX = p[1].toFloatOrNull() ?: 0f
                            val posY = p[2].toFloatOrNull() ?: 0f
                            players.find { it.id == id }?.let {
                                it.posX = posX
                                it.posY = posY
                                radarView.updateData(localX, localY, players.toList(), currentZone)
                            }
                        }
                    }
                    msg.startsWith("LEAVE:") -> {
                        val id = msg.substringAfter("LEAVE:").toLongOrNull()
                        if (id != null) {
                            players.removeAll { it.id == id }
                            tvCount.text = "Players: ${players.size}"
                            radarView.updateData(localX, localY, players.toList(), currentZone)
                        }
                    }
                    msg.startsWith("HEALTH:") -> {
                        val p = msg.substringAfter("HEALTH:").split("|")
                        if (p.size >= 3) {
                            val id = p[0].toLongOrNull() ?: return@runOnUiThread
                            val cur = p[1].toIntOrNull() ?: 0
                            val max = p[2].toIntOrNull() ?: 1
                            players.find { it.id == id }?.let {
                                it.currentHealth = cur
                                it.maxHealth = max
                            }
                        }
                    }
                    msg.startsWith("MOUNT:") -> {
                        val p = msg.substringAfter("MOUNT:").split("|")
                        if (p.size >= 2) {
                            val id = p[0].toLongOrNull() ?: return@runOnUiThread
                            val mounted = p[1] == "true"
                            players.find { it.id == id }?.isMounted = mounted
                        }
                    }
                }
            }
        }
    }

    private fun updateStatus(running: Boolean) {
        tvStatus.text = if (running) "Radar active" else "Tap START to activate"
        btnVpn.text = if (running) "STOP RADAR" else "START RADAR"
    }

    private fun addLog(msg: String) {
        tvLog.text = "$msg\n${tvLog.text}".lines().take(20).joinToString("\n")
    }

    @Deprecated("Using for VPN permission result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) startVpn()
            else Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (vpnBound) {
            unbindService(vpnConnection)
            vpnBound = false
        }
        AlbionVpnService.onUpdate = null
    }

    companion object {
        private const val VPN_REQUEST_CODE = 1001
    }
}
