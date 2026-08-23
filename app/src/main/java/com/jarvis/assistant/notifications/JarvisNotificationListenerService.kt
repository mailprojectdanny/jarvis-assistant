package com.jarvis.assistant.notifications

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

data class JarvisNotification(
    val id: Int,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postTimeMs: Long,
    val sbn: StatusBarNotification
)

/**
 * Requires the user to grant "Notification access" manually in system Settings —
 * Android deliberately makes this a special-access permission that can't be granted
 * via a normal runtime prompt, precisely because it's sensitive. JARVIS only reads
 * notification content into memory (never written to disk, never sent anywhere) and
 * only surfaces it when the user actually asks ("what are my notifications",
 * "reply to that", "dismiss it").
 */
class JarvisNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        refreshSnapshot()
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        refreshSnapshot()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        refreshSnapshot()
    }

    private fun refreshSnapshot() {
        val list = runCatching { activeNotifications }.getOrNull() ?: return
        val pm = packageManager
        latest = list
            .filter { it.packageName != packageName } // don't surface our own foreground-service notification
            .mapNotNull { sbn ->
                val extras = sbn.notification.extras
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                if (title.isBlank() && text.isBlank()) return@mapNotNull null
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
                }.getOrDefault(sbn.packageName)
                JarvisNotification(sbn.id, sbn.packageName, label, title, text, sbn.postTime, sbn)
            }
            .sortedByDescending { it.postTimeMs }
    }

    fun dismiss(notification: JarvisNotification): Boolean =
        runCatching { cancelNotification(notification.sbn.key); true }.getOrDefault(false)

    /** Uses the notification's own inline-reply RemoteInput action where the source
     *  app supports it (e.g. messaging apps) — the same mechanism the system shade
     *  uses, so it respects whatever that app's send behavior actually is. */
    fun replyTo(notification: JarvisNotification, text: String): Boolean {
        val replyAction = notification.sbn.notification.actions?.firstOrNull { action ->
            action.remoteInputs?.any { it.allowFreeFormInput } == true
        } ?: return false
        val remoteInputs = replyAction.remoteInputs ?: return false

        val intent = Intent()
        val bundle = android.os.Bundle()
        remoteInputs.forEach { bundle.putCharSequence(it.resultKey, text) }
        RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)

        return try {
            replyAction.actionIntent.send(this, 0, intent)
            true
        } catch (e: PendingIntent.CanceledException) {
            false
        }
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        var instance: JarvisNotificationListenerService? = null
            private set

        @Volatile
        private var latest: List<JarvisNotification> = emptyList()

        fun currentSnapshot(): List<JarvisNotification> = latest

        fun isAccessGranted(context: android.content.Context): Boolean {
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            return enabled.contains(context.packageName)
        }
    }
}
