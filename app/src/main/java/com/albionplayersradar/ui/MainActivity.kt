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
import android.os.Handler
import android.os.Looper
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
import com.albionplayersradar.vpn.AlbionVpnService
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), EventRouter.PlayerListener {

    private val players = mutableListOf<Player>()
    private var vpnService: AlbionVpnService? = null
    private var vpnBound = false
    private var radarView: RadarView? = null
    private var vpnButton: Button? = null
    private var tvPlayerCount: TextView? = null
    private var tvZone: TextView? = null
    private var isVpnRunning = false

    private val handler = Handler(Looper.getMainLooper())
    private var refreshRunnable: Runnable? = null

    private val vpnConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AlbionVpnService.LocalBinder
            vpnService = binder.getService()
            vpnBound = true
            vpnService?.eventRouter?.setPlayerListener(this@MainActivity)
            updateVpnButton()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            vpnBound = false
            vpnService = null
        }
    }

    private val permissionLauncher = registerForActivityResult(
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
        setupViews()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel("radar_channel", "Albion Radar", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun setupViews() {
        vpnButton = findViewById(R.id.btn_vpn)
        tvPlayerCount = findViewById(R.id.tv_player_count)
        tvZone = findViewById(R.id.tv_zone)

        val container = findViewById<FrameLayout>(R.id.radar_container)

        radarView = RadarView(this)
        container.addView(radarView)

        vpnButton?.setOnClickListener { toggleVpn() }
        findViewById<Button>(R.id.btn_clear)?.setOnClickListener { clearPlayers() }

        refreshRunnable = object : Runnable {
            override fun run() {
                radarView?.invalidate()
                handler.postDelayed(this, 100)
            }
        }
        handler.post(refreshRunnable!!)
    }

    private fun toggleVpn() {
        if (isVpnRunning) {
            stopVpnService()
        } else {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                permissionLauncher.launch(intent)
            } else {
                startVpnService()
            }
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, AlbionVpnService::class.java)
        startService(intent)
        bindService(Intent(this, AlbionVpnService::class.java), vpnConnection, Context.BIND_AUTO_CREATE)
        isVpnRunning = true
        updateVpnButton()
    }

    private fun stopVpnService() {
        vpnService?.stopRun()
        if (vpnBound) {
            unbindService(vpnConnection)
            vpnBound = false
        }
        stopService(Intent(this, AlbionVpnService::class.java))
        isVpnRunning = false
        updateVpnButton()
    }

    private fun updateVpnButton() {
        vpnButton?.text = if (isVpnRunning) "Stop VPN" else "Start VPN"
        vpnButton?.setBackgroundColor(
            if (isVpnRunning) Color.parseColor("#EF4444") else Color.parseColor("#10B981")
        )
    }

    private fun clearPlayers() {
        players.clear()
        radarView?.invalidate()
        tvPlayerCount?.text = "Players: 0"
    }

    override fun onPlayerJoined(id: Long, name: String, guild: String, posX: Float, posY: Float, posZ: Float, faction: Int) {
        runOnUiThread {
            val p = Player(id.toInt(), name, guild, faction, posX, posY)
            players.removeAll { it.id == p.id }
            players.add(p)
            radarView?.invalidate()
            tvPlayerCount?.text = "Players: ${players.size}"
        }
    }

    override fun onPlayerLeft(id: Long) {
        runOnUiThread {
            players.removeAll { it.id == id.toInt() }
            radarView?.invalidate()
            tvPlayerCount?.text = "Players: ${players.size}"
        }
    }

    override fun onPlayerMoved(id: Long, posX: Float, posY: Float, posZ: Float) {
        runOnUiThread {
            players.find { it.id == id.toInt() }?.apply {
                this.posX = posX
                this.posY = posY
            }
            radarView?.invalidate()
        }
    }

    override fun onPlayerHealthChanged(id: Long, currentHp: Float, maxHp: Float) {}

    override fun onDestroy() {
        super.onDestroy()
        refreshRunnable?.let { handler.removeCallbacks(it) }
        if (vpnBound) {
            unbindService(vpnConnection)
            vpnBound = false
        }
    }

    inner class RadarView(context: Context?) : View(context) {
        private val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
        }
        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 24f
            textAlign = Paint.Align.CENTER
        }
        private val circlePaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        private val scale = 30f
        private val radarRadius = 280f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.parseColor("#1a1a2e"))

            val cx = width / 2f
            val cy = height / 2f

            circlePaint.color = Color.parseColor("#333366")
            canvas.drawCircle(cx, cy, radarRadius, circlePaint)
            canvas.drawCircle(cx, cy, radarRadius / 2, circlePaint)
            canvas.drawLine(cx - radarRadius, cy, cx + radarRadius, cy, circlePaint)
            canvas.drawLine(cx, cy - radarRadius, cx, cy + radarRadius, circlePaint)

            paint.color = Color.parseColor("#10B981")
            canvas.drawCircle(cx, cy, 8f, paint)

            for (player in players) {
                val dx = player.posX / scale
                val dy = player.posY / scale

                val sx = cx + dx
                val sy = cy - dy

                if (abs(sx - cx) > radarRadius || abs(sy - cy) > radarRadius) continue

                val color = when {
                    player.faction == 255 -> Color.parseColor("#EF4444")
                    player.faction in 1..6 -> Color.parseColor("#F59E0B")
                    else -> Color.parseColor("#3B82F6")
                }
                paint.color = color
                canvas.drawCircle(sx, sy, 10f, paint)

                textPaint.color = Color.WHITE
                canvas.drawText(player.name, sx, sy - 14f, textPaint)
            }
        }
    }

    data class Player(
        val id: Int,
        val name: String,
        val guild: String,
        val faction: Int,
        var posX: Float = 0f,
        var posY: Float = 0f
    )
}
