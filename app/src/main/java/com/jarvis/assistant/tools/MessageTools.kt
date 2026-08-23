package com.jarvis.assistant.tools

import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat

/**
 * Real SMS sending via SmsManager — Android's own permission dialogs and default-SMS-app
 * rules still apply (Android will show its own confirmation UI on some OEM skins for
 * non-default SMS apps sending silently; this class never attempts to suppress that).
 * "Messages where permitted" from the spec — RCS/carrier chat apps don't expose a public
 * send API, so this covers standard SMS, which is the legitimate cross-device path.
 */
class MessageTools(private val context: Context) {

    fun sendText(contactName: String, body: String): ToolResult {
        if (!hasPermission(android.Manifest.permission.SEND_SMS)) {
            return ToolResult.NeedsPermission(
                android.Manifest.permission.SEND_SMS,
                "JARVIS needs SMS permission to send texts on your behalf."
            )
        }
        val number = ContactsResolver(context).resolveNumber(contactName)
            ?: return ToolResult.Failure("Couldn't find a number for \"$contactName\".")

        return try {
            val smsManager = context.getSystemService(SmsManager::class.java)
            val parts = smsManager.divideMessage(body)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(number, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(number, null, body, null, null)
            }
            ToolResult.Success("Sent to $contactName.")
        } catch (e: Exception) {
            ToolResult.Failure("Message failed to send: ${e.message}")
        }
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
