package com.albionplayersradar.vpn

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.albionplayersradar.data.Player
import com.albionplayersradar.parser.EventRouter
import com.albionplayersradar.ui.MainActivity
import java.io.FileInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class AlbionVpnService : Service(), EventRouter.PlayerListener {

    private var running = false
    private var vpnFd: android.os.ParcelFileDescriptor? = null
    private val players = mutableMapOf<Long, Player>()
    private var localPosX = 0f
    private var localPosY = 0f
    private var currentZone = ""

    private var onUpdate: ((String) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): AlbionVpnService = this@AlbionVpnService
    }
    private val binder = LocalBinder()

    companion object {
        private const val TAG = "AlbionVpnService"
        private const val NOTIFY_ID = 1001
        private const val SERVER_IP = "5.45.187.219"
        private const val SERVER_PORT = 5056
        private const val LOCAL_IP = "10.0.0.2"
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        EventRouter.setPlayerListener(this)
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
        return NotificationCompat.Builder(this, "radar_channel")
            .setContentTitle("Albion Players Radar")
            .setContentText("Active")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    fun setPlayerUpdateListener(cb: (String) -> Unit) { onUpdate = cb }

    private fun startVpn() {
        if (running) return
        try {
            val vpnBuilder = VpnService.Builder()
                .setSession("AlbionPlayersRadar")
                .addAddress(LOCAL_IP, 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .setMtu(1500)
                .setBlocking(true)

            vpnFd = vpnBuilder.establish()
            if (vpnFd == null) { stopSelf(); return }

            running = true
            Thread { readLoop() }.start()
            Thread { proxyLoop() }.start()
            Log.d(TAG, "VPN started")
        } catch (e: Exception) {
            Log.e(TAG, "VPN start failed: ${e.message}")
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
            if (running) Log.e(TAG, "read: ${e.message}")
        }
    }

    private fun handleOutgoing(buf: ByteArray, len: Int) {
        if (len < 20) return
        if (buf[9].toInt() and 0xFF != 17) return
        val ihl = (buf[0].toInt() and 0x0F) * 4
        val dstPort = ((buf[ihl+2].toInt() and 0xFF) shl 8) or (buf[ihl+3].toInt() and 0xFF)
        val payloadOff = ihl + 8
        val payloadLen = len - payloadOff
        if (payloadLen < 12) return
        val payload = buf.copyOfRange(payloadOff, payloadOff + payloadLen)
        try { EventRouter.onPacket(payload) } catch (e: Exception) {}
        try {
            val sock = DatagramSocket()
            protect(sock)
            val dstIp = InetAddress.getByAddress(byteArrayOf(buf[16], buf[17], buf[18], buf[19]))
            val pkt = DatagramPacket(payload, payload.size, dstIp, dstPort)
            sock.send(pkt)
            sock.close()
        } catch (e: Exception) {}
    }

    private fun proxyLoop() {
        try {
            val sock = DatagramSocket(SERVER_PORT)
            protect(sock)
            val buf = ByteArray(4096)
            while (running) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    if (pkt.length > 0) {
                        val response = buildIpResponse(pkt.data, pkt.length)
                        if (response != null) {
                            val out = DatagramPacket(response, response.size, pkt.address, pkt.port)
                            sock.send(out)
                        }
                    }
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "proxy: ${e.message}")
        }
    }

    private fun buildIpResponse(data: ByteArray, len: Int): ByteArray? {
        if (len < 20) return null
        val resp = data.copyOf()
        val srcOff = 12; val dstOff = 16
        for (i in 0..3) { val t = resp[srcOff+i]; resp[srcOff+i] = resp[dstOff+i]; resp[dstOff+i] = t }
        resp[9] = 17
        val totalLen = 8 + len
        resp[2] = (totalLen shr 8).toByte(); resp[3] = (totalLen and 0xFF).toByte()
        resp[10] = 0; resp[11] = 0
        return resp
    }

    fun getAllPlayers(): List<Player> = players.values.toList()
    fun getLocalPosition(): Pair<Float, Float> = localPosX to localPosY
    fun getCurrentZone(): String = currentZone

    override fun onPlayerJoined(player: Player) {
        players[player.id] = player
        notifyUpdate("SPAWN:${player.id}|${player.name}|${player.guildName ?: ""}|${player.allianceName ?: ""}|${player.faction}")
    }

    override fun onPlayerLeft(id: Long) {
        players.remove(id)
        notifyUpdate("LEAVE:$id")
    }

    override fun onPlayerMoved(player: Player) {
        players[player.id] = player
        notifyUpdate("MOVE:${player.id}|${player.posX}|${player.posY}")
    }

    override fun onPlayerHealthChanged(id: Long, currentHp: Float, maxHp: Float) {
        players[id]?.let { p ->
            players[id] = Player(p.id, p.name, p.guildName, p.allianceName, p.faction, p.posX, p.posY, p.posZ, currentHp.toInt(), maxHp.toInt(), p.isMounted)
            notifyUpdate("HEALTH:$id|$currentHp|$maxHp")
        }
    }

    override fun onFactionChanged(id: Long, faction: Int) {
        players[id]?.let { p ->
            players[id] = Player(p.id, p.name, p.guildName, p.allianceName, faction, p.posX, p.posY, p.posZ, p.currentHealth, p.maxHealth, p.isMounted)
            notifyUpdate("FACTION:$id|$faction")
        }
    }

    override fun onMountChanged(id: Long, isMounted: Boolean) {}

    override fun onDestroy() {
        running = false
        vpnFd?.close()
        super.onDestroy()
    }

    private fun notifyUpdate(msg: String) {
        onUpdate?.invoke(msg)
    }
}
