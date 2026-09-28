package com.itantra.walkie.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.itantra.walkie.MainActivity

/**
 * The reason the radio survives a locked screen.
 *
 * Android suspends BLE scanning as soon as an app leaves the foreground, which for a walkie-talkie
 * is the whole point of the device: a rescuer's phone is in a pocket, and a trapped person's phone
 * is face-down next to them. So the mesh runs behind a foreground service of type
 * `connectedDevice`, and the notification is not decoration - it is the platform's own rule that a
 * persistent radio must be visible to the person who granted it. The line says what it is doing and
 * how many handsets it currently hears, so "is it still listening?" never needs a screen unlock.
 *
 * The service owns no radio state. [BleMesh] lives in the ViewModel, which outlives the activity;
 * this only holds the process up and restates the count.
 */
class MeshService : android.app.Service() {

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            // IMPORTANCE_LOW: visible and silent. A rescue radio that beeps at its own status bar
            // gets dismissed, and a dismissed notification means a killed service.
            NotificationChannel(CH, "Mesh radio", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows while VANI is advertising and listening over Bluetooth"
                setShowBadge(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val peers = intent?.getIntExtra(EXTRA_PEERS, -1) ?: -1
        if (peers >= 0) lastPeers = peers
        val n = build(lastPeers)
        // The type is required from 29 up: this is a connected-device radio, and Android will not
        // let a service claim foreground time without saying which hardware it is holding open.
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(ID, n)
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID)
        }
        super.onDestroy()
    }

    private fun build(peers: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("VANI is listening")
            .setContentText(
                if (peers == 1) "1 phone in range over Bluetooth"
                else "$peers phones in range · or none heard yet"
            )
            .setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val ID = 0x5A17
        private const val CH = "vani.mesh"
        private const val EXTRA_PEERS = "peers"
        private var lastPeers = 0

        /** Called whenever the roster changes; the count in the shade must match the console. */
        fun report(context: Context, peers: Int) {
            lastPeers = peers
            runCatching {
                ContextCompat.startForegroundService(
                    context, Intent(context, MeshService::class.java).putExtra(EXTRA_PEERS, peers)
                )
            }
        }

        fun stop(context: Context) = runCatching {
            context.stopService(Intent(context, MeshService::class.java))
        }
    }
}
