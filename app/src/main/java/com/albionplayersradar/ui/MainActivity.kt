package com.albionplayersradar.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.albionplayersradar.R
import com.albionplayersradar.vpn.AlbionVpnService

class MainActivity : Activity() {

    private var vpnService: AlbionVpnService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AlbionVpnService.LocalBinder
            vpnService = binder.getService()
            bound = true
            updateUI()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            vpnService = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btn_toggle).setOnClickListener {
            toggleVpn()
        }

        findViewById<Button>(R.id.btn_clear).setOnClickListener {
            vpnService?.clearPlayers()
            Toast.makeText(this, "Players cleared", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, AlbionVpnService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    private fun toggleVpn() {
        if (vpnService?.isRunning == true) {
            vpnService?.stop()
        } else {
            startService(Intent(this, AlbionVpnService::class.java))
            Intent(this, AlbionVpnService::class.java).also { intent ->
                bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }
        }
        updateUI()
    }

    private fun updateUI() {
        val status = if (vpnService?.isRunning == true) "ACTIVE" else "STOPPED"
        val count = vpnService?.playerCount ?: 0
        findViewById<TextView>(R.id.tv_status).text = "Status: $status | Players: $count"
        findViewById<Button>(R.id.btn_toggle).text = if (vpnService?.isRunning == true) "Stop Radar" else "Start Radar"
    }
}
