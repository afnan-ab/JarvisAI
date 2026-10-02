package com.jarvis.assistant

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class JarvisNotificationListener : NotificationListenerService() {
    companion object {
        @Volatile var instance: JarvisNotificationListener? = null
        @Volatile var lastNotification: String = "No notification captured."
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
            lastNotification = title + if (text.isNotBlank()) ": $text" else ""
        }
    }
}
