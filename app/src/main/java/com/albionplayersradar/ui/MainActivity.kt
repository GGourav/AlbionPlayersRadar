package com.albionplayersradar.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.albionplayersradar.MainApplication
import com.albionplayersradar.R
import com.albionplayersradar.data.Player
import com.albionplayersradar.data.ZonesDatabase
import com.albionplayersradar.vpn.AlbionVpnService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var vpnService: AlbionVpnService? = null
    private var vpnBound = false

    private lateinit var tvZone: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvLog: TextView
    private lateinit var radarContainer: FrameLayout
    private lateinit var renderer: PlayerRendererView

    private var localX = 0f
    private var localY = 0f
    private var currentZone = ""
    private val players = mutableMapOf<Long, Player>()
    private val logs = mutableListOf<String>()

    // ── Broadcast receiver ─────────────────────────────────────────────────

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PLAYER_JOINED -> onPlayerJoined(intent)
                ACTION_PLAYER_MOVED -> onPlayerMoved(intent)
                ACTION_PLAYER_LEFT -> onPlayerLeft(intent)
                ACTION_HEALTH -> onHealth(intent)
                ACTION_LOCAL_MOVE -> onLocalMove(intent)
                ACTION_ZONE -> onZone(intent)
            }
        }
    }

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) startVpn() else Toast.makeText(this, "VPN denied", Toast.LENGTH_SHORT).show()
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        createNotificationChannel()
        bindUI()
    }

    private fun bindUI() {
        tvZone = findViewById(R.id.tv_zone)
        tvCount = findViewById(R.id.tv_count)
        tvLog = findViewById(R.id.tv_log)
        radarContainer = findViewById(R.id.radar_view)

        renderer = PlayerRendererView(this)
        radarContainer.addView(renderer)

        findViewById<Button>(R.id.btn_vpn).setOnClickListener { toggleVpn() }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, receiver, IntentFilter().apply {
            actions.forEach { addAction(it) }
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(receiver) } catch (e: Exception) {}
    }

    private fun toggleVpn() {
        if (vpnService?.isRunning() == true) {
            stopVpn()
        } else {
            val prep = VpnService.prepare(this)
            if (prep != null) vpnPermission.launch(prep) else startVpn()
        }
    }

    private fun startVpn() {
        startForegroundService(Intent(this, AlbionVpnService::class.java))
        bindService(Intent(this, AlbionVpnService::class.java), svcConn, Context.BIND_AUTO_CREATE)
        findViewById<Button>(R.id.btn_vpn).text = "Stop Radar"
        addLog("Starting...")
    }

    private fun stopVpn() {
        if (vpnBound) { unbindService(svcConn); vpnBound = false }
        vpnService?.stopRun()
        vpnService = null
        players.clear()
        logs.clear()
        tvLog.text = ""
        tvCount.text = "Players: 0"
        tvZone.text = "Zone: --"
        renderer.updateData(0f, 0f, emptyList(), "")
        findViewById<Button>(R.id.btn_vpn).text = "Start Radar"
        addLog("Stopped")
    }

    private val svcConn = object : android.content.ServiceConnection {
        override fun onServiceConnected(n: android.content.ComponentName?, b: android.os.IBinder?) {
            vpnService = (b as AlbionVpnService.LocalBinder).getService()
            vpnBound = true
            addLog("Connected")
        }
        override fun onServiceDisconnected(n: android.content.ComponentName?) {
            vpnService = null; vpnBound = false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(MainApplication.CHANNEL_ID, "Radar", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (vpnBound) { unbindService(svcConn); vpnBound = false }
    }

    // ── Event handlers ──────────────────────────────────────────────────────

    private fun onPlayerJoined(intent: Intent) {
        val p = Player(
            id = intent.getLongExtra(EXTRA_ID, -1),
            name = intent.getStringExtra(EXTRA_NAME) ?: return,
            guildName = intent.getStringExtra(EXTRA_GUILD),
            allianceName = intent.getStringExtra(EXTRA_ALLIANCE),
            faction = intent.getIntExtra(EXTRA_FACTION, 0),
            posX = intent.getFloatExtra(EXTRA_X, 0f),
            posY = intent.getFloatExtra(EXTRA_Y, 0f),
            posZ = intent.getFloatExtra(EXTRA_Z, 0f),
            currentHealth = intent.getIntExtra(EXTRA_HEALTH, 0),
            maxHealth = intent.getIntExtra(EXTRA_MAX_HEALTH, 1),
            isMounted = intent.getBooleanExtra(EXTRA_MOUNTED, false)
        )
        players[p.id] = p
        tvCount.text = "Players: ${players.size}"
        addLog("+ ${p.name}${p.guildName?.let { " [$it]" } ?: ""}")
        refresh()
    }

    private fun onPlayerMoved(intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1)
        val x = intent.getFloatExtra(EXTRA_X, 0f)
        val y = intent.getFloatExtra(EXTRA_Y, 0f)
        players[id]?.let { players[id] = it.copy(posX = x, posY = y) }
        refresh()
    }

    private fun onPlayerLeft(intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1)
        players.remove(id)?.let {
            tvCount.text = "Players: ${players.size}"
            addLog("- ${it.name} left")
        }
        refresh()
    }

    private fun onHealth(intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1)
        players[id]?.let {
            players[id] = it.copy(
                currentHealth = intent.getIntExtra(EXTRA_HEALTH, 0),
                maxHealth = intent.getIntExtra(EXTRA_MAX_HEALTH, 1)
            )
        }
    }

    private fun onLocalMove(intent: Intent) {
        localX = intent.getFloatExtra(EXTRA_X, 0f)
        localY = intent.getFloatExtra(EXTRA_Y, 0f)
        refresh()
    }

    private fun onZone(intent: Intent) {
        currentZone = intent.getStringExtra(EXTRA_ZONE) ?: return
        val zi = ZonesDatabase.getZone(currentZone)
        tvZone.text = "Zone: ${zi?.name ?: currentZone} (${zi?.pvpType ?: "?"})"
        renderer.setPvPType(zi?.pvpType ?: "safe")
        players.clear()
        tvCount.text = "Players: 0"
        addLog(">> ${zi?.name ?: currentZone}")
        refresh()
    }

    private fun refresh() {
        renderer.updateData(localX, localY, players.values.toList(), currentZone)
    }

    private fun addLog(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logs.add(0, "[$t] $msg")
        if (logs.size > 50) logs.removeAt(logs.size - 1)
        tvLog.text = logs.joinToString("\n")
    }

    // ── Companion (broadcast helpers — called from VPN service) ────────────

    companion object {
        const val CHANNEL_ID = "albion_vpn_channel"
        const val ACTION_PLAYER_JOINED = "com.albionplayersradar.PLAYER_JOINED"
        const val ACTION_PLAYER_MOVED = "com.albionplayersradar.PLAYER_MOVED"
        const val ACTION_PLAYER_LEFT = "com.albionplayersradar.PLAYER_LEFT"
        const val ACTION_HEALTH = "com.albionplayersradar.HEALTH"
        const val ACTION_LOCAL_MOVE = "com.albionplayersradar.LOCAL_MOVE"
        const val ACTION_ZONE = "com.albionplayersradar.ZONE"

        const val EXTRA_ID = "id"; const val EXTRA_NAME = "name"
        const val EXTRA_GUILD = "guild"; const val EXTRA_ALLIANCE = "alliance"
        const val EXTRA_FACTION = "faction"; const val EXTRA_X = "x"
        const val EXTRA_Y = "y"; const val EXTRA_Z = "z"
        const val EXTRA_HEALTH = "health"; const val EXTRA_MAX_HEALTH = "max_health"
        const val EXTRA_MOUNTED = "mounted"; const val EXTRA_ZONE = "zone"

        val actions = listOf(ACTION_PLAYER_JOINED, ACTION_PLAYER_MOVED, ACTION_PLAYER_LEFT,
            ACTION_HEALTH, ACTION_LOCAL_MOVE, ACTION_ZONE)

        fun broadcastPlayerJoined(p: Player) = sendBroadcast(Intent(ACTION_PLAYER_JOINED).apply {
            putExtra(EXTRA_ID, p.id); putExtra(EXTRA_NAME, p.name)
            putExtra(EXTRA_GUILD, p.guildName); putExtra(EXTRA_ALLIANCE, p.allianceName)
            putExtra(EXTRA_FACTION, p.faction)
            putExtra(EXTRA_X, p.posX); putExtra(EXTRA_Y, p.posY); putExtra(EXTRA_Z, p.posZ)
            putExtra(EXTRA_HEALTH, p.currentHealth.toFloat())
            putExtra(EXTRA_MAX_HEALTH, p.maxHealth.toFloat())
            putExtra(EXTRA_MOUNTED, p.isMounted)
            setPackage("com.albionplayersradar")
        })

        fun broadcastPlayerLeave(id: Long) = sendBroadcast(Intent(ACTION_PLAYER_LEFT).apply {
            putExtra(EXTRA_ID, id); setPackage("com.albionplayersradar")
        })

        fun broadcastHealth(id: Long, h: Float, mh: Float) = sendBroadcast(Intent(ACTION_HEALTH).apply {
            putExtra(EXTRA_ID, id); putExtra(EXTRA_HEALTH, h); putExtra(EXTRA_MAX_HEALTH, mh)
            setPackage("com.albionplayersradar")
        })

        fun broadcastLocalMove(x: Float, y: Float) = sendBroadcast(Intent(ACTION_LOCAL_MOVE).apply {
            putExtra(EXTRA_X, x); putExtra(EXTRA_Y, y); setPackage("com.albionplayersradar")
        })

        fun broadcastZone(zoneId: String) = sendBroadcast(Intent(ACTION_ZONE).apply {
            putExtra(EXTRA_ZONE, zoneId); setPackage("com.albionplayersradar")
        })
    }
}
