package com.jarvis.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Purely on-demand: JARVIS calls readCurrentScreen()/clickByLabel()/globalAction()
 * only in direct response to a user command ("What is this?", "Click the search
 * button", "go back"). This service does not stream, log, or upload screen content —
 * onAccessibilityEvent is intentionally a no-op, and everything is processed locally.
 */
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        var instance: JarvisAccessibilityService? = null

        fun isEnabled(context: android.content.Context): Boolean {
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.contains(context.packageName)
        }
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally does not log/store events. Screen is only read on explicit request.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** "What is this?" / "Read this." / "Explain this page." */
    fun readCurrentScreenText(): String {
        val root = rootInActiveWindow ?: return "I can't see the current screen right now."
        val builder = StringBuilder()
        collectText(root, builder)
        return builder.toString().ifBlank { "This screen doesn't have readable text content." }
    }

    /** "Click the search button." Falls back to the nearest clickable ancestor when
     *  the matching text/description lives on a non-clickable child node — the common
     *  real-world layout (e.g. an icon Button whose label is a child TextView). */
    fun clickByLabel(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findNodeByLabel(root, label) ?: return false
        val clickable = nearestClickableSelfOrAncestor(target) ?: return false
        return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** "Go back" / "go home" / "show recent apps" — standard, user-authorized global
     *  navigation actions available to any enabled AccessibilityService. */
    fun globalAction(action: GlobalNavAction): Boolean {
        val id = when (action) {
            GlobalNavAction.BACK -> GLOBAL_ACTION_BACK
            GlobalNavAction.HOME -> GLOBAL_ACTION_HOME
            GlobalNavAction.RECENTS -> GLOBAL_ACTION_RECENTS
        }
        return performGlobalAction(id)
    }

    private fun nearestClickableSelfOrAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 10) {
            if (current.isClickable) return current
            current = current.parent
            hops++
        }
        return null
    }

    private fun collectText(node: AccessibilityNodeInfo, out: StringBuilder, depth: Int = 0) {
        if (depth > 40) return // guard against pathological trees
        node.text?.let { if (it.isNotBlank()) out.appendLine(it) }
        node.contentDescription?.let { if (it.isNotBlank()) out.appendLine(it) }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectText(it, out, depth + 1) }
        }
    }

    private fun findNodeByLabel(node: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        val text = node.text?.toString() ?: node.contentDescription?.toString()
        if (text != null && text.contains(label, ignoreCase = true)) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findNodeByLabel(child, label)?.let { return it }
            }
        }
        return null
    }
}

enum class GlobalNavAction { BACK, HOME, RECENTS }

