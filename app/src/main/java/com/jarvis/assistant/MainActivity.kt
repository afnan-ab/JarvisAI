package com.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
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

/**
 * -----------------------------------------------------------------------
 * SETUP REQUIRED before this compiles into something useful:
 *   1. Put your Anthropic API key below (or better: load it from
 *      local.properties / a secure settings screen - never commit a real
 *      key to source control).
 *   2. After installing the app, go to
 *      Settings > Accessibility > Installed apps > Jarvis > enable it,
 *      so device-control actions that need on-screen interaction work.
 *   3. Grant the runtime permissions the app will prompt for (mic, call,
 *      SMS) - Android requires these to be accepted by the user, an app
 *      can never silently grant them to itself.
 * -----------------------------------------------------------------------
 */
class MainActivity : AppCompatActivity() {

    // TODO: replace with your real key, loaded securely - do not hardcode in shipped builds.
    private val apiKey = "YOUR_ANTHROPIC_API_KEY"

    private lateinit var memory: MemoryStore
    private lateinit var emotion: EmotionEngine
    private lateinit var commands: CommandProcessor
    private lateinit var api: ClaudeApiClient
    private lateinit var voice: VoiceManager

    private lateinit var adapter: ChatAdapter
    private val messages = mutableListOf<ChatMessage>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        memory = MemoryStore(this)
        emotion = EmotionEngine(memory)
        commands = CommandProcessor(this)
        api = ClaudeApiClient(apiKey)
        voice = VoiceManager(this) { heard -> handleUserInput(heard) }
        voice.init()

        val recycler = findViewById<RecyclerView>(R.id.chatRecycler)
        adapter = ChatAdapter(messages)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        val input = findViewById<EditText>(R.id.inputField)
        val sendButton = findViewById<ImageButton>(R.id.sendButton)
        val micButton = findViewById<ImageButton>(R.id.micButton)
        val moodLabel = findViewById<TextView>(R.id.moodLabel)

        moodLabel.text = "Jarvis · ${emotion.moodDescriptor()}"

        // Replay short-term memory into the chat view on launch.
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

    private fun handleUserInput(text: String) {
        appendMessage(text, isUser = true)
        memory.addTurn("user", text)
        emotion.registerUserMessage(text)
        refreshMoodLabel()

        // 1. Try it as a device command first.
        when (val result = commands.process(text)) {
            is CommandProcessor.Result.Handled -> {
                appendMessage(result.spokenReply, isUser = false)
                memory.addTurn("assistant", result.spokenReply)
                voice.speak(result.spokenReply)
                return
            }
            CommandProcessor.Result.NotACommand -> { /* fall through to AI chat */ }
        }

        // 2. Otherwise, treat it as conversation and call Claude with memory + mood context.
        lifecycleScope.launch {
            val systemPrompt = buildSystemPrompt()
            val reply = try {
                api.sendMessage(systemPrompt, memory.recentTurns(), text)
            } catch (e: Exception) {
                "I hit an error reaching the model: ${e.message}"
            }
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
            What you know about the user so far:
            $facts

            If the user tells you a durable fact about themselves worth remembering
            (their name, a preference, a routine), acknowledge it naturally in your reply.
        """.trimIndent()
    }

    private fun appendMessage(text: String, isUser: Boolean) {
        adapter.addMessage(ChatMessage(text, isUser))
        findViewById<RecyclerView>(R.id.chatRecycler).scrollToPosition(messages.size - 1)
    }

    private fun refreshMoodLabel() {
        findViewById<TextView>(R.id.moodLabel).text = "Jarvis · ${emotion.moodDescriptor()}"
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
