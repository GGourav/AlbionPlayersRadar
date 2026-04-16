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
import com.albionplayersradar.R
import com.albionplayersradar.parser.EventRouter
import com.albionplayersradar.parser.PhotonPacketParser
import com.albionplayersradar.ui.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer

class AlbionVpnService : Service(), EventRouter.Callback {

    private val binder = LocalBinder()
    private var running = false
    private var readerThread: Thread? = null
    private var vpnInterface: android.net.VpnService.Builder? = null
    private var tunFd: android.os.ParcelFileDescriptor? = null
    private var proxySocket: DatagramSocket? = null
    private val parser = PhotonPacketParser()
    private var playerListener: PhotonPacketParser.PlayerListener? = null

    inner class LocalBinder : Binder() {
        fun getService(): AlbionVpnService = this@AlbionVpnService
    }

    override fun onCreate() {
        super.onCreate()
        EventRouter.setCallback(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        return START_STICKY
    }

    fun startVpn() {
        if (running) return
        val intent = VpnService.prepare(this)
        if (intent != null) {
            return
        }
        setupVpn()
    }

    fun stopVpn() {
        running = false
        try {
            tunFd?.close()
            proxySocket?.close()
            readerThread?.interrupt()
        } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    val isRunning: Boolean get() = running

    private fun setupVpn() {
        try {
            val builder = VpnService.Builder()
            builder.setMtu(1400)
            builder.addAddress("10.0.0.2", 32)
            builder.addRoute("0.0.0.0", 0)
            builder.addDnsServer("8.8.8.8")
            builder.addAllowedApplication("com.albiononline")

            tunFd = builder.establish()
            if (tunFd == null) {
                Log.e(TAG, "VPN setup failed")
                return
            }

            proxySocket = DatagramSocket(SERVER_PORT)
            proxySocket?.reuseAddress = true

            running = true
            startReader()
        } catch (e: Exception) {
            Log.e(TAG, "VPN error: ${e.message}")
        }
    }

    private fun startReader() {
        readerThread = Thread {
            val input = FileInputStream(tunFd!!)
            val output = FileOutputStream(tunFd!!)
            val buffer = ByteBuffer.allocate(32767)

            while (running) {
                try {
                    buffer.clear()
                    val len = input.read(buffer.array())
                    if (len > 0) {
                        buffer.limit(len)
                        val proto = (buffer.get(9).toInt() and 0xFF)
                        if (proto == 17) {
                            handleUdpPacket(buffer, len, output)
                        }
                    }
                } catch (e: Exception) {
                    if (running) Log.e(TAG, "Read error: ${e.message}")
                    break
                }
            }
        }.apply { start() }
    }

    private fun handleUdpPacket(buffer: ByteBuffer, len: Int, output: FileOutputStream) {
        try {
            val dstPort = buffer.getShort(22).toInt() and 0xFFFF
            if (dstPort == GAME_PORT || dstPort == GAME_PORT_ALT) {
                val payload = ByteArray(len - 28)
                val payloadBuffer = ByteBuffer.wrap(payload)
                buffer.position(28)
                buffer.get(payload)
                buffer.position(0)

                parser.parse(payload, this)

                proxySocket?.send(DatagramPacket(payload, payload.size, SERVER_ADDR, GAME_PORT))
            }

            if (dstPort == SERVER_PORT) {
                val response = ByteArray(len - 28)
                val responseBuffer = ByteBuffer.wrap(response)
                buffer.position(28)
                buffer.get(response)
                buffer.position(0)

                parser.parse(response, this)

                val replyPacket = DatagramPacket(response, response.size)
                replyPacket.address = java.net.InetAddress.getByName(SERVER_ADDR)
                replyPacket.port = SERVER_PORT
                proxySocket?.send(replyPacket)
            }

            output.write(buffer.array(), 0, len)
        } catch (_: Exception) {}
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, MainActivity.CHANNEL_ID)
            .setContentTitle("Albion Players Radar")
            .setContentText("Scanning for players...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .build()
    }

    fun setPlayerListener(listener: PhotonPacketParser.PlayerListener?) {
        playerListener = listener
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onEvent(code: Int, params: Map<Byte, Any>) {
        EventRouter.routeEvent(code, params, playerListener)
    }

    override fun onRequest(code: Int, params: Map<Byte, Any>) {}

    override fun onResponse(code: Int, params: Map<Byte, Any>) {}

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AlbionVPN"
        private const val NOTIF_ID = 1001
        private const val GAME_PORT = 5056
        private const val GAME_PORT_ALT = 5057
        private const val SERVER_PORT = 55001
        private const val SERVER_ADDR = "5.45.187.219"
    }
}
