package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * This is what lets Jarvis interact with WHATEVER is on screen, not just
 * apps it launches itself - tapping buttons, scrolling, reading text out
 * of other apps' UI. The user has to turn it on manually in
 * Settings > Accessibility > Jarvis (Android blocks apps from silently
 * granting themselves this, for good reason - it's a powerful permission).
 *
 * Kept as a singleton reference so CommandProcessor / MainActivity can
 * reach it once it's running.
 */
class AssistantAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AssistantAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Left intentionally quiet by default - wire this up if you want
        // Jarvis to react to what appears on screen (e.g. read incoming
        // notifications aloud). Keep it narrow and permission-respecting.
    }

    override fun onInterrupt() {}

    /** Finds the first clickable node whose visible text/description contains [label] and taps it. */
    fun tapByText(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findNodeByText(root, label.lowercase()) ?: return false
        return performClick(target)
    }

    private fun findNodeByText(node: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.lowercase()
        val desc = node.contentDescription?.toString()?.lowercase()
        if ((text != null && text.contains(label)) || (desc != null && desc.contains(label))) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodeByText(child, label)?.let { return it }
        }
        return null
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        return false
    }

    /** Reads back all visible text on the current screen (rough OCR-free approximation). */
    fun readScreenText(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        collectText(root, sb)
        return sb.toString().trim()
    }

    private fun collectText(node: AccessibilityNodeInfo, sb: StringBuilder) {
        node.text?.let { sb.append(it).append(" ") }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectText(it, sb) }
        }
    }

    fun pressBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun openRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun pullDownNotifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun tapAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, null, null)
    }
}
