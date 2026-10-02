package com.jarvis.assistant

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class HudScreenActivity : AppCompatActivity() {
    private val bg = Color.rgb(2, 7, 13)
    private val panel = Color.rgb(7, 18, 27)
    private val cyan = Color.rgb(65, 225, 255)
    private val textColor = Color.rgb(215, 245, 252)
    private val muted = Color.rgb(92, 157, 177)
    private val green = Color.rgb(55, 231, 161)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        setContentView(buildScreen(intent.getStringExtra("screen") ?: "menu"))
    }

    private fun buildScreen(screen: String): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 12, 14, 14)
            setBackgroundColor(bg)
        }
        val topLine = View(this).apply { setBackgroundColor(cyan) }
        root.addView(topLine, LinearLayout.LayoutParams(-1, 1))
        root.addView(TextView(this).apply {
            text = "J A R V I S"
            setTextColor(textColor); textSize = 24f
            typeface = Typeface.DEFAULT_BOLD; letterSpacing = .16f
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(-1, 48))
        root.addView(TextView(this).apply {
            text = subtitle(screen); setTextColor(muted); textSize = 8f
            letterSpacing = .09f; gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(-1, 28))

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 4, 0, 4)
        }
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

        root.addView(button("‹  BACK").apply { setOnClickListener { finish() } },
            LinearLayout.LayoutParams(-1, 46).apply { topMargin = 8 })
        return root
    }

    private fun subtitle(screen: String) = when (screen) {
        "listening" -> "VOICE INTERFACE  //  LISTENING"
        "whatsapp" -> "WHATSAPP AUTOMATION  //  ACTIVE TASK"
        "apps" -> "INSTALLED APPS  //  QUICK LAUNCH"
        "tools" -> "QUICK ACTIONS  //  SYSTEM CONTROL"
        "memory" -> "AI MEMORY  //  CONVERSATION CONTEXT"
        "developer" -> "DEVELOPER OPTIONS  //  ADVANCED"
        "about" -> "ABOUT JARVIS  //  SYSTEM INFORMATION"
        else -> "MAIN MENU  //  JARVIS CONTROL CENTER"
    }

    private fun menu(c: LinearLayout) {
        title(c, "MAIN MENU")
        card(c, "VOICE", "Start listening") { open("listening") }
        card(c, "WHATSAPP", "Message automation") { open("whatsapp") }
        card(c, "INSTALLED APPS", "Open any launchable app") { open("apps") }
        card(c, "QUICK ACTIONS", "Phone and system controls") { open("tools") }
        card(c, "AI MEMORY", "Conversation context") { open("memory") }
        card(c, "DEVELOPER OPTIONS", "Advanced controls") { open("developer") }
        card(c, "ABOUT JARVIS", "System information") { open("about") }
    }

    private fun listening(c: LinearLayout) {
        title(c, "LISTENING")
        big(c, "J")
        status(c, "LISTENING", "SPEAK YOUR COMMAND", cyan)
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
        val pm = packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }

        if (apps.isEmpty()) {
            card(c, "NO APPS FOUND", "Android returned no launchable applications.")
            return
        }
        apps.forEach { (pkg, label) ->
            list(c, label, pkg) {
                try { pm.getLaunchIntentForPackage(pkg)?.let { startActivity(it) } } catch (_: Exception) {}
            }
        }
    }

    private fun tools(c: LinearLayout) {
        title(c, "QUICK ACTIONS")
        card(c, "ANDROID SETTINGS", "Open system Settings") {
            safeStart(Intent(Settings.ACTION_SETTINGS))
        }
        card(c, "WI-FI", "Open Wi-Fi settings") {
            safeStart(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
        card(c, "BLUETOOTH", "Open Bluetooth settings") {
            safeStart(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        card(c, "DISPLAY", "Open display settings") {
            safeStart(Intent(Settings.ACTION_DISPLAY_SETTINGS))
        }
        card(c, "SOUND", "Open sound settings") {
            safeStart(Intent(Settings.ACTION_SOUND_SETTINGS))
        }
        card(c, "APPS", "Open installed-app settings") {
            safeStart(Intent(Settings.ACTION_APPLICATION_SETTINGS))
        }
        card(c, "ACCESSIBILITY", "Open accessibility settings") {
            safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        card(c, "DEVELOPER OPTIONS", "Open Android developer settings") {
            safeStart(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }
    }

    private fun memory(c: LinearLayout) {
        title(c, "AI MEMORY")
        status(c, "MEMORY ONLINE", "CONVERSATION CONTEXT PRESERVED", green)
        card(c, "RECENT CONTEXT", "Conversation context is preserved by JARVIS.")
        card(c, "REMEMBERED ITEMS", "Stored context is handled by MemoryStore.")
    }

    private fun developer(c: LinearLayout) {
        title(c, "DEVELOPER OPTIONS")
        status(c, "ADVANCED MODE", "SYSTEM DIAGNOSTICS", cyan)
        card(c, "ACCESSIBILITY SERVICE", "Screen reading and tapping")
        card(c, "AGENT MODE", "Multi-step screen automation")
        card(c, "GROQ AGENT", "Natural-language reasoning")
        card(c, "ANDROID DEVELOPER OPTIONS", "Open real Android developer settings") {
            safeStart(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }
    }

    private fun about(c: LinearLayout) {
        title(c, "ABOUT JARVIS")
        big(c, "J")
        status(c, "SYSTEM ONLINE", "PERSONAL AI ASSISTANT", green)
        card(c, "VERSION", "0.1")
        card(c, "CAPABILITIES", "Voice, typing, Android actions, screen automation, memory and Groq reasoning.")
    }

    private fun title(c: LinearLayout, s: String) {
        c.addView(TextView(this).apply {
            text = s; setTextColor(cyan); textSize = 10f
            typeface = Typeface.DEFAULT_BOLD; letterSpacing = .14f
            setPadding(4, 6, 0, 10)
        })
    }

    private fun status(c: LinearLayout, a: String, b: String, color: Int) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14, 10, 14, 10)
            background = rounded(Color.rgb(8, 22, 31), Color.rgb(40, 105, 125), 1, 18)
        }
        box.addView(TextView(this).apply {
            text = "●  $a"; setTextColor(color); textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
        })
        box.addView(TextView(this).apply {
            text = b; setTextColor(muted); textSize = 8f
        })
        c.addView(box, LinearLayout.LayoutParams(-1, 62).apply { bottomMargin = 9 })
    }

    private fun card(c: LinearLayout, heading: String, body: String, action: (() -> Unit)? = null) {
        val b = button(heading + "\n" + body)
        b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        b.setOnClickListener { action?.invoke() }
        c.addView(b, LinearLayout.LayoutParams(-1, 64).apply { bottomMargin = 8 })
    }

    private fun list(c: LinearLayout, name: String, kind: String, action: (() -> Unit)? = null) {
        val b = button(name.uppercase() + "\n" + kind)
        b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        b.setOnClickListener { action?.invoke() }
        c.addView(b, LinearLayout.LayoutParams(-1, 58).apply { bottomMargin = 7 })
    }

    private fun step(c: LinearLayout, n: String, label: String, done: Boolean) {
        val mark = if (done) "✓" else "○"
        card(c, "$mark  $n  $label", if (done) "COMPLETED" else "WAITING")
    }

    private fun big(c: LinearLayout, value: String) {
        c.addView(TextView(this).apply {
            text = value; gravity = Gravity.CENTER; setTextColor(cyan)
            textSize = 72f; typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(24f, 0f, 0f, cyan)
        }, LinearLayout.LayoutParams(-1, 150))
    }

    private fun button(label: String) = Button(this).apply {
        text = label; textSize = 9f; setTextColor(textColor)
        typeface = Typeface.DEFAULT_BOLD; letterSpacing = .055f
        background = rounded(panel, Color.rgb(48, 118, 139), 1, 18)
        setPadding(16, 5, 16, 5)
        isAllCaps = false
    }

    private fun rounded(fill: Int, stroke: Int, width: Int, radius: Int) =
        GradientDrawable().apply {
            setColor(fill); setStroke(width, stroke); cornerRadius = radius.toFloat()
        }

    private fun safeStart(intent: Intent) {
        try { startActivity(intent) } catch (_: Exception) {}
    }

    private fun open(screen: String) {
        startActivity(Intent(this, HudScreenActivity::class.java).putExtra("screen", screen))
    }
}
