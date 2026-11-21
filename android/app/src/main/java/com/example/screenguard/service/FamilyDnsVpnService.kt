package com.example.screenguard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class FamilyDnsVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile
    private var isRunning = false
    private var vpnThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground safely caught: ${e.message}", e)
        }
        establishDnsTunnel()
        return START_STICKY
    }

    private fun establishDnsTunnel() {
        try {
            stopVpn()

            val builder = Builder()
                .setSession("ScreenGuard Family DNS")
                .addAddress("10.0.0.2", 32)
                // Cloudflare 1.1.1.3 (Blocks Malware + Adult Content at DNS Level)
                .addDnsServer("1.1.1.3")
                .addDnsServer("1.0.0.3")
                // CleanBrowsing Family Filter (Adult content blocking backup)
                .addDnsServer("185.228.168.168")
                .addRoute("1.1.1.3", 32)
                .addRoute("1.0.0.3", 32)
                .addRoute("185.228.168.168", 32)
                .setBlocking(true)

            vpnInterface = builder.establish()
            Log.i(TAG, "✓ ScreenGuard Family DNS VPN Established (Cloudflare 1.1.1.3 active)")

            isRunning = true
            vpnThread = Thread({
                val pfd = vpnInterface ?: return@Thread
                val inputStream = FileInputStream(pfd.fileDescriptor)
                val outputStream = FileOutputStream(pfd.fileDescriptor)
                val buffer = ByteArray(32767)

                val socket = DatagramSocket()
                protect(socket)
                socket.soTimeout = 2500
                val targetDns = InetAddress.getByName("1.1.1.3")

                while (isRunning && !Thread.currentThread().isInterrupted) {
                    try {
                        val length = inputStream.read(buffer)
                        if (length > 0) {
                            val version = (buffer[0].toInt() shr 4) and 0x0F
                            if (version == 4 && length >= 28) {
                                val ipHeaderLen = (buffer[0].toInt() and 0x0F) * 4
                                val protocol = buffer[9].toInt() and 0xFF
                                if (protocol == 17 && length >= ipHeaderLen + 8) {
                                    val destPort = ((buffer[ipHeaderLen].toInt() and 0xFF) shl 8) or (buffer[ipHeaderLen + 1].toInt() and 0xFF)
                                    if (destPort == 53) {
                                        val udpHeaderLen = 8
                                        val dnsOffset = ipHeaderLen + udpHeaderLen
                                        val dnsLen = length - dnsOffset
                                        if (dnsLen > 0) {
                                            val outPacket = DatagramPacket(buffer, dnsOffset, dnsLen, targetDns, 53)
                                            socket.send(outPacket)

                                            val inBuf = ByteArray(4096)
                                            val inPacket = DatagramPacket(inBuf, inBuf.size)
                                            socket.receive(inPacket)

                                            val srcPort = ((buffer[ipHeaderLen + 2].toInt() and 0xFF) shl 8) or (buffer[ipHeaderLen + 3].toInt() and 0xFF)
                                            val respPayloadLen = inPacket.length
                                            val totalRespLen = 20 + 8 + respPayloadLen
                                            val respPacketBytes = ByteArray(totalRespLen)

                                            respPacketBytes[0] = 0x45.toByte()
                                            respPacketBytes[1] = 0.toByte()
                                            respPacketBytes[2] = ((totalRespLen shr 8) and 0xFF).toByte()
                                            respPacketBytes[3] = (totalRespLen and 0xFF).toByte()
                                            respPacketBytes[4] = 0.toByte()
                                            respPacketBytes[5] = 0.toByte()
                                            respPacketBytes[6] = 0x40.toByte()
                                            respPacketBytes[7] = 0.toByte()
                                            respPacketBytes[8] = 64.toByte()
                                            respPacketBytes[9] = 17.toByte()

                                            System.arraycopy(buffer, 16, respPacketBytes, 12, 4)
                                            System.arraycopy(buffer, 12, respPacketBytes, 16, 4)

                                            respPacketBytes[20] = 0.toByte()
                                            respPacketBytes[21] = 53.toByte()
                                            respPacketBytes[22] = ((srcPort shr 8) and 0xFF).toByte()
                                            respPacketBytes[23] = (srcPort and 0xFF).toByte()
                                            val udpLen = 8 + respPayloadLen
                                            respPacketBytes[24] = ((udpLen shr 8) and 0xFF).toByte()
                                            respPacketBytes[25] = (udpLen and 0xFF).toByte()
                                            respPacketBytes[26] = 0.toByte()
                                            respPacketBytes[27] = 0.toByte()

                                            System.arraycopy(inPacket.data, inPacket.offset, respPacketBytes, 28, respPayloadLen)

                                            outputStream.write(respPacketBytes)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (!isRunning) break
                    }
                }
                try { socket.close() } catch (ignored: Exception) {}
            }, "ScreenGuardDnsForwarder").apply { isDaemon = true; start() }

        } catch (e: Exception) {
            Log.e(TAG, "Failed establishing Family DNS VPN", e)
        }
    }

    private fun stopVpn() {
        isRunning = false
        vpnThread?.interrupt()
        vpnThread = null
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: IOException) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun createNotification(): Notification {
        val channelId = "screenguard_family_dns"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "ScreenGuard Protection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Enforces Family DNS filtering"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("🛡 ScreenGuard Protection Active")
            .setContentText("Family DNS & Adult Content filtering is active.")
            .setSmallIcon(android.R.drawable.ic_secure)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "FamilyDnsVpn"
        private const val NOTIFICATION_ID = 2049
        const val ACTION_START = "com.example.screenguard.action.START_VPN"
        const val ACTION_STOP = "com.example.screenguard.action.STOP_VPN"

        fun start(context: Context) {
            try {
                val intent = Intent(context, FamilyDnsVpnService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed starting Family DNS VPN", e)
            }
        }
    }
}
