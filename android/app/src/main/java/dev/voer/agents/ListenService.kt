package dev.voer.agents

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/** One quiet connection to your server that waits (long poll) for news. When a task finishes or an agent messages you,
 *  the phone shows a notification at once. Nothing else is needed: no Telegram, no extra app. */
class ListenService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: kotlinx.coroutines.Job? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("listener", "Connection to your server", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, "listener").setSmallIcon(android.R.drawable.stat_notify_sync_noanim).setContentTitle("Agents is listening").setContentText("You get a message the moment an agent has news.")
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_MIN).setContentIntent(open).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
        if (job?.isActive != true) job = scope.launch { listen() }
        return START_STICKY
    }

    private suspend fun listen() {
        val conn = getSharedPreferences("conn", Context.MODE_PRIVATE); val st = getSharedPreferences("state", Context.MODE_PRIVATE)
        var rev = 0L
        while (currentCoroutineContext().isActive) {
            val url = conn.getString("url", null); val key = conn.getString("key", null)
            if (url == null || key == null) { delay(10_000); continue }   // not connected yet
            val api = Api(url, key)
            try {
                val l = api.agents(rev); rev = l.rev
                var marker = st.getLong("notified", -1)
                if (marker < 0) { st.edit().putLong("notified", l.actLast).apply(); continue }
                if (l.actLast > marker) {
                    val (newest, items) = api.activity(after = marker, limit = 30)
                    if (!Notifier.appVisible) items.filter { it.kind != "system" && it.kind != "thought" }.sortedBy { it.id }.forEach { Notifier.show(this@ListenService, it) }
                    st.edit().putLong("notified", newest).apply()
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { rev = 0; delay(5_000) }   // offline or the server restarted: try again in a moment
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        fun start(ctx: Context) { try { androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, ListenService::class.java)) } catch (_: Exception) { /* Android refused a start from the background; the 15-minute check still works */ } }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, ListenService::class.java)) }
    }
}

/** Starts the listener again after a restart of the phone or an update of the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("notify", false)) ListenService.start(ctx)
    }
}
