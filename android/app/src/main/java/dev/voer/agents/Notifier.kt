package dev.voer.agents

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

/** Shows phone notifications for new results and messages. Android lets background work run about every 15 minutes;
 *  for instant messages add ntfy or Telegram in Settings. */
object Notifier {
    /** true while the app is on screen: then new results show up inside the app and no pop-up is needed */
    @Volatile var appVisible = false
    private const val CHANNEL = "agents"
    private const val WORK = "agents-poll"

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<PollWorker>(15, TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }
    fun cancel(ctx: Context) { WorkManager.getInstance(ctx).cancelUniqueWork(WORK) }
    fun enable(ctx: Context, lastAct: Long) { resetMarker(ctx, lastAct); schedule(ctx); ListenService.start(ctx) }
    fun disable(ctx: Context) { cancel(ctx); ListenService.stop(ctx) }

    fun resetMarker(ctx: Context, last: Long) { ctx.getSharedPreferences("state", Context.MODE_PRIVATE).edit().putLong("notified", last).apply() }

    fun show(ctx: Context, e: Act) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Agents", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle(e.title.ifBlank { "Agents" })
            .setContentText(e.body.take(200)).setStyle(NotificationCompat.BigTextStyle().bigText(e.body.take(1200))).setContentIntent(open).setAutoCancel(true).build()
        try { nm.notify(1000 + e.id.toInt(), n) } catch (_: SecurityException) { /* permission was taken away */ }
    }
}

class PollWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        val conn = c.getSharedPreferences("conn", Context.MODE_PRIVATE)
        val api = Api(conn.getString("url", null) ?: return Result.success(), conn.getString("key", null) ?: return Result.success())
        val st = c.getSharedPreferences("state", Context.MODE_PRIVATE)
        val last = st.getLong("notified", -1)
        return try {
            val (newest, items) = api.activity(after = if (last < 0) null else last, limit = 30)
            if (last >= 0 && !Notifier.appVisible) items.filter { it.kind != "system" }.sortedBy { it.id }.forEach { Notifier.show(c, it) }
            st.edit().putLong("notified", newest).apply()
            Result.success()
        } catch (e: ApiError) { Result.retry() }
    }
}
