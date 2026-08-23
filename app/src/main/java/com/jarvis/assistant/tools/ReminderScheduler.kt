package com.jarvis.assistant.tools

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * "Remind me to call the dentist in an hour" — a real, working reminder, not a
 * fire-and-forget intent hoping some other app catches it. Uses AlarmManager to
 * wake the device at the right time (respecting Doze via *AndAllowWhileIdle) and
 * ReminderReceiver posts a local notification. Everything stays on-device.
 *
 * Android 12+ (API 31) gates *exact* alarms behind a special permission the user
 * must grant manually (Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) — this class
 * checks canScheduleExactAlarms() first and falls back to an inexact alarm rather
 * than crashing with a SecurityException, since an approximately-on-time reminder
 * beats a crash.
 */
class ReminderScheduler(private val context: Context) {

    fun schedule(text: String, triggerAtMillis: Long): ToolResult {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return ToolResult.Failure("Alarm system unavailable.")

        val requestCode = triggerAtMillis.toInt() xor text.hashCode()
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_TEXT, text)
            putExtra(ReminderReceiver.EXTRA_ID, requestCode)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, flags)

        val canExact = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()
        return try {
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                ToolResult.Success("Okay, I'll remind you: $text.")
            } else {
                // Inexact fallback — still fires, just not to-the-second, and needs no
                // special permission. Honest degraded mode rather than a crash.
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                ToolResult.Success("I'll remind you: $text — it might be off by a few minutes since exact alarms aren't enabled for JARVIS.")
            }
        } catch (e: SecurityException) {
            ToolResult.NeedsPermission(
                "android.permission.SCHEDULE_EXACT_ALARM",
                "JARVIS needs the \"Alarms & reminders\" permission to remind you at an exact time."
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "jarvis_reminders"
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT) ?: "Reminder"
        val id = intent.getIntExtra(EXTRA_ID, 0)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ReminderScheduler.CHANNEL_ID, "JARVIS reminders", NotificationManager.IMPORTANCE_HIGH
            )
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setContentTitle("JARVIS reminder")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        context.getSystemService(NotificationManager::class.java)?.notify(id, notification)
    }

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_ID = "id"
    }
}
