package com.albionplayersradar.ui

import android.Manifest
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
import com.albionplayersradar.parser.EventRouter
import com.albionplayersradar.parser.PhotonPacketParser
import com.albionplayersradar.vpn.AlbionVpnService

class MainActivity : AppCompatActivity(), EventRouter.PlayerListener {

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
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            vpnService = null
            vpnBound = false
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        setContentView(R.layout.activity_main)

        vpnButton = findViewById(R.id.btn_vpn)
        statusText = findViewById(R.id.status_text)
        radarView = findViewById(R.id.radar_view)

        vpnButton?.setOnClickListener { toggleVpn() }
        updateStatus("Tap button to start radar")
    }

    private fun toggleVpn() {
        if (vpnBound) {
            vpnService?.stopVpn()
            vpnBound = false
            updateStatus("VPN stopped")
        } else {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                vpnPermissionLauncher.launch(intent)
            } else {
                startVpnService()
            }
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, AlbionVpnService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        vpnService?.setPlayerListener(this)
        updateStatus("VPN started — connecting...")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Albion Radar",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun updateStatus(msg: String) {
        runOnUiThread { statusText?.text = msg }
    }

    override fun onPlayerJoined(id: Long, name: String, guild: String, posX: Float, posY: Float, faction: Int) {
        radarView?.addPlayer(id, name, guild, posX, posY, faction)
        runOnUiThread {
            statusText?.text = "Players: ${radarView?.playerCount ?: 0}"
        }
    }

    override fun onPlayerLeft(id: Long) {
        radarView?.removePlayer(id)
    }

    override fun onPlayerMoved(id: Long, posX: Float, posY: Float) {
        radarView?.updatePlayer(id, posX, posY)
    }

    override fun onPlayerHealthChanged(id: Long, currentHp: Float, maxHp: Float) {
        radarView?.updateHealth(id, currentHp, maxHp)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (vpnBound) {
            unbindService(serviceConnection)
            vpnBound = false
        }
    }

    class RadarView @JvmOverloads constructor(
        context: Context,
        attrs: android.util.AttributeSet? = null
    ) : View(context, attrs) {

        private val players = mutableMapOf<Long, PlayerDot>()
        private val playerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
        }
        private val localPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.YELLOW
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        var localX = 0f
        var localY = 0f
        val playerCount: Int get() = players.size

        data class PlayerDot(
            var name: String,
            var guild: String,
            var x: Float,
            var y: Float,
            var faction: Int
        )

        fun addPlayer(id: Long, name: String, guild: String, x: Float, y: Float, faction: Int) {
            players[id] = PlayerDot(name, guild, x, y, faction)
            invalidate()
        }

        fun removePlayer(id: Long) {
            players.remove(id)
            invalidate()
        }

        fun updatePlayer(id: Long, x: Float, y: Float) {
            players[id]?.let {
                it.x = x
                it.y = y
                invalidate()
            }
        }

        fun updateHealth(id: Long, currentHp: Float, maxHp: Float) {
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val scale = 50f

            // Draw local player ring
            canvas.drawCircle(cx, cy, 20f, localPaint)

            // Draw grid
            val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(40, 255, 255, 255)
                strokeWidth = 1f
                style = Paint.Style.STROKE
            }
            for (r in 50..(minOf(width, height) / 2) step 50) {
                canvas.drawCircle(cx, cy, r.toFloat(), gridPaint)
            }

            // Draw players
            players.forEach { (_, player) ->
                val dx = (player.x - localX) * scale
                val dy = (player.y - localY) * scale
                val px = cx + dx
                val py = cy - dy

                if (px < 0 || px > width || py < 0 || py > height) return@forEach

                val color = when {
                    player.faction == 255 -> Color.RED
                    player.faction in 1..6 -> Color.rgb(255, 165, 0)
                    else -> Color.rgb(0, 255, 136)
                }
                playerPaint.color = color
                canvas.drawCircle(px, py, 12f, playerPaint)
                canvas.drawText(player.name.take(8), px + 14, py + 8, textPaint)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "albion_radar_channel"
    }
}
