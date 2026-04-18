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
import com.albionplayersradar.MainApplication
import com.albionplayersradar.parser.EventRouter
import com.albionplayersradar.ui.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

class AlbionVpnService : VpnService(), EventRouter.PlayerListener {

    private val binder = LocalBinder()
    private val running = AtomicBoolean(false)
    private var tunnelFd: android.os.ParcelFileDescriptor? = null
    private var udpSocket: DatagramSocket? = null

    // Albion Online server — hardcoded for now, ideally resolved dynamically
    private val SERVER_IP: ByteArray = byteArrayOf(5, 45, -71, 27)  // 5.45.187.219
    private val SERVER_PORT = 5056

    var onUpdate: ((String) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): AlbionVpnService = this@AlbionVpnService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running.getAndSet(true)) return START_STICKY

        val notification = createNotification()
        startForeground(1, notification)

        Thread { runTunnel() }.start()
        return START_STICKY
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, MainApplication.CHANNEL_ID)
            .setContentTitle("Albion Radar Active")
            .setContentText("VPN tunnel running")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun runTunnel() {
        try {
            val builder = Builder()
                .setSession("AlbionRadar")
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .setMtu(1500)

            tunnelFd = builder.establish()

            val input = FileInputStream(tunnelFd!!.fileDescriptor)
            val output = FileOutputStream(tunnelFd!!.fileDescriptor)
            val packet = ByteArray(32767)

            // Open UDP socket BEFORE protection — critical!
            udpSocket = DatagramSocket()
            udpSocket!!.connect(InetAddress.getByAddress(SERVER_IP), SERVER_PORT)

            // Protect socket so traffic bypasses VPN
            protect(udpSocket!!.socket)

            while (running.get()) {
                val len = input.read(packet)
                if (len > 0) {
                    handleOutgoing(packet, len, output)
                }
            }
        } catch (e: Exception) {
            Log.e("AlbionVPN", "Tunnel error", e)
        } finally {
            running.set(false)
            tunnelFd?.close()
            udpSocket?.close()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Parse IP header from game packet.
     * Layout (standard 20-byte IPv4 header, no options):
     *   byte[0]  = version+IHL  (0x45 = IPv4, 5×4=20 bytes header)
     *   byte[1]  = TOS
     *   byte[2-3]= total length (big-endian)
     *   byte[4-5]= ID          (big-endian)
     *   byte[6-7]= flags+frag  (big-endian)
     *   byte[8]  = TTL
     *   byte[9]  = protocol    (17=UDP)
     *   byte[10-11]= header checksum (big-endian)
     *   byte[12-15]= src IP
     *   byte[16-19]= dst IP
     * UDP header starts at offset 20:
     *   byte[20-21]= src port  (big-endian)
     *   byte[22-23]= dst port  (big-endian)
     *   byte[24-25]= length
     *   byte[26-27]= checksum
     * Data starts at offset 28.
     */
    private fun handleOutgoing(packet: ByteArray, length: Int, output: FileOutputStream) {
        if (length < 28) return

        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return  // Only UDP

        val totalLen = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        if (totalLen > length) return

        // dst IP from IP header (bytes 16-19)
        val dstIp = packet.copyOfRange(16, 20)
        // dst port from UDP header (bytes 22-23)
        val dstPort = ((packet[22].toInt() and 0xFF) shl 8) or (packet[23].toInt() and 0xFF)

        val dataLen = totalLen - 28
        if (dataLen <= 0) return

        // Forward to Albion server
        try {
            val data = packet.copyOfRange(28, 28 + dataLen)
            val req = DatagramPacket(data, data.size, InetAddress.getByAddress(dstIp), dstPort)
            udpSocket?.send(req)
        } catch (e: Exception) {
            Log.e("AlbionVPN", "Send error", e)
        }
    }

    fun handleIncoming(data: ByteArray) {
        try {
            val response = buildIpPacket(data)
            val out = FileOutputStream(tunnelFd!!.fileDescriptor)
            out.write(response)
            out.flush()
        } catch (e: Exception) {
            Log.e("AlbionVPN", "Write error", e)
        }
    }

    /**
     * Build a valid IPv4+UDP packet from raw UDP payload received from Albion server.
     * IP header: 20 bytes, UDP header: 8 bytes, payload: data.size bytes.
     */
    private fun buildIpPacket(data: ByteArray): ByteArray {
        val totalLen = 28 + data.size
        val out = ByteArray(totalLen)

        // IP header
        out[0] = 0x45                          // version=4, IHL=5
        out[1] = 0.toByte()                    // TOS
        out[2] = (totalLen ushr 8).toByte()   // total length high
        out[3] = totalLen.toByte()              // total length low
        out[4] = 0; out[5] = 0                 // ID
        out[6] = 0x40.toByte()                 // flags: DF
        out[7] = 0.toByte()                    // fragment offset
        out[8] = 64.toByte()                   // TTL
        out[9] = 17                             // protocol = UDP
        out[10] = 0; out[11] = 0               // checksum (0 = skip)

        // src IP = Albion server
        System.arraycopy(SERVER_IP, 0, out, 12, 4)
        // dst IP = VPN address (tunnel)
        out[16] = 10; out[17] = 0; out[18] = 0; out[19] = 2

        // UDP header
        // src port = server port
        out[20] = (SERVER_PORT ushr 8).toByte()
        out[21] = SERVER_PORT.toByte()
        // dst port = 65072 (Android VPN assigned source port)
        out[22] = 0xFE; out[23] = 0x10
        // UDP length
        out[24] = (totalLen ushr 8).toByte()
        out[25] = totalLen.toByte()
        out[26] = 0; out[27] = 0               // UDP checksum (skip)

        // Payload
        System.arraycopy(data, 0, out, 28, data.size)
        return out
    }

    fun disconnect() {
        running.set(false)
        tunnelFd?.close()
        udpSocket?.close()
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    // ——— EventRouter.PlayerListener implementation ———

    override fun onPlayerJoined(id: Long, name: String, guild: String, posX: Float, posY: Float, faction: Int) {
        onUpdate?.invoke("PLAYER:$id|$name|$guild|$faction")
    }

    override fun onPlayerLeft(id: Long) {
        onUpdate?.invoke("LEFT:$id")
    }

    override fun onPlayerMoved(id: Long, posX: Float, posY: Float) {
        onUpdate?.invoke("MOVE:$id|$posX|$posY")
    }

    override fun onPlayerMountChanged(id: Long, isMounted: Boolean) {
        // Not tracked in current UI
    }

    override fun onMapChanged(zoneId: String) {
        onUpdate?.invoke("ZONE:$zoneId")
    }

    override fun onLocalPlayerMoved(posX: Float, posY: Float) {
        onUpdate?.invoke("LOCAL:$posX|$posY")
    }
}
