package com.albionplayersradar.vpn

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.albionplayersradar.MainApplication
import com.albionplayersradar.R
import com.albionplayersradar.data.Player
import com.albionplayersradar.data.ZoneInfo
import com.albionplayersradar.parser.EventRouter
import com.albionplayersradar.parser.PhotonPacketParser
import com.albionplayersradar.ui.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

class AlbionVpnService : VpnService() {

    private val TAG = "AlbionVpnService"
    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private lateinit var eventRouter: EventRouter
    private var playerCallback: PlayerDisplayCallback? = null

    interface PlayerDisplayCallback {
        fun onPlayerSpawned(player: Player)
        fun onPlayerLeft(id: Long)
        fun onPlayerMoved(id: Long, x: Float, y: Float)
        fun onPlayerHealthUpdate(id: Long, health: Int, maxHealth: Int)
        fun onZoneChanged(zoneId: String, zone: ZoneInfo)
        fun onLocalPlayerMoved(x: Float, y: Float, zoneId: String)
        fun onAlert(message: String)
    }

    fun setPlayerCallback(cb: PlayerDisplayCallback) {
        playerCallback = cb
    }

    inner class VpnThread : Thread("AlbionVpnThread") {
        override fun run() {
            isRunning = true
            val vpnFd = vpnInterface!!.fileDescriptor
            val input = FileInputStream(vpnFd)
            val output = FileOutputStream(vpnFd)
            val buffer = ByteBuffer.allocate(4096)

            Log.d(TAG, "VPN thread running")

            while (isRunning) {
                try {
                    buffer.clear()
                    val length = input.read(buffer.array())

                    if (length > 0) {
                        buffer.limit(length)
                        processPacket(buffer, length)
                    }
                } catch (e: Exception) {
                    if (isRunning) Log.e(TAG, "Read error: ${e.message}")
                }
            }

            try { input.close() } catch (e: Exception) {}
            try { output.close() } catch (e: Exception) {}
            Log.d(TAG, "VPN thread stopped")
        }
    }

    private fun processPacket(buffer: ByteBuffer, length: Int) {
        if (length < 20) return

        val version = (buffer.get(0).toInt() shr 4) and 0xF
        if (version != 4) return

        val ihl = (buffer.get(0).toInt() and 0xF) * 4
        val protocol = buffer.get(9).toInt() and 0xFF
        if (protocol != 17) return  // UDP only

        val srcPort = ((buffer.get(ihl).toInt() and 0xFF) shl 8) or
                      (buffer.get(ihl + 1).toInt() and 0xFF)
        val dstPort = ((buffer.get(ihl + 2).toInt() and 0xFF) shl 8) or
                      (buffer.get(ihl + 3).toInt() and 0xFF)

        // Only capture Albion server port
        if (dstPort != 5056 && dstPort != 5057) return

        val udpOffset = ihl + 8
        val udpPayloadLen = length - udpOffset

        if (udpPayloadLen <= 8) return

        val payload = buffer.array().copyOfRange(udpOffset, udpOffset + udpPayloadLen)
        PhotonPacketParser.parsePacket(payload, eventRouter)
    }

    override fun onCreate() {
        super.onCreate()

        eventRouter = EventRouter(
            onPlayerSpawn = { p -> playerCallback?.onPlayerSpawned(p) },
            onPlayerLeave = { id -> playerCallback?.onPlayerLeft(id) },
            onPlayerMove = { id, x, y -> playerCallback?.onPlayerMoved(id, x, y) },
            onPlayerHealth = { id, h, mh -> playerCallback?.onPlayerHealthUpdate(id, h, mh) },
            onZoneChange = { zid, zone -> playerCallback?.onZoneChanged(zid, zone) },
            onLocalPlayerJoined = { _, x, y, zid -> playerCallback?.onLocalPlayerMoved(x, y, zid ?: "") }
        )

        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(startId, notification, android.content.pm.ForegroundService.IMPLICIT_FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(startId, notification)
        }

        startVpnInterface()
        return START_STICKY
    }

    private fun startVpnInterface() {
        try {
            val builder = Builder()
                .setMtu(2048)
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("8.8.4.4")
                .addAllowedApplication("com.albiononline")

            vpnInterface = builder.establish()
            if (vpnInterface != null) {
                VpnThread().start()
                Log.d(TAG, "VPN established")
            }
        } catch (e: Exception) {
            Log.e(TAG, "VPN failed: ${e.message}")
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, MainApplication.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_radar)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        try { vpnInterface?.close() } catch (e: Exception) {}
        vpnInterface = null
        super.onDestroy()
    }

    override fun onRevoke() {
        isRunning = false
        try { vpnInterface?.close() } catch (e: Exception) {}
        vpnInterface = null
        super.onRevoke()
    }
}
