package com.albionplayersradar.vpn

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.albionplayersradar.parser.EventRouter
import com.albionplayersradar.parser.PhotonPacketParser
import com.albionplayersradar.ui.MainActivity
import java.io.FileInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class AlbionVpnService : Service(), EventRouter.PlayerCallback {

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
        fun getService(): AlbionVpnService = this@AlbionVpnService
    }
    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        EventRouter.setCallback(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFY_ID, makeNotification())
        startVpn()
        return START_STICKY
    }

    private fun makeNotification(): Notification {
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, "radar_channel")
            .setContentTitle("Albion Players Radar")
            .setContentText("Active")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    fun setUpdateListener(cb: (String) -> Unit) {
        onUpdate = cb
    }

    private fun startVpn() {
        if (running) return
        val prepared = VpnService.prepare(this)
        if (prepared != null) { stopSelf(); return }

        try {
            val builder = VpnService.Builder()
                .setSession("AlbionPlayersRadar")
                .addAddress(LOCAL_IP, 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .setMtu(1500)

            vpnFd = builder.establish()
            if (vpnFd == null) { stopSelf(); return }

            running = true
            udpSocket = DatagramSocket()
            protect(udpSocket!!)

            Thread { readLoop() }.start()
            Thread { writeLoop() }.start()
            Log.d(TAG, "VPN started")
        } catch (e: Exception) {
            Log.e(TAG, "startVpn failed: ${e.message}")
            stopSelf()
        }
    }

    private fun readLoop() {
        val fd = vpnFd ?: return
        try {
            val inp = FileInputStream(fd.fileDescriptor)
            val buf = ByteArray(4096)
            while (running) {
                val n = inp.read(buf)
                if (n > 0) handleOutgoing(buf, n)
            }
        } catch (e: Exception) {
            if (running) Log.e(TAG, "readLoop: ${e.message}")
        }
    }

    private fun writeLoop() {
        try {
            val sock = udpSocket ?: return
            val buf = ByteArray(4096)
            while (running) {
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt)
                if (pkt.length > 0) {
                    val resp = pkt.data.copyOf(pkt.length)
                    val written = writeToTunnel(resp)
                    if (written < 0) break
                }
            }
        } catch (e: Exception) {
            if (running) Log.e(TAG, "writeLoop: ${e.message}")
        }
    }

    private fun writeToTunnel(data: ByteArray): Int {
        val fd = vpnFd ?: return -1
        try {
            val out = FileInputStream(fd.fileDescriptor)
            out.read(data)
            return data.size
        } catch (e: Exception) {
            return -1
        }
    }

    private fun handleOutgoing(buf: ByteArray, len: Int) {
        if (len < 20) return
        val proto = buf[9].toInt() and 0xFF
        if (proto != 17) return
        val ihl = (buf[0].toInt() and 0x0F) * 4
        val dstPort = ((buf[ihl + 2].toInt() and 0xFF) shl 8) or (buf[ihl + 3].toInt() and 0xFF)
        val payloadOff = ihl + 8
        val payloadLen = len - payloadOff
        if (payloadLen < 12) return
        val payload = buf.copyOfRange(payloadOff, payloadOff + payloadLen)

        try {
            PhotonPacketParser.parse(payload) { type, params ->
                EventRouter.route(type, params)
            }
        } catch (e: Exception) {}

        try {
            val dstIp = InetAddress.getByAddress(byteArrayOf(buf[16], buf[17], buf[18], buf[19]))
            val pkt = DatagramPacket(payload, payload.size, dstIp, dstPort)
            udpSocket?.send(pkt)
        } catch (e: Exception) {}
    }

    override fun onPlayerJoined(player: com.albionplayersradar.data.Player) {
        val msg = "SPAWN:${player.id}|${player.name}|${player.guildName ?: ""}|${player.allianceName ?: ""}|${player.faction}"
        Log.d(TAG, msg)
        onUpdate?.invoke(msg)
    }

    override fun onPlayerLeft(id: Long) {
        val msg = "LEAVE:$id"
        Log.d(TAG, msg)
        onUpdate?.invoke(msg)
    }

    override fun onPlayerMoved(player: com.albionplayersradar.data.Player) {
        val msg = "MOVE:${player.id}|${player.posX}|${player.posY}"
        onUpdate?.invoke(msg)
    }

    override fun onPlayerHealthChanged(id: Long, currentHp: Float, maxHp: Float) {
        onUpdate?.invoke("HEALTH:$id|$currentHp|$maxHp")
    }

    override fun onMountChanged(id: Long, isMounted: Boolean) {
        onUpdate?.invoke("MOUNT:$id|$isMounted")
    }

    override fun onFactionChanged(id: Long, faction: Int) {
        onUpdate?.invoke("FACTION:$id|$faction")
    }

    override fun onZoneChanged(zoneId: String) {
        onUpdate?.invoke("ZONE:$zoneId")
    }

    fun stopVpn() {
        running = false
        try {
            vpnFd?.close()
            udpSocket?.close()
        } catch (e: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }
}
