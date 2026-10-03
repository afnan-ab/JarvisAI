package com.jarvis.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import org.json.JSONObject
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Local Vosk microphone engine.
 * Unlike Android SpeechRecognizer, AudioRecord does not start the system
 * speech-recognition UI/earcon, so wake detection is silent.
 */
class VoskWakeEngine(private val context: Context) {

    interface Listener {
        fun onWake()
        fun onCommand(text: String)
        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var recorder: AudioRecord? = null
    private var recognizer: Recognizer? = null
    private var worker: Thread? = null
    private var running = false
    private var commandMode = false
    private var released = false

    fun startWake(listener: Listener) {
        currentListener = listener
        commandMode = false
        ensureModel { startCapture(listener) }
    }

    fun startCommand(listener: Listener) {
        currentListener = listener
        commandMode = true
        ensureModel { startCapture(listener) }
    }

    private fun ensureModel(ready: () -> Unit) {
        if (model != null) {
            ready()
            return
        }
        try {
            StorageService.unpack(
                context,
                "model-en-us",
                "jarvis-vosk",
                { loaded ->
                    model = loaded
                    main.post { ready() }
                },
                { error ->
                    main.post {
                        currentListener?.onError(
                            "Vosk model: ${error.message ?: "load failed"}"
                        )
                    }
                }
            )
        } catch (t: Throwable) {
            main.post {
                currentListener?.onError("Vosk model: ${t.message ?: "load failed"}")
            }
        }
    }

    private var currentListener: Listener? = null

    private fun startCapture(listener: Listener) {
        stop()
        currentListener = listener

        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            listener.onError("Microphone permission is required")
            return
        }

        val sampleRate = 16000
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            listener.onError("Microphone is unavailable on this device")
            return
        }
        val bufferSize = maxOf(minBuffer * 2, 4096)

        try {
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            val loadedModel = model
            if (loadedModel == null) {
                listener.onError("Vosk model is not loaded")
                stop()
                return
            }
            recognizer = Recognizer(loadedModel, sampleRate.toFloat())
            if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
                listener.onError("Microphone could not be initialized")
                stop()
                return
            }
            running = true
            recorder?.startRecording()
            if (recorder?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                listener.onError("Microphone could not start")
                stop()
                return
            }

            worker = thread(name = "Jarvis-Vosk") {
                val buffer = ByteArray(bufferSize)
                while (running) {
                    val count = try {
                        recorder?.read(buffer, 0, buffer.size) ?: -1
                    } catch (_: Exception) {
                        -1
                    }
                    if (count <= 0) continue

                    val accepted = recognizer?.acceptWaveForm(buffer, count) == true
                    if (accepted) {
                        val text = extractText(recognizer?.result)
                        if (text.isNotBlank()) {
                            if (!commandMode && containsWakeWord(text)) {
                                main.post {
                                    if (running) {
                                        stop()
                                        currentListener?.onWake()
                                    }
                                }
                                break
                            } else if (commandMode) {
                                main.post {
                                    if (running) {
                                        stop()
                                        currentListener?.onCommand(text)
                                    }
                                }
                                break
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            stop()
            main.post { listener.onError("Microphone: ${e.message ?: "start failed"}") }
        }
    }

    private fun containsWakeWord(text: String): Boolean {
        val normalized = text.lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return normalized.split(" ").contains("jarvis")
    }

    private fun extractText(json: String?): String {
        if (json.isNullOrBlank()) return ""
        return try {
            JSONObject(json).optString("text").trim()
        } catch (_: Exception) {
            ""
        }
    }

    fun stop() {
        running = false
        try { recorder?.stop() } catch (_: Throwable) {}
        try { recorder?.release() } catch (_: Throwable) {}
        recorder = null
        try { recognizer?.close() } catch (_: Throwable) {}
        recognizer = null
        worker = null
    }

    fun release() {
        released = true
        stop()
        model?.close()
        model = null
        currentListener = null
    }
}