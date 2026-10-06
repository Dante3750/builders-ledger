package com.buildersledger.notify

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * Schedules one alarm when an upgrade finishes and, optionally, one reminder a few minutes before.
 *
 * Exact alarms need a permission the player may not have granted (Android 13+ denies it by default for
 * apps like this one). Without it the alarm still fires, but Android may delay it by several minutes.
 */
class UpgradeAlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Upgrade finished", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A builder, lab or other worker is free again."
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SOON, "Upgrade finishing soon", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Optional heads-up shortly before an upgrade finishes."
            }
        )
    }

    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()

    fun schedule(upgradeId: Long, name: String, villageName: String, endsAtMs: Long, leadMinutes: Int) {
        cancel(upgradeId)
        val now = System.currentTimeMillis()
        if (endsAtMs > now) {
            set(endsAtMs, pending(upgradeId, KIND_DONE, name, villageName, 0, create = true)!!)
        }
        val leadAt = endsAtMs - leadMinutes * 60_000L
        if (leadMinutes > 0 && leadAt > now) {
            set(leadAt, pending(upgradeId, KIND_SOON, name, villageName, leadMinutes, create = true)!!)
        }
    }

    fun cancel(upgradeId: Long) {
        for (kind in intArrayOf(KIND_DONE, KIND_SOON)) {
            val pi = pending(upgradeId, kind, "", "", 0, create = false) ?: continue
            alarmManager.cancel(pi)
            pi.cancel()
        }
    }

    private fun set(atMs: Long, pi: PendingIntent) {
        try {
            if (canScheduleExact()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            }
        } catch (e: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        }
    }

    private fun pending(
        upgradeId: Long,
        kind: Int,
        name: String,
        villageName: String,
        leadMinutes: Int,
        create: Boolean,
    ): PendingIntent? {
        val intent = Intent(context, UpgradeAlarmReceiver::class.java).apply {
            action = ACTION_ALARM
            // action + data make the intent unique per upgrade and kind, so it can be found again to cancel it
            data = Uri.parse("ledger://upgrade/$upgradeId/$kind")
            putExtra(EXTRA_ID, upgradeId)
            putExtra(EXTRA_KIND, kind)
            putExtra(EXTRA_NAME, name)
            putExtra(EXTRA_VILLAGE, villageName)
            putExtra(EXTRA_LEAD, leadMinutes)
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or
            if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(context, (upgradeId * 2 + kind).toInt(), intent, flags)
    }

    companion object {
        const val CHANNEL_DONE = "upgrades_done"
        const val CHANNEL_SOON = "upgrades_soon"
        const val ACTION_ALARM = "com.buildersledger.UPGRADE_ALARM"
        const val EXTRA_ID = "upgrade_id"
        const val EXTRA_KIND = "kind"
        const val EXTRA_NAME = "name"
        const val EXTRA_VILLAGE = "village"
        const val EXTRA_LEAD = "lead"
        const val KIND_DONE = 0
        const val KIND_SOON = 1
    }
}
