package com.jarvis.assistant.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.jarvis.assistant.accessibility.GlobalNavAction
import com.jarvis.assistant.accessibility.JarvisAccessibilityService

/**
 * Every action here uses public Android APIs / intents. Nothing bypasses the
 * permission model, sandboxing, or system dialogs (calls still go through the
 * dialer's own confirmation where Android requires it, etc). If a required
 * permission is missing, ToolResult.NeedsPermission is returned — the caller
 * (JarvisForegroundService) is responsible for prompting, never silently
 * pretending success.
 */
sealed class ToolResult {
    data class Success(val message: String) : ToolResult()
    data class NeedsPermission(val permission: String, val rationale: String) : ToolResult()
    data class Failure(val reason: String) : ToolResult()
}

enum class MediaAction { PLAY_PAUSE, NEXT, PREVIOUS, STOP }

class ToolExecutor(private val context: Context) {

    fun openApp(appNameOrPackage: String): ToolResult {
        val pm = context.packageManager
        val pkg = resolvePackage(appNameOrPackage, pm)
            ?: return ToolResult.Failure("Couldn't find an app matching \"$appNameOrPackage\".")
        val launchIntent = pm.getLaunchIntentForPackage(pkg)
            ?: return ToolResult.Failure("\"$appNameOrPackage\" doesn't expose a launchable screen.")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return ToolResult.Success("Opening $appNameOrPackage.")
    }

    fun callContact(target: String): ToolResult {
        if (!hasPermission(android.Manifest.permission.CALL_PHONE)) {
            return ToolResult.NeedsPermission(android.Manifest.permission.CALL_PHONE, "JARVIS needs Phone permission to place calls directly.")
        }
        // Resolution of "target" (name -> number) happens via ContactsResolver (contacts read permission).
        val number = ContactsResolver(context).resolveNumber(target)
            ?: return ToolResult.Failure("Couldn't find a number for \"$target\".")
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ToolResult.Success("Calling $target.")
    }

