package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import java.util.Locale

/**
 * Lightweight JARVIS wake/overlay layer.
 *
 * Say "Jarvis" and a compact HUD appears over the current app instead of opening
 * the full JARVIS activity. The overlay then listens for the next command.
 */
class WakeWordService : Service() {

    companion object {
        const val ACTION_START = "com.jarvis.assistant.START_WAKE"
        private const val CHANNEL_ID = "jarvis_wake"
        private const val NOTIFICATION_ID = 77
        private const val WAKE_WORD = "jarvis"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var recognizer: SpeechRecognizer? = null
    private var voskEngine: VoskWakeEngine? = null
    private var voice: VoiceManager? = null
    private var overlay: View? = null
    private var windowManager: WindowManager? = null
    private var waveform: TextView? = null
    private var face: TextView? = null
    private var stateText: TextView? = null
    private var replyText: TextView? = null
    private var listeningForCommand = false
    private var overlayVisible = false
    private var destroyed = false
    private var wakeListening = false
    private var wakeStartInProgress = false
    private var wakeDisabled = false

    private lateinit var memory: MemoryStore
    private lateinit var emotion: EmotionEngine
    private lateinit var commands: CommandProcessor
    private lateinit var api: GroqApiClient
    private lateinit var agent: AgentRunner

    override fun onCreate() {
        super.onCreate()
        try {
            createChannel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { startForeground(NOTIFICATION_ID, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) } else { startForeground(NOTIFICATION_ID, buildNotification()) }
            memory = MemoryStore(this)
            emotion = EmotionEngine(memory)
            commands = CommandProcessor(this)
            api = GroqApiClient("YOUR_GROQ_API_KEY")
            agent = AgentRunner(api, commands) { msg ->
                handler.post { setOverlayState("PROCESSING", msg) }
            }
            voice = VoiceManager(this, {}, {}, {}, {})
            voice?.init()
            voskEngine = VoskWakeEngine(this)
            startWakeListening()
        } catch (t: Throwable) {
            // A voice/model failure must never crash the whole JARVIS app.
            wakeDisabled = true
            handler.post { stopSelf() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!destroyed && VoiceSettings(this).isCompanionEnabled() && !wakeDisabled && !wakeStartInProgress && !listeningForCommand) {
            startWakeListening()
        }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Wake Listener",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Keeps JARVIS ready for the Jarvis wake phrase."
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("JARVIS is ready")
            .setContentText("Say “Jarvis” to talk to your friendly JARVIS.")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startWakeListening() {
        if (destroyed || wakeDisabled || listeningForCommand || wakeStartInProgress) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return

        val engine = voskEngine ?: return
        wakeStartInProgress = true
        try {
            engine.startWake(object : VoskWakeEngine.Listener {
                override fun onWake() {
                    wakeStartInProgress = false
                    handleWakeResult("jarvis")
                }
                override fun onCommand(text: String) {
                    wakeStartInProgress = false
                }
                override fun onError(message: String) {
                    wakeStartInProgress = false
                    wakeDisabled = true
                    handler.post { setOverlayState("VOICE OFFLINE", message) }
                }
            })
        } catch (t: Throwable) {
            wakeStartInProgress = false
            wakeDisabled = true
            handler.post { setOverlayState("VOICE OFFLINE", t.message ?: "Voice engine unavailable") }
        }
    }

    private fun restartWake(delayMs: Long) {
        if (destroyed) return
        handler.postDelayed({ startWakeListening() }, delayMs)
    }

    private fun handleWakeResult(heard: String) {
        val lower = heard.lowercase(Locale.getDefault())
        val index = lower.indexOf(WAKE_WORD)
        if (index < 0) {
            restartWake(250)
            return
        }

        val remainder = heard.substring(index + WAKE_WORD.length).trim()
        showOverlay()
        if (remainder.isBlank()) {
            setOverlayState("LISTENING", "HAAN BHAI  •  BOL, MAIN SUN RAHA HOON")
            voice?.speak("Haan bhai, bol kya scene hai?") {
                handler.post { startCommandListening() }
            }
        } else {
            setOverlayState("PROCESSING", remainder)
            processCommand(remainder)
        }
    }

    private fun startCommandListening() {
        wakeListening = false
        listeningForCommand = true
        showOverlay()
        setOverlayState("LISTENING", "BOL BHAI  •  MAIN SUN RAHA HOON")

        voskEngine?.startCommand(object : VoskWakeEngine.Listener {
            override fun onWake() {}
            override fun onCommand(text: String) {
                listeningForCommand = false
                if (text.isBlank()) {
                    setOverlayState("LISTENING", "I didn't catch that.")
                    handler.postDelayed({ finishOverlay() }, 1400)
                } else {
                    processCommand(text)
                }
            }
            override fun onError(message: String) {
                listeningForCommand = false
                setOverlayState("ERROR", message)
                handler.postDelayed({ finishOverlay() }, 1800)
            }
        })
    }

    private fun processCommand(text: String) {
        emotion.registerUserMessage(text)
        showOverlay()
        setOverlayState("PROCESSING", text)

        scope.launch {
            val result = try {
                when (val command = commands.process(text)) {
                    is CommandProcessor.Result.Handled -> command.spokenReply
                    CommandProcessor.Result.NotACommand -> agent.run(text)
                }
            } catch (e: Exception) {
                "I hit an error: " + (e.message ?: "unknown error")
            }

            memory.addTurn("user", text)
            memory.addTurn("assistant", result)
            setOverlayState(emotion.moodLabel(), result)
            voice?.speak(result) {
                handler.postDelayed({ finishOverlay() }, 1800)
            }
        }
    }

    private fun showOverlay() {
        if (overlayVisible || !Settings.canDrawOverlays(this)) return

        windowManager = if (Build.VERSION.SDK_INT >= 30) {
            val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
            val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            createDisplayContext(display).createWindowContext(
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                null
            ).getSystemService(WindowManager::class.java)
        } else {
            getSystemService(WINDOW_SERVICE) as WindowManager
        }

        // Gemini-style assistant sheet: no draggable/floating bubble. The current
        // app remains visible behind a subtle dim layer.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 18)
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(30f, 30f, 30f, 30f, 0f, 0f, 0f, 0f)
                setColors(intArrayOf(
                    Color.argb(238, 24, 45, 66),
                    Color.argb(224, 7, 20, 31),
                    Color.argb(238, 4, 11, 19)
                ))
                orientation = GradientDrawable.Orientation.TL_BR
                setStroke(1, Color.argb(150, 92, 220, 255))
            }
            alpha = 0f
            scaleY = 0.94f
        }

        val handle = View(this).apply {
            setBackgroundColor(Color.argb(150, 94, 220, 245))
        }
        root.addView(handle, LinearLayout.LayoutParams(54, 4).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = 10
        })

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        face = TextView(this).apply {
            text = "◉‿◉"
            textSize = 22f
            setTextColor(Color.rgb(220, 250, 255))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(90, 20, 70, 90))
                setStroke(1, Color.rgb(50, 190, 220))
            }
        }

        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 0, 8, 0)
        }

        val title = TextView(this).apply {
            text = "J A R V I S"
            textSize = 15f
            setTextColor(Color.rgb(220, 250, 255))
        }

        stateText = TextView(this).apply {
            text = "LISTENING  •  READY"
            textSize = 8f
            setTextColor(Color.rgb(69, 240, 178))
        }

        titleBlock.addView(title)
        titleBlock.addView(stateText)
        header.addView(face, LinearLayout.LayoutParams(54, 54))
        header.addView(titleBlock, LinearLayout.LayoutParams(0, -2, 1f))

        val close = TextView(this).apply {
            text = "×"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(180, 225, 235))
            setOnClickListener { finishOverlay() }
        }
        header.addView(close, LinearLayout.LayoutParams(44, 54))
        root.addView(header)

        replyText = TextView(this).apply {
            text = "Say your command..."
            textSize = 15f
            setTextColor(Color.WHITE)
            maxLines = 3
            setPadding(4, 14, 4, 10)
        }
        root.addView(replyText, LinearLayout.LayoutParams(-1, 0, 1f))

        waveform = TextView(this).apply {
            text = "▁▃▅▇█▇▅▃▁"
            textSize = 13f
            setTextColor(Color.rgb(50, 223, 255))
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        root.addView(waveform, LinearLayout.LayoutParams(-1, 34))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 5, 8, 5)
            background = GradientDrawable().apply {
                cornerRadius = 24f
                setColors(intArrayOf(
                    Color.argb(150, 52, 82, 104),
                    Color.argb(105, 17, 34, 48),
                    Color.argb(145, 8, 19, 29)
                ))
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                setStroke(1, Color.argb(135, 92, 220, 255))
            }
        }

        val hint = TextView(this).apply {
            text = "Ask JARVIS anything…"
            textSize = 11f
            setTextColor(Color.rgb(95, 145, 160))
        }
        bottom.addView(hint, LinearLayout.LayoutParams(0, 48, 1f))

        val mic = TextView(this).apply {
            text = "●"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(65, 225, 255))
            setOnClickListener { startCommandListening() }
        }
        bottom.addView(mic, LinearLayout.LayoutParams(48, 48))

        root.addView(bottom)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            330,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            dimAmount = 0.28f
            flags = flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
        }

        try {
            windowManager?.addView(root, params)
            overlay = root
            overlayVisible = true
            startWaveAnimation()
            root.animate().alpha(1f).scaleY(1f).setDuration(240).start()
        } catch (_: Exception) {
            overlayVisible = false
        }
    }

    private fun startWaveAnimation() {
        val frames = arrayOf(
            "▁▃▅▇█▇▅▃▁",
            "▂▆█▇▅█▇▂",
            "▁▇█▅▇█▆▁",
            "▃█▇▂▆█▇▃"
        )
        var i = 0
        val tick = object : Runnable {
            override fun run() {
                if (!overlayVisible) return
                waveform?.text = frames[i++ % frames.size]
                handler.postDelayed(this, 110)
            }
        }
        handler.post(tick)
    }

    private fun setOverlayState(state: String, text: String) {
        handler.post {
            stateText?.text = state.uppercase(Locale.getDefault())
            replyText?.text = text
            face?.text = when (state.lowercase(Locale.getDefault())) {
                "happy", "excited" -> "◉‿◉"
                "annoyed", "angry" -> "ಠ_ಠ"
                "sad" -> "◔︵◔"
                "processing" -> "◉_◉"
                "listening" -> "◉‿◉"
                else -> "•‿•"
            }
        }
    }

    private fun finishOverlay() {
        if (!overlayVisible) {
            restartWake(200)
            return
        }
        listeningForCommand = false
        wakeListening = false
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        voskEngine?.stop()
        overlay?.animate()?.alpha(0f)?.scaleX(0.94f)?.scaleY(0.94f)?.setDuration(180)?.withEndAction {
            try { windowManager?.removeView(overlay) } catch (_: Exception) {}
            overlay = null
            overlayVisible = false
            waveform = null
            face = null
            stateText = null
            replyText = null
            restartWake(250)
        }?.start()
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        try { recognizer?.destroy() } catch (_: Throwable) {}
        recognizer = null
        try { voskEngine?.release() } catch (_: Throwable) {}
        voskEngine = null
        try { voice?.release() } catch (_: Throwable) {}
        voice = null
        try { overlay?.let { windowManager?.removeView(it) } } catch (_: Throwable) {}
        overlay = null
        overlayVisible = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}