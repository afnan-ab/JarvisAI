package com.jarvis.assistant

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.ArrayDeque

class JarvisNotificationListener : NotificationListenerService() {
    companion object {
        @Volatile var instance: JarvisNotificationListener? = null
        @Volatile var lastNotification: String = "No notification captured."
        private val recent = ArrayDeque<String>(20)

        @Synchronized
        fun summary(): String {
            return recent.firstOrNull() ?: lastNotification
        }

        @Synchronized
        fun recentSummary(): String {
            return if (recent.isEmpty()) "No recent notifications." else recent.take(5).joinToString(" | ")
        }

        @Synchronized
        private fun addRecent(value: String) {
            recent.remove(value)
            recent.addFirst(value)
            while (recent.size > 20) recent.removeLast()
            lastNotification = value
        }
    }

    override fun onListenerConnected() {
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isNotBlank() || text.isNotBlank()) {
            val value = title + if (text.isNotBlank()) ": $text" else ""
            addRecent(value)
        }
    }
}
