package com.albionplayersradar.vpn

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.albionplayersradar.data.Player
import com.albionplayersradar.parser.EventRouter
import com.albionplayersradar.ui.MainActivity
import java.io.FileInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class AlbionVpnService : Service() {

    private val binder = LocalBinder()
    private var running = false
    private var vpnFd: android.os.ParcelFileDescriptor? = null
    private var udpSocket: DatagramSocket? = null
    private var onUpdate: ((String) -> Unit)? = null

    companion object {
        private const val TAG = "AlbionVPN"
        private const val NOTIFY_ID = 1001
        private const val SERVER_IP = "5.45.187.219"
        private const val SERVER_PORT = 5056
        private const val LOCAL_IP = "10.0.0.2"
    }

    inner class LocalBinder : Binder() {
        fun getService() = this@AlbionVpnService
    }

    override fun onBind(intent: Intent?) = binder

    override fun onCreate() {
        super.onCreate()
        EventRouter.listener = object : EventRouter.PlayerCallback {
            override fun onPlayerJoined(player: Player) {
                val msg = "SPAWN:${player.id}|${player.name}|${player.guildName ?: ""}|${player.allianceName ?: ""}|${player.faction}"
                notifyUpdate(msg)
            }
            override fun onPlayerMoved(player: Player) {
                notifyUpdate("MOVE:${player.id}|${player.posX}|${player.posY}")
            }
            override fun onPlayerLeft(id: Long) { notifyUpdate("LEAVE:$id") }
            override fun onPlayerHealth(id: Long, current: Float, max: Float) { notifyUpdate("HEALTH:$id|$current|$max") }
            override fun onMountChanged(id: Long, mounted: Boolean) { notifyUpdate("MOUNT:$id|$mounted") }
            override fun onFactionChanged(id: Long, faction: Int) { notifyUpdate("FACTION:$id|$faction") }
            override fun onLocalPosition(x: Float, y: Float) {}
            override fun onZoneChanged(zoneId: String) { notifyUpdate("ZONE:$zoneId") }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFY_ID, makeNotification())
        startVpn()
        return START_STICKY
    }

    private fun makeNotification(): Notification {
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, MainApplication.CHANNEL_ID)
            .setContentTitle("Albion Players Radar")
            .setContentText("Radar active")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    fun setPlayerUpdateListener(cb: (String) -> Unit) { onUpdate = cb }

    private fun startVpn() {
        if (running) return
        running = true
        try {
            VpnService.prepare(this)?.let { return }
            val b = VpnService.Builder()
                .setSession("AlbionPlayersRadar")
                .addAddress(LOCAL_IP, 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .setMtu(1500)

            vpnFd = b.establish()
            if (vpnFd == null) { stopSelf(); return }

            udpSocket = DatagramSocket()
            protect(udpSocket!!)
            udpSocket!!.connect(InetAddress.getByName(SERVER_IP), SERVER_PORT)

            Thread { readLoop() }.start()
            Thread { proxyLoop() }.start()
            Log.d(TAG, "VPN started")
        } catch (e: Exception) {
            Log.e(TAG, "startVpn failed: ${e.message}")
            stopSelf()
        }
    }

    private fun readLoop() {
        val fd = vpnFd ?: return
        val inp = FileInputStream(fd.fileDescriptor)
        val buf = ByteArray(2048)
        while (running) {
            try {
                val n = inp.read(buf)
                if (n > 0) {
                    if (buf[10].toInt() and 0xFF == 17) {
                        val ihl = (buf[0].toInt() and 0x0F) * 4
                        val payloadLen = n - ihl - 8
                        if (payloadLen > 0) {
                            val payload = buf.copyOfRange(ihl + 8, ihl + 8 + payloadLen)
                            EventRouter.onPacket(payload)
                        }
                    }
                    val pkt = DatagramPacket(buf, n)
                    udpSocket?.send(pkt)
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "read: ${e.message}")
            }
        }
    }

    private fun proxyLoop() {
        val buf = ByteArray(2048)
        while (running) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                udpSocket?.receive(pkt)
            } catch (e: Exception) {}
        }
    }

    private fun notifyUpdate(msg: String) {
        onUpdate?.invoke(msg)
    }

    override fun onDestroy() {
        running = false
        vpnFd?.close()
        udpSocket?.close()
        super.onDestroy()
    }
}
