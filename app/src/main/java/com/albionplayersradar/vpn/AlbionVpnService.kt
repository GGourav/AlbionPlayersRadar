package com.albionplayersradar.vpn

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.FileDescriptor
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
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
    private var eventRouter: EventRouter? = null
    private val PHOTON_PORT = 5056
    private val SERVER_IP = "5.45.187.219"
    private val SERVER_PORT = 5056

    private val notification: Notification by lazy {
        NotificationCompat.Builder(this, "albion_vpn")
            .setContentTitle("Albion Players Radar")
            .setContentText("Scanning for players...")
            .setSmallIcon(R.drawable.ic_launcher)
            .build()
    }

    inner class LocalBinder : Binder() {
        fun getService(): AlbionVpnService = this@AlbionVpnService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        eventRouter = EventRouter()
        eventRouter?.setPlayerListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification)
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (running) return
        val vpn = VpnService.Builder()
            .setMtu(2048)
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .addAllowedApplication("com.albiononline")
        tunFd = vpn.establish()?.fileDescriptor
        if (tunFd == null) {
            Log.e("AlbionVpnService", "VPN establish returned null")
            return
        }
        proxySocket = DatagramSocket(PHOTON_PORT)
        proxySocket?.reuseAddress = true
        running = true
        readerThread = Thread { readLoop() }
        readerThread?.start()
    }

    private fun readLoop() {
        val buffer = ByteArray(32767)
        val packet = DatagramPacket(buffer, buffer.size)
        while (running) {
            try {
                proxySocket?.receive(packet)
                val data = packet.data
                val length = packet.length
                val destAddr = packet.address
                val destPort = packet.port
                // Write to TUN
                tunFd?.let { fd ->
                    try {
                        val out = FileDescriptorOutputStream(fd)
                        out.write(data, 0, length)
                        out.flush()
                    } catch (_: Exception) {}
                }
                // Handle outgoing packet through parser
                if (destAddr.hostAddress == SERVER_IP && destPort == SERVER_PORT) {
                    try {
                        val parser = PhotonPacketParser(eventRouter!!)
                        val eventCodes = parser.parsePacket(data, length)
                    } catch (_: Exception) {}
                }
                // Handle response
                val responseData = receiveFromServer()
                if (responseData != null && responseData.isNotEmpty()) {
                    try {
                        val parser = PhotonPacketParser(eventRouter!!)
                        parser.parsePacket(responseData, responseData.size)
                    } catch (_: Exception) {}
                    // Write response to TUN
                    tunFd?.let { fd ->
                        try {
                            val out = FileDescriptorOutputStream(fd)
                            out.write(responseData, 0, responseData.size)
                            out.flush()
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun receiveFromServer(): ByteArray? {
        return try {
            val buf = ByteArray(32767)
            val pkt = DatagramPacket(buf, buf.size)
            proxySocket?.receive(pkt)
            buf.copyOf(pkt.length)
        } catch (_: Exception) {
            null
        }
    }

    override fun onDestroy() {
        running = false
        readerThread?.interrupt()
        proxySocket?.close()
        super.onDestroy()
    }

    // EventRouter.PlayerListener implementation

    override fun onPlayerJoined(id: Long, name: String, guild: String, posX: Float, posY: Float, faction: Int) {
        Log.d("AlbionVpnService", "Player joined: $name ($guild) at ($posX, $posY) faction=$faction")
    }

    override fun onPlayerLeft(id: Long) {
        Log.d("AlbionVpnService", "Player left: $id")
    }

    override fun onPlayerMoved(id: Long, posX: Float, posY: Float) {
        Log.d("AlbionVpnService", "Player moved: $id to ($posX, $posY)")
    }

    override fun onPlayerHealthChanged(id: Long, currentHp: Float, maxHp: Float) {
        Log.d("AlbionVpnService", "Player $id health: $currentHp / $maxHp")
    }
}

private class FileDescriptorOutputStream(private val fd: FileDescriptor) : java.io.OutputStream() {
    override fun write(b: Int) {
        // Not used directly
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        try {
            java.io.FileOutputStream(fd).write(b, off, len)
        } catch (_: Exception) {}
    }

    override fun flush() {
        try {
            java.io.FileOutputStream(fd).flush()
        } catch (_: Exception) {}
    }
}
