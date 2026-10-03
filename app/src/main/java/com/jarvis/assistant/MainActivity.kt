package com.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class MainActivity : AppCompatActivity() {

    private val apiKey = "YOUR_GROQ_API_KEY"

    private lateinit var memory: MemoryStore
    private lateinit var emotion: EmotionEngine
    private lateinit var commands: CommandProcessor
    private lateinit var api: GroqApiClient
    private lateinit var voice: VoiceManager
    private lateinit var agent: AgentRunner
    private lateinit var security: SecuritySettings
    private var pendingSecureScreenTask: String? = null
    private lateinit var micButton: ImageButton
    private lateinit var stateLabel: TextView
    private lateinit var stateHint: TextView
    private val hudHandler = Handler(Looper.getMainLooper())
    private var waveformRunning = false
    @Volatile private var taskRunning = false
    private var pcClient: PcControlClient? = null

    private lateinit var adapter: ChatAdapter
    private val messages = mutableListOf<ChatMessage>()

    private val screenTaskKeywords = listOf(
        "open ", "kholo", "khol do", "type ", "search ", "search for ", "find ",
        "message ", "send ", "search box", "follow", "start", "shuru karo",
        "tap ", "click ", "scroll", "post ", "like ", "comment", "developer option",
        "settings", "waha", "wahan"
    )

    private val rememberPatterns = listOf(
        Regex("""^remember (?:that )?(.+)$""", RegexOption.IGNORE_CASE),
        Regex("""^yaad rakho (?:ki )?(.+)$""", RegexOption.IGNORE_CASE),
        Regex("""^(.+) yaad rakho$""", RegexOption.IGNORE_CASE),
        Regex("""^(.+) yaad rakhna$""", RegexOption.IGNORE_CASE)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        setContentView(R.layout.activity_main)

        memory = MemoryStore(this)
        emotion = EmotionEngine(memory)
        commands = CommandProcessor(this)
        security = SecuritySettings(this)
        api = GroqApiClient(apiKey)
        agent = AgentRunner(api, commands) { message ->
            runOnUiThread {
                stateHint.text = message
                findViewById<TextView>(R.id.taskProgressText).text = message
                findViewById<TextView>(R.id.listeningIndicator).text = "▮▮▮  $message"
                val match = Regex("""(?:STEP |)(\\d+)/(\\d+)""").find(message)
                if (match != null) {
                    val current = match.groupValues[1].toIntOrNull() ?: 0
                    val total = match.groupValues[2].toIntOrNull() ?: 1
                    findViewById<android.widget.ProgressBar>(R.id.taskProgressBar).progress = (current * 100 / total).coerceIn(0, 100)
                }
            }
        }

        stateLabel = findViewById(R.id.stateLabel)
        stateHint = findViewById(R.id.stateHint)

        voice = VoiceManager(
            this,
            onSpeechResult = { heard ->
                setOrbState("calm")
                handleUserInput(heard)
            },
            onListenStart = { setOrbState("listening") },
            onListenEnd = { setOrbState("calm") }
        )
        voice.init()

        val recycler = findViewById<RecyclerView>(R.id.chatRecycler)
        adapter = ChatAdapter(messages)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        val input = findViewById<EditText>(R.id.inputField)
        val sendButton = findViewById<ImageButton>(R.id.sendButton)
        micButton = findViewById(R.id.micButton)
        val settingsButton = findViewById<ImageButton>(R.id.settingsButton)

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<TextView>(R.id.voiceQuickButton).setOnClickListener {
            requestPermissionsIfNeeded()
            voice.startListening()
        }

        findViewById<TextView>(R.id.typeQuickButton).setOnClickListener {
            input.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }

        findViewById<TextView>(R.id.cameraQuickButton).setOnClickListener {
            try {
                startActivity(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE))
            } catch (_: Exception) {
                Toast.makeText(this, "Camera isn't available.", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<TextView>(R.id.appsQuickButton).setOnClickListener {
            startActivity(Intent(this, HudScreenActivity::class.java).putExtra("screen", "apps"))
        }

        findViewById<TextView>(R.id.toolsQuickButton).setOnClickListener {
            startActivity(Intent(this, HudScreenActivity::class.java).putExtra("screen", "tools"))
        }

        findViewById<TextView>(R.id.settingsQuickButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<TextView>(R.id.historyQuickButton).setOnClickListener {
            startActivity(Intent(this, HudScreenActivity::class.java).putExtra("screen", "history"))
        }

        findViewById<TextView>(R.id.memoryQuickButton).setOnClickListener {
            startActivity(Intent(this, HudScreenActivity::class.java).putExtra("screen", "memory"))
        }
        refreshMoodLabel()

        sendButton.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                input.text.clear()
                handleUserInput(text)
            }
        }

        micButton.setOnClickListener {
            requestPermissionsIfNeeded()
            voice.startListening()
        }

        requestPermissionsIfNeeded()
        maybePromptAccessibilityService()
        promptOverlayPermissionIfNeeded()
        startCompanionIfEnabled()
        intent.getStringExtra("routine")?.let { executeRoutine(it) }
    }

    private fun startCompanionIfEnabled() {
        val voiceSettings = VoiceSettings(this)
        if (!voiceSettings.isCompanionEnabled()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, WakeWordService::class.java).setAction(WakeWordService.ACTION_START)
            )
        } catch (_: Exception) {
            // Companion Mode is opt-in; a service start failure must not affect the main app.
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("routine")?.let { executeRoutine(it) }
    }

    private fun executeRoutine(name: String) {
        val commandsToRun = RoutineStore(this).get(name)
        if (commandsToRun.isEmpty()) {
            stateHint.text = "ROUTINE NOT FOUND  •  " + name
            return
        }

        setOrbState("thinking")
        lifecycleScope.launch {
            for ((index, command) in commandsToRun.withIndex()) {
                stateHint.text = "ROUTINE  •  " + (index + 1) + "/" + commandsToRun.size + "\n" + command
                findViewById<TextView>(R.id.taskProgressText).text =
                    "ROUTINE  •  " + (index + 1) + "/" + commandsToRun.size + "  •  " + command
                findViewById<android.widget.ProgressBar>(R.id.taskProgressBar).progress =
                    (index * 100 / commandsToRun.size).coerceIn(0, 100)

                taskRunning = false
                handleUserInput(command)

                while (taskRunning) {
                    delay(150)
                }
                delay(700)
            }

            findViewById<android.widget.ProgressBar>(R.id.taskProgressBar).progress = 100
            findViewById<TextView>(R.id.taskProgressText).text =
                "ROUTINE COMPLETE  •  " + commandsToRun.size + "/" + commandsToRun.size + " STEPS"
            stateHint.text = "ROUTINE COMPLETE\n" + name
            setOrbState("calm")
            val reply = "Routine " + name + " completed."
            appendMessage(reply, false)
            voice.speak(reply)
        }
    }

    private fun setOrbState(state: String) {
        val core = findViewById<TextView>(R.id.waveform)
        val face = findViewById<TextView>(R.id.coreFace)
        val telemetry = findViewById<TextView>(R.id.coreTelemetry)
        when (state) {
            "listening" -> {
                stateLabel.text = "●  LISTENING"
                face.text = "◉‿◉"
                telemetry.text = "MIC LINK  •  VOICE INPUT  •  ACTIVE"
                stateHint.text = "LIVE AUDIO  •  Speak your command..."
                micButton.setBackgroundResource(R.drawable.mic_bg_active)
                startReactiveWave(core, true)
            }
            "thinking" -> {
                stateLabel.text = "●  PROCESSING"
                face.text = "◉_◉"
                telemetry.text = "NEURAL LINK  •  LIVE TASK EXECUTION"
                stateHint.text = "PROCESSING  •  JARVIS is working..."
                startReactiveWave(core, false)
            }
            else -> {
                stateLabel.text = "●  SYSTEM ONLINE"
                face.text = "◉‿◉"
                core.text = "▁▃▅▇▅▃▁  ▂▅▇█▇▅▂  ▁▃▆█▆▃▁"
                telemetry.text = "VOICE LINK  •  AI READY  •  100%"
                stateHint.text = "JARVIS READY\nAwaiting your command..."
                micButton.setBackgroundResource(R.drawable.mic_bg)
                stopReactiveWave()
            }
        }
    }

    private fun startReactiveWave(core: TextView, listening: Boolean) {
        stopReactiveWave()
        waveformRunning = true
        val frames = if (listening) arrayOf(
            "▁▃▅▇█▇▅▃▁  ▂▅▇█▇▅▂  ▁▃▆█▆▃▁",
            "▂▅█▇▃▆█▅▂  ▃▇█▅▇▃  ▂▆█▇▅▂",
            "▁▆█▅▇█▃▅▁  ▅█▇▃▇█▅  ▁▅▇█▆▃▁",
            "▃▇█▆▂▅█▇▃  ▇█▅▃▅█▇  ▃▆█▇▅▂▁"
        ) else arrayOf(
            "▂▅▇█▇▅▂  ▃▆█▇▆▃  ▂▅▇█▇▅▂",
            "▃▇█▇▃  ▅█▇█▅  ▃▇█▇▃  ▅█",
            "▁▅█▆▃  ▂▇█▇▂  ▃▆█▅  ▂▇█▆▁",
            "▂▆█▇▅▂  ▅█▇▅█▅  ▂▆█▇▃"
        )
        var index = 0
        val tick = object : Runnable {
            override fun run() {
                if (!waveformRunning) return
                core.text = frames[index++ % frames.size]
                hudHandler.postDelayed(this, 120L)
            }
        }
        hudHandler.post(tick)
    }

    private fun stopReactiveWave() {
        waveformRunning = false
        hudHandler.removeCallbacksAndMessages(null)
    }

    private fun looksLikePcTask(text: String): Boolean {
        val t = text.lowercase()
        return t.contains(" on pc") || t.contains(" on my computer") ||
            t.contains("computer par") || t.contains("pc par")
    }

    private fun runPcTask(text: String): Boolean {
        if (!looksLikePcTask(text)) return false
        val prefs = getSharedPreferences("jarvis_pc", MODE_PRIVATE)
        val host = prefs.getString("host", "").orEmpty()
        val token = prefs.getString("token", "").orEmpty()
        if (host.isBlank() || token.isBlank()) {
            val reply = "PC Link is not configured yet. Open PC Link from the JARVIS menu and pair your computer first."
            appendMessage(reply, false)
            voice.speak(reply)
            startActivity(Intent(this, HudScreenActivity::class.java).putExtra("screen", "pc"))
            return true
        }

        val t = text.lowercase()
        val action = when {
            t.contains("browser") || t.contains("chrome") -> "browser"
            t.contains("calculator") || t.contains("calc") -> "calculator"
            t.contains("terminal") || t.contains("cmd") -> "terminal"
            else -> null
        }
        if (action == null) {
            val reply = "PC Link is ready, but I only have safe browser, calculator and terminal launch commands configured right now."
            appendMessage(reply, false)
            voice.speak(reply)
            return true
        }

        setOrbState("thinking")
        pcClient?.disconnect()
        pcClient = PcControlClient { msg -> stateHint.text = msg }
        pcClient?.connect(host, token) {
            pcClient?.send("open_app", action)
            runOnUiThread {
                val reply = "Opening $action on your PC."
                appendMessage(reply, false)
                memory.addTurn("assistant", reply)
                voice.speak(reply)
                setOrbState("calm")
            }
        }
        return true
    }

    private fun looksLikeScreenTask(text: String): Boolean {
        val t = text.lowercase()
        return screenTaskKeywords.any { t.contains(it) }
    }

    private fun looksLikeWhatsAppMessageTask(text: String): Boolean {
        val t = text.lowercase()
        val isWhatsApp = t.contains("whatsapp")
        val isMessageAction = listOf("message", "send", "bhejo", "msg").any { t.contains(it) }
        return isWhatsApp && isMessageAction
    }

    private fun looksLikeSensitiveScreenTask(text: String): Boolean {
        val t = text.lowercase()
        return listOf(
            "message ", "send ", "bhejo", "whatsapp and message",
            "call ", "phone ", "dial "
        ).any { t.contains(it) }
    }

    private fun looksLikeMultiStepTask(text: String): Boolean {
        val t = text.trim().lowercase()
        val hasConnector = Regex("""\b(and|then|phir|aur)\b|[,;]""").containsMatchIn(t)
        val hasPhoneAction = listOf(
            "open ", "kholo", "khol do", "message ", "send ", "search ",
            "find ", "developer option", "tap ", "click ", "scroll "
        ).any { t.contains(it) }
        return hasConnector && hasPhoneAction
    }

    private fun handleDeterministicSettings(text: String): Boolean {
        val t = text.lowercase()

        val action = when {
            t.contains("accessibility") && (t.contains("settings") || t.contains("setting") || t.contains("open") || t.contains("khol")) ->
                Settings.ACTION_ACCESSIBILITY_SETTINGS
            t.contains("developer option") || t.contains("developer options") ->
                Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
            (t.contains("open settings") || t.contains("open setting") || t.trim() == "settings" || t.trim() == "setting") ->
                Settings.ACTION_SETTINGS
            else -> null
        }

        if (action == null) return false

        return try {
            startActivity(Intent(action))
            val reply = when (action) {
                Settings.ACTION_ACCESSIBILITY_SETTINGS -> "Opening Accessibility settings."
                Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS -> "Opening Developer options."
                else -> "Opening Android Settings."
            }
            appendMessage(reply, isUser = false)
            memory.addTurn("assistant", reply)
            voice.speak(reply)
            true
        } catch (_: Exception) {
            val reply = "I couldn't open that Android settings page on this device."
            appendMessage(reply, isUser = false)
            memory.addTurn("assistant", reply)
            voice.speak(reply)
            true
        }
    }

    private fun runWhatsAppTask(text: String) {
        if (AssistantAccessibilityService.instance == null) {
            val reply = "Please enable Jarvis in Settings > Accessibility first."
            appendMessage(reply, isUser = false)
            memory.addTurn("assistant", reply)
            voice.speak(reply)
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        val contacts = ContactsHelper(this)
        val recipient = contacts.findBestDisplayNameInText(text)
        if (recipient.isNullOrBlank()) {
            val reply = "I couldn't identify the WhatsApp contact name. Please use the exact saved contact name."
            appendMessage(reply, isUser = false)
            memory.addTurn("assistant", reply)
            voice.speak(reply)
            return
        }

        val lower = text.lowercase()
        val start = lower.indexOf(recipient.lowercase())
        if (start < 0) {
            val reply = "I couldn't identify the WhatsApp contact name."
            appendMessage(reply, isUser = false)
            memory.addTurn("assistant", reply)
            voice.speak(reply)
            return
        }

        var message = text.substring(start + recipient.length).trim()
        message = message
            .replace(Regex("^(?:and\\s+)?(?:saying|message|with message)\\s+", RegexOption.IGNORE_CASE), "")
            .trim()

        if (message.isBlank()) {
            val reply = "Tell me what message to send to $recipient."
            appendMessage(reply, isUser = false)
            memory.addTurn("assistant", reply)
            voice.speak(reply)
            return
        }

        setOrbState("thinking")
        appendMessage("Opening WhatsApp Search for $recipient...", isUser = false)

        taskRunning = true
        lifecycleScope.launch {
            val summary = try {
                agent.runWhatsAppMessage(recipient, message)
            } catch (e: Exception) {
                "I ran into an error: " + e.message
            }
            taskRunning = false
            setOrbState("calm")
            appendMessage(summary, isUser = false)
            memory.addTurn("assistant", summary)
            voice.speak(summary)
        }
    }

    private fun runScreenTask(text: String) {
        if (AssistantAccessibilityService.instance == null) {
            val reply = "Please enable Jarvis in Settings > Accessibility first. I'll need it to control WhatsApp, Instagram, Settings, and other apps."
            appendMessage(reply, isUser = false)
            memory.addTurn("assistant", reply)
            voice.speak(reply)
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        setOrbState("thinking")
        appendMessage("Working on it...", isUser = false)
        taskRunning = true
        lifecycleScope.launch {
            val summary = try {
                agent.run(text)
            } catch (e: Exception) {
                "I ran into an error: " + e.message
            }
            taskRunning = false
            setOrbState("calm")
            appendMessage(summary, isUser = false)
            memory.addTurn("assistant", summary)
            voice.speak(summary)
        }
    }

    private fun tryRemember(text: String): String? {
        for (pattern in rememberPatterns) {
            pattern.find(text.trim())?.let { m -> return m.groupValues[1].trim() }
        }
        return null
    }

    private fun handleUserInput(text: String) {
        appendMessage(text, isUser = true)

        pendingSecureScreenTask?.let { pendingTask ->
            pendingSecureScreenTask = null
            if (security.checkPassphrase(text)) {
                if (looksLikeWhatsAppMessageTask(pendingTask)) {
                    runWhatsAppTask(pendingTask)
                } else {
                    runScreenTask(pendingTask)
                }
            } else {
                val reply = "That passphrase didn't match, so I cancelled the action."
                appendMessage(reply, isUser = false)
                memory.addTurn("assistant", reply)
                voice.speak(reply)
            }
            return
        }

        tryRemember(text)?.let { fact ->
            memory.setFact(System.currentTimeMillis().toString(), fact)
            val reply = "Got it, I'll remember that."
            appendMessage(reply, isUser = false)
            voice.speak(reply)
            return
        }

        memory.addTurn("user", text)
        emotion.registerUserMessage(text)
        refreshMoodLabel()

        if (runPcTask(text)) return

        // Handle Android settings targets deterministically instead of asking the visual agent
        // to navigate system Settings by coordinates.
        if (handleDeterministicSettings(text)) {
            return
        }

        // Sensitive visual tasks must also honor the optional Jarvis passphrase.
        if ((looksLikeMultiStepTask(text) || looksLikeWhatsAppMessageTask(text)) &&
            security.isEnabled() && looksLikeSensitiveScreenTask(text)) {
            pendingSecureScreenTask = text
            val reply = "Before I do that, please say the Jarvis passphrase."
            appendMessage(reply, isUser = false)
            memory.addTurn("assistant", reply)
            voice.speak(reply)
            return
        }

        // WhatsApp messages use a deterministic Search -> contact -> chat -> type -> Send flow.
        // This avoids asking the vision model to guess coordinates for the most important steps.
        if (looksLikeWhatsAppMessageTask(text)) {
            runWhatsAppTask(text)
            return
        }

        // Other multi-step phone tasks still use the visual agent.
        if (looksLikeMultiStepTask(text)) {
            runScreenTask(text)
            return
        }

        when (val result = commands.process(text)) {
            is CommandProcessor.Result.Handled -> {
                appendMessage(result.spokenReply, isUser = false)
                memory.addTurn("assistant", result.spokenReply)
                voice.speak(result.spokenReply)
                return
            }
            CommandProcessor.Result.NotACommand -> {}
        }

        if (looksLikeScreenTask(text)) {
            runScreenTask(text)
            return
        }

        setOrbState("thinking")
        lifecycleScope.launch {
            val systemPrompt = buildSystemPrompt()
            val reply = try {
                api.sendMessage(systemPrompt, memory.recentTurns(), text)
            } catch (e: Exception) {
                "I hit an error reaching the model: ${e.message}"
            }
            setOrbState("calm")
            appendMessage(reply, isUser = false)
            memory.addTurn("assistant", reply)
            voice.speak(reply)
        }
    }

    private fun buildSystemPrompt(): String {
        val facts = memory.allFacts().entries.joinToString("\n") { "- ${it.key}: ${it.value}" }
        return """
            You are Jarvis, a personal AI assistant living on the user's phone.
            Your current mood/tone should be: ${emotion.moodDescriptor()}.
            Keep replies short and natural - 1 to 3 sentences unless the user clearly
            asks for more detail. Replies are read aloud by text-to-speech, so avoid
            long lists or markdown.
            When replying in Hindi, always write in Devanagari script (हिंदी), never in
            Latin/Hinglish letters - the phone's voice engine can only pronounce Hindi
            correctly when it is in Devanagari. Reply in English when the user writes in
            English or Hinglish and a Hindi reply is not clearly wanted.
            You cannot control the phone yourself through plain conversation - only exact
            recognized commands or the on-screen agent do that. Do not claim you performed
            a device action unless you are certain a command actually triggered it.
            Durable facts the user has explicitly asked you to remember:
            $facts
        """.trimIndent()
    }

    private fun appendMessage(text: String, isUser: Boolean) {
        adapter.addMessage(ChatMessage(text, isUser))
        findViewById<RecyclerView>(R.id.chatRecycler).scrollToPosition(messages.size - 1)
    }

    private fun refreshMoodLabel() {
        findViewById<TextView>(R.id.moodLabel).text = emotion.moodDescriptor()
    }

    private fun requestPermissionsIfNeeded() {
        val permissionList = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= 33) {
            permissionList.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = permissionList.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1001)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun promptOverlayPermissionIfNeeded() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Enable \"Display over other apps\" so JARVIS can present its assistant HUD.", Toast.LENGTH_LONG).show()
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun maybePromptAccessibilityService() {
        if (AssistantAccessibilityService.instance == null) {
            Toast.makeText(
                this,
                "For full on-screen control, enable Jarvis under Settings > Accessibility.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    override fun onDestroy() {
        super.onDestroy()
        voice.release()
    }
}
