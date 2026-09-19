package com.jarvis.assistant

import android.Manifest
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        memory = MemoryStore(this)
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

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        refreshMoodLabel()

        memory.recentTurns().forEach { (role, text) ->
            messages.add(ChatMessage(text, isUser = role == "user"))
        }
        adapter.notifyDataSetChanged()

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
    }

    override fun onResume() {
        super.onResume()
        voice.applySettings()
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

    private fun handleUserInput(text: String) {
        appendMessage(text, isUser = true)
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
            Speak naturally and concisely - replies may be read aloud by text-to-speech,
            so avoid long lists, markdown, or anything that reads awkwardly out loud.
            You cannot control the phone yourself through plain conversation - only exact
            recognized commands or the on-screen agent do that. Do not claim you performed
            a device action unless you are certain a command actually triggered it.
            What you know about the user so far:
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
        val needed = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS
        ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1001)
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
}
