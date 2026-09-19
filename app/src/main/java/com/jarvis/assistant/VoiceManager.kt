package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

class VoiceManager(
    private val context: Context,
    private val onSpeechResult: (String) -> Unit,
    private val onListenStart: () -> Unit = {},
    private val onListenEnd: () -> Unit = {},
    private val onNoSpeechDetected: () -> Unit = {}
) {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val settings = VoiceSettings(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingDoneCallback: (() -> Unit)? = null

    fun init() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                applySettings()
                setupUtteranceListener()
                ttsReady = true
            }
        }
    }

    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                val callback = pendingDoneCallback
                pendingDoneCallback = null
                callback?.let { mainHandler.post(it) }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                val callback = pendingDoneCallback
                pendingDoneCallback = null
                callback?.let { mainHandler.post(it) }
            }
        })
    }

    fun applySettings() {
        val wantMale = settings.getPreferMale()
        val voice = tts?.voices?.firstOrNull {
            it.name.contains(if (wantMale) "male" else "female", ignoreCase = true) &&
                !it.isNetworkConnectionRequired
        }
        voice?.let { tts?.voice = it }
        tts?.setPitch(settings.getPitch())
        tts?.setSpeechRate(settings.getRate())
    }

    private fun containsDevanagari(text: String): Boolean = text.any { it.code in 0x0900..0x097F }

    private fun recognitionLocale(): Locale = when (settings.getRecognitionLang()) {
        "en-IN" -> Locale("en", "IN")
        "hi-IN" -> Locale("hi", "IN")
        else -> Locale.getDefault()
    }

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onSpeechResult("(speech recognition not available on this device)")
            return
        }
        onListenStart()
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    onListenEnd()
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull().orEmpty()
                    if (text.isNotBlank()) {
                        onSpeechResult(text)
                    } else {
                        onNoSpeechDetected()
                    }
                }
                override fun onError(error: Int) {
                    onListenEnd()
                    onNoSpeechDetected()
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognitionLocale())
        }
        recognizer?.startListening(intent)
    }

    fun stopListening() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    fun speak(text: String, onDone: () -> Unit = {}) {
        if (ttsReady) {
            val locale = if (containsDevanagari(text)) Locale("hi", "IN") else Locale("en", "IN")
            val result = tts?.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.getDefault())
            }
            pendingDoneCallback = onDone
            val id = UUID.randomUUID().toString()
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        } else {
            onDone()
        }
    }

    fun release() {
        recognizer?.destroy()
        tts?.shutdown()
    }
}
