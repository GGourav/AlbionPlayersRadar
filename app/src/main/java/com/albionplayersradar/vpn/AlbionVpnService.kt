package com.albionplayersradar.vpn

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.FileDescriptor
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.albionplayersradar.R
import com.albionplayersradar.parser.EventRouter
import com.albionplayersradar.ui.MainActivity
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class AlbionVpnService : Service(), EventRouter.PlayerListener {

    private var running = false
    private var readerThread: Thread? = null
    private var proxySocket: DatagramSocket? = null
    private var tunFd: FileDescriptor? = null
    private var playerListener: EventRouter.PlayerListener? = null
    private var eventRouter: EventRouter? = null
    private val PHOTON_PORT = 5056
    private val SERVER_IP = "5.45.187.219"

    private val notification: Notification by lazy {
        val channel = NotificationCompat.Builder(this, "albion_vpn")
            .build()
        NotificationCompat.Builder(this, "albion_vpn")
            .setContentTitle("Albion Players Radar")
            .setContentText("Scanning for players...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun startVpn() {
        if (running) return
        try {
            val vpn = android.net.VpnService.Builder()
                .setMtu(2048)
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addAllowedApplication("com.albiononline")
            tunFd = vpn.establish()?.fileDescriptor
            if (tunFd == null) {
                Log.e(TAG, "VPN establish returned null")
                return
            }
            proxySocket = DatagramSocket(PHOTON_PORT)
            proxySocket?.reuseAddress = true
            running = true
            readerThread = Thread { readLoop() }
            readerThread?.start()
            Log.i(TAG, "VPN started")
        } catch (e: Exception) {
            Log.e(TAG, "startVpn error: ${e.message}")
        }
    }

    private fun readLoop() {
        val buffer = ByteArray(65535)
        val socket = proxySocket ?: return
        while (running && !Thread.currentThread().isInterrupted) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val payload = packet.data.copyOf(packet.length)
                eventRouter?.onPacketReceived(payload)
            } catch (e: Exception) {
                if (running) Log.e(TAG, "readLoop error: ${e.message}")
            }
        }
    }

    fun stopVpn() {
        running = false
        readerThread?.interrupt()
        proxySocket?.close()
        tunFd = null
        Log.i(TAG, "VPN stopped")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification)
        startVpn()
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private val binder = object : Binder() {
        fun getService(): AlbionVpnService = this@AlbionVpnService
    }

    fun setPlayerListener(listener: EventRouter.PlayerListener) {
        playerListener = listener
    }

    override fun onPlayerJoined(id: Long, name: String?, guild: String?, posX: Float, posY: Float, faction: Int) {
        playerListener?.onPlayerJoined(id, name, guild, posX, posY, faction)
    }

    override fun onPlayerLeft(id: Long) {
        playerListener?.onPlayerLeft(id)
    }

    override fun onPlayerMoved(id: Long, posX: Float, posY: Float) {
        playerListener?.onPlayerMoved(id, posX, posY)
    }

    override fun onPlayerHealthChanged(id: Long, currentHp: Float, maxHp: Float) {
        playerListener?.onPlayerHealthChanged(id, currentHp, maxHp)
    }

    companion object {
        const val TAG = "AlbionVpnService"
    }
}
