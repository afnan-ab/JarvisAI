package com.jarvis.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

class WakeWordService : Service(), RecognitionListener {

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private val channelId = "jarvis_wake_word"

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification("Loading wake word model...")
        StorageService.unpack(
            this, "model-en-us", "model",
            { loadedModel ->
                model = loadedModel
                startListening()
            },
            { exception ->
                updateNotification("Model load failed: ${exception.message}")
            }
        )
    }

    private fun startListening() {
        try {
            val rec = Recognizer(model, 16000.0f)
            speechService = SpeechService(rec, 16000.0f)
            speechService?.startListening(this)
            updateNotification("Say \"Jarvis\" to wake me up")
        } catch (e: Exception) {
            updateNotification("Listener error: ${e.message}")
        }
    }

    override fun onPartialResult(hypothesis: String?) {
        checkForWakeWord(hypothesis)
    }

    override fun onResult(hypothesis: String?) {
        checkForWakeWord(hypothesis)
    }

    override fun onFinalResult(hypothesis: String?) {
        checkForWakeWord(hypothesis)
    }

    private fun checkForWakeWord(hypothesis: String?) {
        if (hypothesis == null) return
        try {
            val json = JSONObject(hypothesis)
            val text = (json.optString("text", "") + " " + json.optString("partial", "")).lowercase()
            if (text.contains("jarvis")) {
                onWakeWordDetected()
            }
        } catch (e: Exception) {}
    }

    override fun onError(exception: Exception?) {
        updateNotification("Recognition error: ${exception?.message}")
    }

    override fun onTimeout() {}

    private fun onWakeWordDetected() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra("wake_word_triggered", true)
        }
        startActivity(intent)
    }

    private fun startForegroundWithNotification(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Jarvis Wake Word", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, channelId)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(1, buildNotification(text))
    }

    override fun onDestroy() {
        speechService?.stop()
        speechService?.shutdown()
        model?.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