    fun setAlarm(hour: Int, minute: Int, label: String? = null): ToolResult {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            return ToolResult.Failure("No clock app available to set the alarm.")
        }
        context.startActivity(intent)
        return ToolResult.Success("Alarm set for %02d:%02d.".format(hour, minute))
    }

    fun setTimer(seconds: Int, label: String? = null): ToolResult {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            return ToolResult.Failure("No clock app available to set a timer.")
        }
        context.startActivity(intent)
        return ToolResult.Success("Timer set for $seconds seconds.")
    }

    fun createCalendarEvent(title: String, startMillis: Long, endMillis: Long): ToolResult {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ToolResult.Success("Opening calendar to confirm \"$title\".")
    }

    fun webSearch(query: String): ToolResult {
        val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra("query", query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ToolResult.Success("Searching for $query.")
    }

    // --- Camera/photos ---------------------------------------------------

    fun openCamera(): ToolResult {
        // Launches the device's default camera app in still-photo capture mode —
        // legitimate, no CAMERA permission needed for this (the camera app itself
        // handles the actual capture and its own permission).
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            return ToolResult.Failure("No camera app is available.")
        }
        context.startActivity(intent)
        return ToolResult.Success("Opening the camera.")
    }

    // --- Files -------------------------------------------------------------

    /** Android has no single universal "open the files app" intent action across
     *  OEMs. Closest legitimate alternative: try the common system file managers
     *  by package first (present on stock Android and most OEM skins), then fall
     *  back to the standard document-picker chooser, which always exists. */
    fun openFiles(): ToolResult {
        val knownFileManagerPackages = listOf(
            "com.google.android.documentsui", // AOSP/Pixel "Files"
            "com.android.documentsui",
            "com.google.android.apps.nbu.files" // "Files by Google"
        )
        val pm = context.packageManager
        for (pkg in knownFileManagerPackages) {
            val launch = pm.getLaunchIntentForPackage(pkg) ?: continue
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            return ToolResult.Success("Opening Files.")
        }
        // Fallback: standard SAF document picker, guaranteed present on any compliant device.
        val fallback = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (fallback.resolveActivity(pm) == null) {
            return ToolResult.Failure("No file manager or document picker is available.")
        }
        context.startActivity(fallback)
        return ToolResult.Success("Opening a file picker — no dedicated Files app was found.")
    }

    // --- Media control -------------------------------------------------------

    /** Dispatches a real media key event to whatever app currently holds an active
     *  media session — the standard, app-agnostic way to control playback (the same
     *  mechanism a Bluetooth headset's play/pause button uses), rather than needing
     *  per-app integration. */
    fun mediaControl(action: MediaAction): ToolResult {
        val keyCode = when (action) {
            MediaAction.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            MediaAction.STOP -> KeyEvent.KEYCODE_MEDIA_STOP
        }
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ToolResult.Failure("Audio system unavailable.")
        val eventTime = System.currentTimeMillis()
        return try {
            audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0))
            audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0))
            ToolResult.Success(
                when (action) {
                    MediaAction.PLAY_PAUSE -> "Toggled playback."
                    MediaAction.NEXT -> "Skipped to the next track."
                    MediaAction.PREVIOUS -> "Went back a track."
                    MediaAction.STOP -> "Stopped playback."
                }
            )
        } catch (e: Exception) {
            ToolResult.Failure("Couldn't reach a media session: ${e.message}")
        }
    }

    // --- Back/home/recents navigation ----------------------------------------

    /** Requires the Accessibility service to be enabled — that's the only Android-
     *  legitimate way for a third-party app to trigger system navigation on another
     *  app's behalf. If it's off, this returns NeedsPermission rather than silently
     *  no-op'ing. */
    fun navigate(action: GlobalNavAction): ToolResult {
        val service = JarvisAccessibilityService.instance
            ?: return ToolResult.NeedsPermission(
                "android.permission.BIND_ACCESSIBILITY_SERVICE",
                "JARVIS needs the Accessibility service turned on to control system navigation."
            )
        return if (service.globalAction(action)) {
            ToolResult.Success(
                when (action) {
                    GlobalNavAction.BACK -> "Went back."
                    GlobalNavAction.HOME -> "Went home."
                    GlobalNavAction.RECENTS -> "Showing recent apps."
                }
            )
        } else {
            ToolResult.Failure("That navigation action didn't go through.")
        }
    }

    // --- Settings pages --------------------------------------------------------

    fun openSettings(section: String): ToolResult {
        val action = when {
            section.contains("wifi", true) || section.contains("wi-fi", true) -> Settings.ACTION_WIFI_SETTINGS
            section.contains("bluetooth", true) -> Settings.ACTION_BLUETOOTH_SETTINGS
            section.contains("display", true) || section.contains("brightness", true) -> Settings.ACTION_DISPLAY_SETTINGS
            section.contains("sound", true) || section.contains("volume", true) -> Settings.ACTION_SOUND_SETTINGS
            section.contains("battery", true) -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            section.contains("app", true) -> Settings.ACTION_APPLICATION_SETTINGS
            section.contains("location", true) || section.contains("gps", true) -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            section.contains("network", true) || section.contains("data", true) -> Settings.ACTION_WIRELESS_SETTINGS
            section.contains("date", true) || section.contains("time", true) -> Settings.ACTION_DATE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        if (intent.resolveActivity(context.packageManager) == null) {
            return ToolResult.Failure("Couldn't open that settings screen.")
        }
        context.startActivity(intent)
        return ToolResult.Success("Opening settings.")
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun resolvePackage(query: String, pm: PackageManager): String? {
        val apps = if (android.os.Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.getInstalledApplications(0)
        }
        return apps.firstOrNull { pm.getApplicationLabel(it).toString().contains(query, ignoreCase = true) }?.packageName
    }
}

class ContactsResolver(private val context: Context) {
    fun resolveNumber(name: String): String? {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return null

        val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(projection[0])
            val numberIdx = cursor.getColumnIndex(projection[1])
            while (cursor.moveToNext()) {
                val contactName = cursor.getString(nameIdx)
                if (contactName.contains(name, ignoreCase = true)) {
                    return cursor.getString(numberIdx)
                }
            }
        }
        return null
    }
}
