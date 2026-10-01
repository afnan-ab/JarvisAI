package com.jarvis.assistant

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.content.Intent
import android.content.pm.PackageManager
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class HudScreenActivity : AppCompatActivity() {
    private val cyan = Color.rgb(65, 225, 255)
    private val textColor = Color.rgb(215, 245, 252)
    private val muted = Color.rgb(92, 157, 177)
    private val green = Color.rgb(55, 231, 161)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = intent.getStringExtra("screen") ?: "menu"
        setContentView(buildScreen(screen))
    }

    private fun buildScreen(screen: String): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 14, 16, 18)
            setBackgroundColor(Color.rgb(2, 7, 13))
        }
        root.addView(TextView(this).apply {
            text = "J A R V I S"
            setTextColor(textColor); textSize = 24f
            typeface = Typeface.DEFAULT_BOLD; letterSpacing = .13f
        })
        root.addView(TextView(this).apply {
            text = subtitle(screen); setTextColor(muted); textSize = 8f
            letterSpacing = .08f; setPadding(2, 2, 0, 14)
        })
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        when (screen) {
            "listening" -> listening(content)
            "whatsapp" -> whatsapp(content)
            "apps" -> apps(content)
            "tools" -> tools(content)
            "memory" -> memory(content)
            "developer" -> developer(content)
            "about" -> about(content)
            else -> menu(content)
        }

        root.addView(button("‹  BACK TO JARVIS").apply { setOnClickListener { finish() } },
            LinearLayout.LayoutParams(-1, 48).apply { topMargin = 10 })
        return root
    }

    private fun subtitle(screen: String) = when (screen) {
        "listening" -> "VOICE INTERFACE  //  LISTENING"
        "whatsapp" -> "WHATSAPP AUTOMATION  //  ACTIVE TASK"
        "apps" -> "INSTALLED APPS  //  QUICK LAUNCH"
        "tools" -> "TOOLS  //  SYSTEM CONTROL"
        "memory" -> "AI MEMORY  //  CONVERSATION CONTEXT"
        "developer" -> "DEVELOPER OPTIONS  //  ADVANCED"
        "about" -> "ABOUT JARVIS  //  SYSTEM INFORMATION"
        else -> "MAIN MENU  //  JARVIS CONTROL CENTER"
    }

    private fun menu(c: LinearLayout) {
        title(c, "MAIN MENU")
        card(c, "VOICE", "Start the JARVIS listening interface.") { open("listening") }
        card(c, "WHATSAPP", "View the deterministic message automation flow.") { open("whatsapp") }
        card(c, "APPS", "Browse the installed-app launcher interface.") { open("apps") }
        card(c, "TOOLS", "Phone controls and automation tools.") { open("tools") }
        card(c, "MEMORY", "Conversation memory and stored context.") { open("memory") }
        card(c, "DEVELOPER OPTIONS", "Advanced JARVIS diagnostics and controls.") { open("developer") }
        card(c, "ABOUT JARVIS", "Version and assistant information.") { open("about") }
    }

    private fun listening(c: LinearLayout) {
        title(c, "LISTENING"); big(c, "◉")
        status(c, "LISTENING", "SPEAK YOUR COMMAND", cyan)
        card(c, "VOICE INPUT", "JARVIS is waiting for a spoken command.")
    }

    private fun whatsapp(c: LinearLayout) {
        title(c, "WHATSAPP MESSAGE")
        status(c, "AUTOMATION ACTIVE", "EXACT RECIPIENT SEARCH FLOW", green)
        step(c, "01", "OPEN WHATSAPP", true)
        step(c, "02", "OPEN SEARCH", true)
        step(c, "03", "SEARCH EXACT CONTACT", true)
        step(c, "04", "OPEN MATCHING CHAT", true)
        step(c, "05", "VERIFY CHAT", true)
        step(c, "06", "TYPE MESSAGE", false)
        step(c, "07", "PRESS SEND", false)
        step(c, "08", "VERIFY SENT MESSAGE", false)
    }

    private fun apps(c: LinearLayout) {
        title(c, "INSTALLED APPS")
        list(c, "WhatsApp", "Messaging"); list(c, "YouTube", "Video")
        list(c, "Chrome", "Browser"); list(c, "Settings", "Android System")
        list(c, "Camera", "Camera"); list(c, "Phone", "Communication")
    }

    private fun tools(c: LinearLayout) {
        title(c, "QUICK ACTIONS")
        card(c, "FLASHLIGHT", "Turn the phone torch on or off.")
        card(c, "VOLUME", "Increase or decrease media volume.")
        card(c, "ANDROID SETTINGS", "Open the real Android Settings screen.")
        card(c, "ACCESSIBILITY", "Open accessibility controls for JARVIS.")
        card(c, "SCREEN AUTOMATION", "Use JARVIS screen actions when accessibility is enabled.")
    }

    private fun memory(c: LinearLayout) {
        title(c, "AI MEMORY")
        status(c, "MEMORY ONLINE", "CONVERSATION CONTEXT PRESERVED", green)
        card(c, "RECENT CONTEXT", "JARVIS can preserve conversation context between launches.")
        card(c, "REMEMBERED ITEMS", "Stored user-approved memories appear here.")
        card(c, "MEMORY CONTROL", "Memory is handled by the existing MemoryStore.")
    }

    private fun developer(c: LinearLayout) {
        title(c, "DEVELOPER OPTIONS")
        status(c, "ADVANCED MODE", "SYSTEM DIAGNOSTICS", cyan)
        card(c, "ACCESSIBILITY SERVICE", "Required for screen reading and tapping.")
        card(c, "AGENT MODE", "Vision and multi-step screen automation.")
        card(c, "GROQ AGENT", "Natural-language reasoning layer.")
        card(c, "DEBUG STATUS", "Runtime diagnostics and task verification.")
    }

    private fun about(c: LinearLayout) {
        title(c, "ABOUT JARVIS"); big(c, "J")
        status(c, "SYSTEM ONLINE", "PERSONAL AI ASSISTANT", green)
        card(c, "JARVIS", "Personal Android AI assistant.")
        card(c, "VERSION", "0.1")
        card(c, "CAPABILITIES", "Voice input, typed commands, Android actions, screen automation, memory and Groq-powered reasoning.")
    }

    private fun title(c: LinearLayout, s: String) {
        c.addView(TextView(this).apply {
            text = s; setTextColor(cyan); textSize = 10f
            typeface = Typeface.DEFAULT_BOLD; letterSpacing = .14f
            setPadding(0, 2, 0, 10)
        })
    }

    private fun status(c: LinearLayout, a: String, b: String, color: Int) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(14, 12, 14, 12); setBackgroundColor(Color.rgb(8, 22, 31))
        }
        box.addView(TextView(this).apply {
            text = "●  " + a; setTextColor(color); textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
        })
        box.addView(TextView(this).apply {
            text = b; setTextColor(muted); textSize = 8f; setPadding(0, 4, 0, 0)
        })
        c.addView(box, LinearLayout.LayoutParams(-1, 62).apply { bottomMargin = 9 })
    }

    private fun card(c: LinearLayout, heading: String, body: String, action: (() -> Unit)? = null) {
        val b = button(heading + "\n" + body)
        b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        b.setOnClickListener { action?.invoke() }
        c.addView(b, LinearLayout.LayoutParams(-1, 68).apply { bottomMargin = 8 })
    }

    private fun list(c: LinearLayout, name: String, kind: String, action: (() -> Unit)? = null) =
        card(c, name.uppercase(), kind, action)

    private fun step(c: LinearLayout, n: String, label: String, done: Boolean) {
        val mark = if (done) "✓" else "○"
        card(c, mark + "  " + n + "  " + label, if (done) "COMPLETED" else "WAITING")
    }

    private fun big(c: LinearLayout, value: String) {
        c.addView(TextView(this).apply {
            text = value; gravity = Gravity.CENTER; setTextColor(cyan)
            textSize = 64f; typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(20f, 0f, 0f, cyan)
        }, LinearLayout.LayoutParams(-1, 150))
    }

    private fun button(label: String) = Button(this).apply {
        text = label; textSize = 9f; setTextColor(textColor)
        typeface = Typeface.DEFAULT_BOLD; letterSpacing = .06f
        setBackgroundColor(Color.rgb(7, 22, 31)); setPadding(14, 6, 14, 6)
    }

    private fun open(screen: String) {
        startActivity(android.content.Intent(this, HudScreenActivity::class.java).putExtra("screen", screen))
    }
}
