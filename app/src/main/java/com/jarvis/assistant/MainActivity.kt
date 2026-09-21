package com.jarvis.assistant

import android.Manifest
import android.animation.ObjectAnimator
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val apiKey = "YOUR_GROQ_API_KEY"

    private lateinit var memory: MemoryStore
    private lateinit var emotion: EmotionEngine
    private lateinit var commands: CommandProcessor
    private lateinit var api: GroqApiClient
    private lateinit var voice: VoiceManager
    private lateinit var agent: AgentRunner
    private lateinit var micButton: ImageButton
    private lateinit var orb: View
    private lateinit var stateLabel: TextView
    private var pulseAnimator: ObjectAnimator? = null

    private lateinit var adapter: ChatAdapter
    private val messages = mutableListOf<ChatMessage>()

    private val screenTaskKeywords = listOf(
        "open ", "kholo", "khol do", "type ", "search box", "follow", "start", "shuru karo",
        "tap ", "click ", "scroll", "post ", "like ", "comment", "waha", "wahan"
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
        memory.clearTurnsOnly()
        emotion = EmotionEngine(memory)
        commands = CommandProcessor(this)
        api = GroqApiClient(apiKey)
        agent = AgentRunner(api, commands)

        orb = findViewById(R.id.orb)
        stateLabel = findViewById(R.id.stateLabel)
        startPulse()

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
        val clearButton = findViewById<ImageButton>(R.id.clearButton)

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        clearButton.setOnClickListener {
            messages.clear()
            adapter.notifyDataSetChanged()
            memory.clearTurnsOnly()
            Toast.makeText(this, "Chat cleared", Toast.LENGTH_SHORT).show()
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
        startWakeWordService()
        handleWakeWordIntent(intent)
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
        handleWakeWordIntent(intent)
    }

    private fun handleWakeWordIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("wake_word_triggered", false) == true) {
            voice.startListening()
        }
    }

    private fun startWakeWordService() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val serviceIntent = Intent(this, WakeWordService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    override fun onResume() {
        super.onResume()
        voice.applySettings()
    }

    override fun onPause() {
        super.onPause()
        voice.stopListening()
    }

    private fun startPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = ObjectAnimator.ofFloat(orb, "alpha", 0.7f, 1f, 0.7f).apply {
            duration = 2200
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun setOrbState(state: String) {
        when (state) {
            "listening" -> {
                orb.setBackgroundResource(R.drawable.orb_listening)
                stateLabel.text = "Listening..."
                micButton.setBackgroundResource(R.drawable.mic_bg_active)
            }
            "thinking" -> {
                orb.setBackgroundResource(R.drawable.orb_thinking)
                stateLabel.text = "Thinking..."
            }
            else -> {
                orb.setBackgroundResource(R.drawable.orb_calm)
                stateLabel.text = "Calm / Ready"
                micButton.setBackgroundResource(R.drawable.mic_bg)
            }
        }
    }

    private fun looksLikeScreenTask(text: String): Boolean {
        val t = text.lowercase()
        return screenTaskKeywords.any { t.contains(it) }
    }

    private fun tryRemember(text: String): String? {
        for (pattern in rememberPatterns) {
            pattern.find(text.trim())?.let { m -> return m.groupValues[1].trim() }
        }
        return null
    }

    private fun handleUserInput(text: String) {
        appendMessage(text, isUser = true)

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
            setOrbState("thinking")
            appendMessage("Working on it...", isUser = false)
            lifecycleScope.launch {
                val summary = try {
                    agent.run(text)
                } catch (e: Exception) {
                    "I ran into an error: ${e.message}"
                }
                setOrbState("calm")
                appendMessage(summary, isUser = false)
                memory.addTurn("assistant", summary)
                voice.speak(summary)
            }
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
        startWakeWordService()
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
