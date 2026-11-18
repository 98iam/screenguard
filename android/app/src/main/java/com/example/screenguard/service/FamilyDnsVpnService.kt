package com.example.screenguard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.IOException

class FamilyDnsVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, createNotification())
        establishDnsTunnel()
        return START_STICKY
    }

    private fun establishDnsTunnel() {
        try {
            if (vpnInterface != null) {
                vpnInterface?.close()
                vpnInterface = null
            }

            val builder = Builder()
                .setSession("ScreenGuard Family DNS")
                .addAddress("10.0.0.2", 32)
                // Cloudflare 1.1.1.3 (Blocks Malware + Adult Content at DNS Level)
                .addDnsServer("1.1.1.3")
                .addDnsServer("1.0.0.3")
                // CleanBrowsing Family Filter (Adult content blocking backup)
                .addDnsServer("185.228.168.168")
                .setBlocking(false)

            vpnInterface = builder.establish()
            Log.i(TAG, "✓ ScreenGuard Family DNS VPN Established (Cloudflare 1.1.1.3 active)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed establishing Family DNS VPN", e)
        }
    }

    private fun stopVpn() {
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
