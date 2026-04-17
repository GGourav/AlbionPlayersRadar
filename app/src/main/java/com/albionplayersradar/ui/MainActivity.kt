package com.albionplayersradar.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.albionplayersradar.R
import com.albionplayersradar.data.Player
import com.albionplayersradar.vpn.AlbionVpnService

class MainActivity : AppCompatActivity(), AlbionVpnService.PlayerCallback {

    private var vpnService: AlbionVpnService? = null
    private var vpnBound = false
    private lateinit var renderer: PlayerRendererView
    private lateinit var statusText: TextView
    private var localId: Long = -1
    private var localX: Float = 0f
    private var localY: Float = 0f
    private val playerList = mutableListOf<Player>()

    private val vpnConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as AlbionVpnService.LocalBinder
            vpnService = b.getService()
            vpnBound = true
            vpnService?.setCallback(this@MainActivity)
            runOnUiThread { statusText.text = "Radar active - ${vpnService?.playerCount ?: 0} players" }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            vpnService = null
            vpnBound = false
        }
    }

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) startVpnService()
        else Toast.makeText(this, "VPN permission required", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        createNotificationChannel()

        renderer = findViewById(R.id.radar_view)
        statusText = findViewById(R.id.status_text)
        val vpnButton = findViewById<Button>(R.id.btn_vpn)

        vpnButton.setOnClickListener {
            if (vpnService != null) {
                vpnService?.stopRun()
                vpnService = null
                statusText.text = "Radar stopped"
            } else {
                val intent = VpnService.prepare(this)
                if (intent != null) vpnPermission.launch(intent)
                else startVpnService()
            }
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, AlbionVpnService::class.java)
        startForegroundService(intent)
        bindService(intent, vpnConnection, Context.BIND_AUTO_CREATE)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("radar_channel", "Albion Radar", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onPlayerJoined(player: Player) {
        if (player.id == localId) return
        runOnUiThread {
            playerList.removeAll { it.id == player.id }
            playerList.add(player)
            renderer.updateData(localX, localY, playerList.toList(), "")
            statusText.text = "Players: ${playerList.size}"
        }
    }

    override fun onPlayerMoved(id: Long, posX: Float, posY: Float) {
        runOnUiThread {
            playerList.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { idx ->
                val p = playerList[idx]
                playerList[idx] = Player(p.id, p.name, p.guildName, p.allianceName, p.faction, posX, posY, p.posZ, p.currentHealth, p.maxHealth, p.isMounted, p.detectedAt)
            }
            renderer.updateData(localX, localY, playerList.toList(), "")
        }
    }

    override fun onPlayerLeft(id: Long) {
        runOnUiThread {
            playerList.removeAll { it.id == id }
            renderer.updateData(localX, localY, playerList.toList(), "")
            statusText.text = "Players: ${playerList.size}"
        }
    }

    override fun onLocalPlayerUpdate(id: Long, posX: Float, posY: Float) {
        localId = id; localX = posX; localY = posY
        runOnUiThread { renderer.updateData(localX, localY, playerList.toList(), "") }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (vpnBound) { unbindService(vpnConnection); vpnBound = false }
    }
}
