package com.buildersledger.notify

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.buildersledger.LedgerApplication
import com.buildersledger.MainActivity
import com.buildersledger.R
import kotlinx.coroutines.launch

/** Fires when an upgrade finishes (or shortly before, if the player asked for a heads-up) and posts a notification. */
class UpgradeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(UpgradeAlarmScheduler.EXTRA_ID, 0L)
        val kind = intent.getIntExtra(UpgradeAlarmScheduler.EXTRA_KIND, UpgradeAlarmScheduler.KIND_DONE)
        val name = intent.getStringExtra(UpgradeAlarmScheduler.EXTRA_NAME).orEmpty().ifBlank { "An upgrade" }
        val village = intent.getStringExtra(UpgradeAlarmScheduler.EXTRA_VILLAGE).orEmpty()
        val lead = intent.getIntExtra(UpgradeAlarmScheduler.EXTRA_LEAD, 0)

        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val done = kind == UpgradeAlarmScheduler.KIND_DONE
        val title = if (done) "$name is finished" else "$name finishes in $lead min"
        val text = when {
            done && village.isNotBlank() -> "$village: that worker is free again. Time to start the next upgrade."
            done -> "That worker is free again. Time to start the next upgrade."
            else -> "Get ready to start the next one."
        }

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(
            context,
            if (done) UpgradeAlarmScheduler.CHANNEL_DONE else UpgradeAlarmScheduler.CHANNEL_SOON,
        )
            .setSmallIcon(R.drawable.ic_stat_hourglass)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        try {
            manager.notify((id * 2 + kind).toInt(), notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS was revoked between the check and the call; nothing else to do.
        }
    }
}

/** Alarms are lost on reboot, app update and when exact-alarm permission changes, so register them again. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as LedgerApplication
        val pending = goAsync()
        app.appScope.launch {
            try {
                app.container.repository.rescheduleAll()
            } finally {
                pending.finish()
            }
        }
    }
}
