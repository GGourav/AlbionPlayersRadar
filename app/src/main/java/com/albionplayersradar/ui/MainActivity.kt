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
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.albionplayersradar.R
import com.albionplayersradar.data.Player
import com.albionplayersradar.vpn.AlbionVpnService
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private var vpnService: AlbionVpnService? = null
    private var vpnBound = false

    private lateinit var renderer: PlayerRendererView
    private lateinit var statusText: TextView
    private lateinit var countText: TextView
    private lateinit var zoneText: TextView
    private lateinit var logText: TextView
    private lateinit var toggleBtn: Button

    private val logs = mutableListOf<String>()
    private var scale = 50f
    private var showPassive = true
    private var showFaction = true
    private var showHostile = true
    private var showGuild = true
    private var showHealth = true
    private var showDist = false
    private var pvpType = "safe"

    private val svcConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as AlbionVpnService.LocalBinder
            vpnService = b.getService()
            vpnBound = true
            setupCallbacks()
            addLog("VPN connected")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            vpnService = null
            vpnBound = false
        }
    }

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) startVpnService() else Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        setupUI()
    }

    private fun setupUI() {
        val root = LinearLayout(this).apply { orientation = VERTICAL; setBackgroundColor(Color.parseColor("#111111")) }

        val topBar = LinearLayout(this).apply { orientation = HORIZONTAL; setPadding(16, 16, 16, 8); setBackgroundColor(Color.parseColor("#2d2d2d")) }

        toggleBtn = Button(this).apply { text = "Start Radar"; setOnClickListener { toggleVpn() } }
        zoneText = TextView(this).apply { text = "Zone: --"; setTextColor(Color.WHITE); setPadding(16, 0, 16, 0) }
        countText = TextView(this).apply { text = "Players: 0"; setTextColor(Color.parseColor("#00ff88")) }

        topBar.addView(toggleBtn)
        topBar.addView(zoneText)
        topBar.addView(countText)

        renderer = PlayerRendererView(this)
        renderer.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)

        val logScroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#111111")) }
        logText = TextView(this).apply { setTextColor(Color.parseColor("#aaaaaa")); textSize = 10f; setPadding(12, 8, 12, 8) }
        logScroll.addView(logText)

        statusText = TextView(this).apply { text = "Tap START to activate"; setTextColor(Color.parseColor("#666666")); textSize = 12f; setPadding(16, 4, 16, 8) }

        root.addView(topBar)
        root.addView(renderer)
        root.addView(logScroll)
        root.addView(statusText)
        setContentView(root)
    }

    private fun toggleVpn() {
        if (vpnService != null) stopVpn() else {
            val intent = VpnService.prepare(this)
            if (intent != null) vpnPermission.launch(intent) else startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, AlbionVpnService::class.java)
        startForegroundService(intent)
        bindService(intent, svcConnection, Context.BIND_AUTO_CREATE)
        toggleBtn.text = "Stop Radar"
        statusText.text = "Radar active..."
        statusText.setTextColor(Color.parseColor("#00ff88"))
        addLog("Radar started")
    }

    private fun stopVpn() {
        if (vpnBound) { unbindService(svcConnection); vpnBound = false }
        stopService(Intent(this, AlbionVpnService::class.java))
        vpnService = null
        toggleBtn.text = "Start Radar"
        statusText.text = "Radar stopped"
        statusText.setTextColor(Color.parseColor("#666666"))
        addLog("Radar stopped")
    }

    private fun setupCallbacks() {
        vpnService?.setPlayerUpdateListener { data ->
            runOnUiThread {
                val parts = data.split(":", limit = 2)
                if (parts.size < 2) return@runOnUiThread
                when (parts[0]) {
                    "SPAWN" -> {
                        val p = parts[1].split("|")
                        if (p.size >= 5) {
                            val player = Player(
                                id = p[0].toLongOrNull() ?: return@runOnUiThread,
                                name = p[1],
                                guildName = p[2].takeIf { it.isNotEmpty() },
                                allianceName = p[3].takeIf { it.isNotEmpty() },
                                faction = p[4].toIntOrNull() ?: 0,
                                posX = 0f, posY = 0f, posZ = 0f,
                                currentHealth = 0, maxHealth = 0,
                                isMounted = false
                            )
                            addLog("Player: ${player.name} (F:${player.faction})")
                            updateCount()
                        }
                    }
                    "ZONE" -> {
                        zoneText.text = "Zone: ${parts[1]}"
                        pvpType = com.albionplayersradar.data.ZonesDatabase.getPvpType(parts[1])
                        renderer.setPvPType(pvpType)
                        addLog("Zone: ${parts[1]} [$pvpType]")
                    }
                    "LEAVE" -> { addLog("Player left: ${parts[1]}"); updateCount() }
                    "MOVE" -> updateCount()
                    "HEALTH" -> updateCount()
                }
            }
        }
    }

    private fun updateCount() {
        val players = vpnService?.parser?.getAllPlayers() ?: return
        countText.text = "Players: ${players.size}"
        renderer.updateData(
            vpnService?.parser?.getLocalPosition()?.first ?: 0f,
            vpnService?.parser?.getLocalPosition()?.second ?: 0f,
            players,
            vpnService?.parser?.getCurrentZone() ?: ""
        )
    }

    private fun addLog(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logs.add(0, "[$t] $msg")
        if (logs.size > 50) logs.removeAt(logs.size - 1)
        logText.text = logs.joinToString("\n")
    }

    override fun onDestroy() {
        if (vpnBound) { unbindService(svcConnection); vpnBound = false }
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(m: Menu): Boolean {
        m.add(0, 1, 0, "Settings")
        m.add(0, 2, 0, "Scale +")
        m.add(0, 3, 0, "Scale -")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> showSettings()
            2 -> { scale *= 1.2f; renderer.setScale(scale) }
            3 -> { scale /= 1.2f; renderer.setScale(scale) }
        }
        return true
    }

    private fun showSettings() {
        val panel = LinearLayout(this).apply { orientation = VERTICAL; setPadding(24, 24, 24, 24) }

        fun sw(label: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch {
            val s = Switch(this).apply {
                text = label; isChecked = checked; setTextColor(Color.WHITE)
                setOnCheckedChangeListener { _, v -> onChange(v) }
            }
            panel.addView(s)
            return s
        }

        sw("Passive (Green)", showPassive) { showPassive = it; renderer.setShowPassive(it) }
        sw("Faction (Orange)", showFaction) { showFaction = it; renderer.setShowFaction(it) }
        sw("Hostile (Red)", showHostile) { showHostile = it; renderer.setShowHostile(it) }
        sw("Guild Names", showGuild) { showGuild = it; renderer.setShowGuild(it) }
        sw("Health Bars", showHealth) { showHealth = it; renderer.setShowHealth(it) }
        sw("Distance", showDist) { showDist = it; renderer.setShowDistance(it) }

        val scroll = ScrollView(this)
        scroll.addView(panel)
        AlertDialog.Builder(this).setTitle("Settings").setView(scroll).setPositiveButton("Done", null).show()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("radar_channel", "Albion Radar", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
