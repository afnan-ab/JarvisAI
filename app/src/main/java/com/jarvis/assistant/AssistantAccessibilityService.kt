package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

data class ScreenElement(
    val index: Int,
    val label: String,
    val clickable: Boolean,
    val editable: Boolean
)

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
        val root = rootInActiveWindow ?: return false
        val target = findNodeByText(root, label.lowercase()) ?: return false
        return performClick(target)
    }

    /** Finds a visible non-editable text/description and clicks its clickable parent. */
    fun tapTextResult(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findNonEditableContainingText(root, label.trim().lowercase()) ?: return false
        return performClick(target)
    }

    /** Opens WhatsApp Search using stable resource IDs first, then accessibility labels. */
    fun tapSearchControl(): Boolean {
        val root = rootInActiveWindow ?: return false

        findByViewId(root, "com.whatsapp:id/search_bar_inner_layout")
            .firstOrNull()
            ?.let { if (performClick(it)) return true }

        findByViewId(root, "com.whatsapp:id/menuitem_search")
            .firstOrNull()
            ?.let { if (performClick(it)) return true }

        val target = findSearchNode(root) ?: return false
        return performClick(target)
    }

    fun typeIntoWhatsAppSearch(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val field = findByViewId(root, "com.whatsapp:id/search_input").firstOrNull()
            ?: findEditableNode(root)
            ?: return false
        return setNodeText(field, text)
    }

    fun typeIntoWhatsAppComposer(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val field = findByViewId(root, "com.whatsapp:id/entry").firstOrNull()
            ?: findEditableNode(root)
            ?: return false
        return setNodeText(field, text)
    }

    fun hasWhatsAppComposer(): Boolean {
        val root = rootInActiveWindow ?: return false
        return findByViewId(root, "com.whatsapp:id/entry").isNotEmpty() ||
            findEditableNode(root) != null
    }

    fun tapWhatsAppSearchResult(recipient: String): Boolean {
        val root = rootInActiveWindow ?: return false

        // Prefer the actual conversation row. Clicking the avatar/profile button
        // can open contact details instead of the chat.
        val rows = findByViewId(root, "com.whatsapp:id/contact_row_container")
        for (row in rows) {
            val rowText = nodeText(row).lowercase()
            if (rowText.contains(recipient.trim().lowercase())) {
                if (performClick(row)) return true
            }
        }

        // Newer versions may expose the contact name without the row ID.
        val nameNodes = findByViewId(root, "com.whatsapp:id/conversations_row_contact_name")
        for (node in nameNodes) {
            val text = node.text?.toString()?.trim().orEmpty()
            if (text.equals(recipient.trim(), ignoreCase = true) ||
                text.contains(recipient.trim(), ignoreCase = true)
            ) {
                if (clickNearestRow(node)) return true
            }
        }

        // Fallback: find exact name and deliberately prefer the larger clickable
        // ancestor rather than an avatar/profile child.
        val exact = findExactTextNode(root, recipient.trim().lowercase()) ?: return false
        return clickNearestRow(exact)
    }

    fun tapWhatsAppSend(): Boolean {
        val root = rootInActiveWindow ?: return false
        findByViewId(root, "com.whatsapp:id/send")
            .firstOrNull()
            ?.let { if (performClick(it)) return true }
        return tapByText("send")
    }

    /** Types into the first editable field currently exposed by the accessibility tree. */
    fun typeIntoFirstEditableField(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val field = findEditableNode(root) ?: return false
        return setNodeText(field, text)
    }

    fun hasEditableField(): Boolean {
        val root = rootInActiveWindow ?: return false
        return findEditableNode(root) != null
    }

    private fun setNodeText(field: AccessibilityNodeInfo, text: String): Boolean {
        if (!field.isEnabled) return false
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findByViewId(
        node: AccessibilityNodeInfo,
        id: String
    ): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        if (node.viewIdResourceName == id) result.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { result.addAll(findByViewId(it, id)) }
        }
        return result
    }

    private fun nodeText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        collectText(node, sb)
        return sb.toString().trim()
    }

    private fun clickNearestRow(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var fallback: AccessibilityNodeInfo? = null
        repeat(7) {
            val c = current ?: return@repeat
            if (c.isClickable && c.isEnabled) {
                val id = c.viewIdResourceName.orEmpty()
                if (id.endsWith("contact_row_container")) {
                    return c.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                if (fallback == null) fallback = c
            }
            current = c.parent
        }
        return fallback?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    fun screenContainsText(value: String): Boolean {
        return readScreenText().lowercase().contains(value.lowercase())
    }

    fun tapExactText(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findExactTextNode(root, label.trim().lowercase()) ?: return false
        return performClick(target)
    }

    private fun findNodeByText(
        node: AccessibilityNodeInfo,
        label: String
    ): AccessibilityNodeInfo? {
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

    private fun findNonEditableContainingText(
        node: AccessibilityNodeInfo,
        label: String
    ): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.trim()?.lowercase()
        val desc = node.contentDescription?.toString()?.trim()?.lowercase()
        if (!node.isEditable &&
            ((text != null && text.contains(label)) || (desc != null && desc.contains(label)))
        ) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNonEditableContainingText(child, label)?.let { return it }
        }
        return null
    }

    private fun findExactTextNode(
        node: AccessibilityNodeInfo,
        label: String
    ): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.trim()?.lowercase()
        val desc = node.contentDescription?.toString()?.trim()?.lowercase()
        if (!node.isEditable && (text == label || desc == label)) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findExactTextNode(child, label)?.let { return it }
        }
        return null
    }

    private fun findSearchNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.trim()?.lowercase().orEmpty()
        val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.trim()?.lowercase().orEmpty()
        } else ""

        val looksLikeSearch = text.contains("search") ||
            desc.contains("search") ||
            hint.contains("search")

        if (looksLikeSearch && (node.isClickable || node.isEditable || desc.contains("search"))) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findSearchNode(child)?.let { return it }
        }
        return null
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isEnabled) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findEditableNode(child)?.let { return it }
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

    fun activePackageName(): String =
        rootInActiveWindow?.packageName?.toString().orEmpty()

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

            elements.add(
                ScreenElement(
                    elements.size,
                    parts.joinToString(" | ").take(80),
                    node.isClickable,
                    node.isEditable
                )
            )
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
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )
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

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun pullDownNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

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
                                result.hardwareBuffer,
                                result.colorSpace
                            )
                            result.hardwareBuffer.close()
                            callback(
                                hwBitmap?.copy(
                                    android.graphics.Bitmap.Config.ARGB_8888,
                                    false
                                )
                            )
                        } catch (_: Exception) {
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
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return false
        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}
