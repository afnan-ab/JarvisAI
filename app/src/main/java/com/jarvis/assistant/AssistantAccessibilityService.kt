package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

data class ScreenElement(val index: Int, val label: String, val clickable: Boolean, val editable: Boolean)

class AssistantAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AssistantAccessibilityService? = null
    }

    private var lastElements: List<AccessibilityNodeInfo> = emptyList()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun tapByText(label: String): Boolean {

    /**
     * Clicks a non-editable node whose visible text/content description exactly matches
     * the supplied label. This avoids clicking the WhatsApp search field itself after
     * the recipient name has been typed into it.
     */
    fun tapExactText(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findExactTextNode(root, label.trim().lowercase()) ?: return false
        return performClick(target)
    }

    private fun findExactTextNode(
        node: AccessibilityNodeInfo,
        label: String
    ): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.trim()?.lowercase()
        val desc = node.contentDescription?.toString()?.trim()?.lowercase()
        val exact = text == label || desc == label
        if (exact && !node.isEditable) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findExactTextNode(child, label)?.let { return it }
        }
        return null
    }

    /**
     * Sets text in the first editable node on the current screen.
     * WhatsApp's chat screen normally exposes only its message composer as editable.
     */
    fun typeIntoFirstEditableField(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val field = findEditableNode(root) ?: return false
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isEnabled) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findEditableNode(child)?.let { return it }
        }
        return null
    }

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

    fun activePackageName(): String = rootInActiveWindow?.packageName?.toString().orEmpty()

    fun readScreenText(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        collectText(root, sb)
        return sb.toString().trim()
    }

    private fun collectText(node: AccessibilityNodeInfo, sb: StringBuilder) {
        node.text?.let { sb.append(it).append(" ") }
        node.contentDescription?.let { sb.append(it).append(" ") }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectText(it, sb) }
        }
    }

    /** Enumerates tappable/editable elements on screen for the agent loop, indexed for reference. */
    fun listInteractiveElements(): List<ScreenElement> {
        val root = rootInActiveWindow ?: return emptyList()
        val elements = mutableListOf<ScreenElement>()
        val refs = mutableListOf<AccessibilityNodeInfo>()
        collectInteractive(root, elements, refs)
        lastElements = refs
        return elements
    }

    private fun collectInteractive(
        node: AccessibilityNodeInfo,
        elements: MutableList<ScreenElement>,
        refs: MutableList<AccessibilityNodeInfo>
    ) {
        val interactive = node.isClickable || node.isEditable || node.isCheckable
        if (interactive) {
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString()
            } else null
            val resId = node.viewIdResourceName?.substringAfterLast("/")

            val parts = mutableListOf<String>()
            node.text?.toString()?.let { if (it.isNotBlank()) parts.add(it) }
            hint?.let { if (it.isNotBlank()) parts.add("hint: $it") }
            node.contentDescription?.toString()?.let { if (it.isNotBlank()) parts.add("desc: $it") }
            resId?.let { if (it.isNotBlank()) parts.add("id: $it") }
            if (parts.isEmpty()) parts.add(node.className?.toString() ?: "element")

            val label = parts.joinToString(" | ").take(80)
            elements.add(ScreenElement(elements.size, label, node.isClickable, node.isEditable))
            refs.add(node)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectInteractive(it, elements, refs) }
        }
    }

    fun tapElement(index: Int): Boolean {
        val node = lastElements.getOrNull(index) ?: return false
        return performClick(node)
    }

    fun typeIntoElement(index: Int, text: String): Boolean {
        val node = lastElements.getOrNull(index) ?: return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scrollDown(): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findScrollable(root) ?: return false
        return scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findScrollable(child)?.let { return it }
            }
        }
        return null
    }

    fun pressBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun openRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun pullDownNotifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun tapAt(x: Float, y: Float): Boolean {
        if (x < 0f || y < 0f) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun captureScreenshot(callback: (android.graphics.Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        try {
                            val hwBitmap = android.graphics.Bitmap.wrapHardwareBuffer(
                                result.hardwareBuffer, result.colorSpace
                            )
                            result.hardwareBuffer.close()
                            val bitmap = hwBitmap?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                            callback(bitmap)
                        } catch (e: Exception) {
                            callback(null)
                        }
                    }
                    override fun onFailure(errorCode: Int) {
                        callback(null)
                    }
                }
            )
        } else {
            callback(null)
        }
    }

    fun typeIntoFocusedField(text: String): Boolean {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}
