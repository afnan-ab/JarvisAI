package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

class VoiceManager(
    private val context: Context,
    private val onSpeechResult: (String) -> Unit,
    private val onListenStart: () -> Unit = {},
    private val onListenEnd: () -> Unit = {}
) {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val settings = VoiceSettings(context)

    fun init() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                applySettings()
                ttsReady = true
            }
        }
    }

    fun applySettings() {
        tts?.language = Locale.getDefault()
        val wantMale = settings.getPreferMale()
        val voice = tts?.voices?.firstOrNull {
            it.name.contains(if (wantMale) "male" else "female", ignoreCase = true) &&
                !it.name.contains("female", ignoreCase = true) == wantMale &&
                !it.isNetworkConnectionRequired
        }
        voice?.let { tts?.voice = it }
        tts?.setPitch(settings.getPitch())
        tts?.setSpeechRate(settings.getRate())
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
                    if (text.isNotBlank()) onSpeechResult(text)
                }
                override fun onError(error: Int) { onListenEnd() }
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

    fun speak(text: String) {
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_utterance")
        }
    }

    fun release() {
        recognizer?.destroy()
        tts?.shutdown()
    }
}
