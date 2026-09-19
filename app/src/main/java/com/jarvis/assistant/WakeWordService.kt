package com.jarvis.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import java.util.Locale

/**
 * Listens continuously in the background for the word "Jarvis" using Android's
 * own speech recognizer in a loop - no external wake-word engine or account
 * needed. Less battery-efficient than a dedicated keyword-spotting engine and
 * may take a second or two to notice the wake word each cycle, but works with
 * nothing but what's already on the phone.
 */
class WakeWordService : Service() {

    private var recognizer: SpeechRecognizer? = null
    private val channelId = "jarvis_wake_word"
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()
        running = true
        listenCycle()
    }

    private fun startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Jarvis Wake Word", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Jarvis is listening")
            .setContentText("Say \"Jarvis\" to wake me up")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun listenCycle() {
        if (!running) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            stopSelf()
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.lowercase().orEmpty()
                    if (text.contains("jarvis")) {
                        onWakeWordDetected()
                    }
                    handler.postDelayed({ listenCycle() }, 400)
                }
                override fun onError(error: Int) {
                    handler.postDelayed({ listenCycle() }, 800)
                }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        recognizer?.startListening(intent)
    }

    private fun onWakeWordDetected() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra("wake_word_triggered", true)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        running = false
        recognizer?.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
