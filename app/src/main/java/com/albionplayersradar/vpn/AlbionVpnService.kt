package com.albionplayersradar.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.albionplayersradar.R
import com.albionplayersradar.parser.EventRouter
import com.albionplayersradar.parser.PhotonPacketParser
import com.albionplayersradar.ui.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class AlbionVpnService : Service(), EventRouter.PlayerListener {

    private val binder = LocalBinder()
    private var running = false
    private var vpnFd: android.os.ParcelFileDescriptor? = null
    private var udpSocket: DatagramSocket? = null

    private val SERVER_IP = "5.45.187.219"
    private val SERVER_PORT = 5056
    private val LOCAL_IP = "10.0.0.2"
    private val NOTIFY_ID = 1001

    companion object {
        const val CHANNEL_ID = "albion_vpn_channel"
        var onUpdate: ((String) -> Unit)? = null
    }

    inner class LocalBinder : Binder() {
        fun getService(): AlbionVpnService = this@AlbionVpnService
    }

    override fun onCreate() {
        super.onCreate()
        EventRouter.setPlayerListener(this)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFY_ID, createNotification())
        startVpn()
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Albion Radar", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Albion Players Radar")
            .setContentText("Active")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun startVpn() {
        if (running) return
        try {
            VpnService.prepare(this)?.let { return }

            val builder = Builder()
                .setSession("AlbionPlayersRadar")
                .addAddress(LOCAL_IP, 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .setMtu(1500)
                .setBlocking(true)

            vpnFd = builder.establish()
            if (vpnFd == null) {
                stopSelf()
                return
            }

            running = true

            Thread { readFromTunnel() }.start()
            Thread { writeToTunnel() }.start()

            Log.d("AlbionVPN", "VPN started")
        } catch (e: Exception) {
            Log.e("AlbionVPN", "startVpn failed: ${e.message}")
            stopSelf()
        }
    }

    private fun readFromTunnel() {
        val inp = FileInputStream(vpnFd!!.fileDescriptor)
        val buf = ByteArray(2048)
        try {
            udpSocket = DatagramSocket()
            protectSocket(udpSocket!!)
        } catch (e: Exception) {
            Log.e("AlbionVPN", "socket: ${e.message}")
            return
        }

        while (running) {
            try {
                val n = inp.read(buf)
                if (n > 0) {
                    handleOutgoing(buf, n)
                }
            } catch (e: Exception) {
                if (running) Log.e("AlbionVPN", "read: ${e.message}")
                break
            }
        }
    }

    private fun handleOutgoing(buf: ByteArray, len: Int) {
        if (len < 28) return
        val protocol = buf[9].toInt() and 0xFF
        if (protocol != 17) return

        val ihl = (buf[0].toInt() and 0x0F) * 4
        if (len < ihl + 8) return

        val dstPort = ((buf[ihl + 2].toInt() and 0xFF) shl 8) or (buf[ihl + 3].toInt() and 0xFF)
        if (dstPort != SERVER_PORT) return

        val payloadOff = ihl + 8
        val payloadLen = len - payloadOff
        if (payloadLen < 12) return

        val payload = buf.copyOfRange(payloadOff, payloadOff + payloadLen)

        try {
            EventRouter.onUdpPacketReceived(payload)
        } catch (e: Exception) {
            Log.e("AlbionVPN", "parse: ${e.message}")
        }

        try {
            val dstIp = InetAddress.getByAddress(byteArrayOf(buf[16], buf[17], buf[18], buf[19]))
            val pkt = DatagramPacket(payload, payload.size, dstIp, dstPort)
            udpSocket!!.send(pkt)
        } catch (e: Exception) {
            Log.e("AlbionVPN", "send: ${e.message}")
        }
    }

    private fun writeToTunnel() {
        val out = FileOutputStream(vpnFd!!.fileDescriptor)
        val buf = ByteArray(2048)
        while (running) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                udpSocket!!.receive(pkt)
                if (pkt.length > 0) {
                    out.write(pkt.data, 0, pkt.length)
                }
            } catch (e: Exception) {
                if (running) {}
            }
        }
    }

    private fun protectSocket(socket: DatagramSocket) {
        try {
            val protectMethod = Class.forName("android.net.VpnService").getMethod("protect", java.net.Socket::class.java)
            protectMethod.invoke(this, socket)
        } catch (e: Exception) {
            Log.e("AlbionVPN", "protect failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        running = false
        try {
            vpnFd?.close()
            udpSocket?.close()
        } catch (e: Exception) {}
        super.onDestroy()
    }

    override fun onPlayerJoined(id: Long, name: String, guild: String, posX: Float, posY: Float, faction: Int) {
        val msg = "JOIN:$id|$name|$guild|$faction"
        Log.d("AlbionVPN", msg)
        onUpdate?.invoke(msg)
        MainActivity.broadcastPlayerJoined(id, name, guild, posX, posY, faction)
    }

    override fun onPlayerLeft(id: Long) {
        val msg = "LEAVE:$id"
        Log.d("AlbionVPN", msg)
        onUpdate?.invoke(msg)
        MainActivity.broadcastPlayerLeave(id)
    }

    override fun onPlayerMoved(id: Long, posX: Float, posY: Float) {
        val msg = "MOVE:$id|$posX|$posY"
        onUpdate?.invoke(msg)
        MainActivity.broadcastPlayerMove(id, posX, posY)
    }

    override fun onPlayerMountChanged(id: Long, isMounted: Boolean) {
        val msg = "MOUNT:$id|$isMounted"
        onUpdate?.invoke(msg)
    }

    override fun onMapChanged(zoneId: String) {
        val msg = "ZONE:$zoneId"
        Log.d("AlbionVPN", msg)
        onUpdate?.invoke(msg)
        MainActivity.broadcastZone(zoneId)
    }

    override fun onLocalPlayerMoved(posX: Float, posY: Float) {
        val msg = "LOCAL:$posX|$posY"
        onUpdate?.invoke(msg)
    }
}
