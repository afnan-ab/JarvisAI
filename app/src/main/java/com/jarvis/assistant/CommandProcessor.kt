package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.MediaStore
import android.telephony.SmsManager

class CommandProcessor(private val context: Context) {

    sealed class Result {
        data class Handled(val spokenReply: String) : Result()
        object NotACommand : Result()
    }

    private val contactsHelper = ContactsHelper(context)
    private val security = SecuritySettings(context)
    private var pendingAction: (() -> String)? = null

    fun process(text: String): Result {
        pendingAction?.let { action ->
            val ok = security.checkPassphrase(text.trim())
            val pending = pendingAction
            pendingAction = null
            return if (ok) {
                Result.Handled(pending!!.invoke())
            } else {
                Result.Handled("That passphrase didn't match, so I've cancelled that action.")
            }
        }

        val segments = text.split(Regex("""(?i),| aur | phir | and | then |;"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (segments.size <= 1) {
            return processSingle(text.trim(), isStandalone = true)
        }

        val replies = mutableListOf<String>()
        for (segment in segments) {
            when (val r = processSingle(segment, isStandalone = false)) {
                is Result.Handled -> replies.add(r.spokenReply)
                Result.NotACommand -> return Result.NotACommand
            }
        }
        return Result.Handled(replies.joinToString(" "))
    }

    private fun processSingle(raw: String, isStandalone: Boolean): Result {
        val t = raw.trim().lowercase()

        if (t.contains("turn on torch") || t.contains("turn on flashlight") || t == "torch on" || t == "flashlight on") {
            return if (setTorch(true)) Result.Handled("Turning on the torch.")
            else Result.Handled("I couldn't access the flashlight on this device.")
        }
        if (t.contains("turn off torch") || t.contains("turn off flashlight") || t == "torch off" || t == "flashlight off") {
            return if (setTorch(false)) Result.Handled("Turning off the torch.")
            else Result.Handled("I couldn't access the flashlight on this device.")
        }

        if (t.contains("open camera") || t == "take a photo") {
            context.startActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return Result.Handled("Opening the camera.")
        }

        Regex("""^(.+?) ko (?:whatsapp|message) (?:karo|bhejo) (.+)$""").find(t)?.let { m ->
            return gateSensitive(
                "message ${m.groupValues[1].trim()}"
            ) { sendWhatsAppMessage(m.groupValues[1].trim(), m.groupValues[2].trim()) }
        }
        Regex("""^message (\w+) (.+)$""").find(t)?.let { m ->
            return gateSensitive(
                "message ${m.groupValues[1].trim()}"
            ) { sendWhatsAppMessage(m.groupValues[1].trim(), m.groupValues[2].trim()) }
        }
        Regex("""^text (\w+) saying (.+)$""").find(t)?.let { m ->
            return gateSensitive(
                "message ${m.groupValues[1].trim()}"
            ) { sendWhatsAppMessage(m.groupValues[1].trim(), m.groupValues[2].trim()) }
        }

        Regex("""^(.+) ko (?:call|phone) karo$""").find(t)?.let { m ->
            val target = m.groupValues[1].trim()
            return gateSensitive("call $target") { performCall(target) }
        }
        Regex("""^call (.+)$""").find(t)?.let { m ->
            val target = m.groupValues[1].trim()
            return gateSensitive("call $target") { performCall(target) }
        }

        Regex("""^(.+) khol do$""").find(t)?.let { m -> return openAppResult(m.groupValues[1].trim()) }
        Regex("""^(.+) kholo$""").find(t)?.let { m -> return openAppResult(m.groupValues[1].trim()) }
        Regex("""^(.+) open karo$""").find(t)?.let { m -> return openAppResult(m.groupValues[1].trim()) }
        Regex("""^open (.+)$""").find(t)?.let { m -> return openAppResult(m.groupValues[1].trim()) }

        Regex("""^set (?:an )?alarm for (\d{1,2})[:\s]?(\d{2})?\s*(am|pm)?$""").find(t)?.let { m ->
            val hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].toIntOrNull() ?: 0
            val ampm = m.groupValues[3]
            val hour24 = when {
                ampm == "pm" && hour < 12 -> hour + 12
                ampm == "am" && hour == 12 -> 0
                else -> hour
            }
            setAlarm(hour24, minute)
            return Result.Handled("Alarm set for ${"%02d".format(hour24)}:${"%02d".format(minute)}.")
        }

        Regex("""^(?:google|web) search(?: for)? (.+)$""").find(t)?.let { m ->
            webSearch(m.groupValues[1])
            return Result.Handled("Searching the web for ${m.groupValues[1]}.")
        }

        if (isStandalone) {
            Regex("""^search(?: for)? (.+)$""").find(t)?.let { m ->
                webSearch(m.groupValues[1])
                return Result.Handled("Searching for ${m.groupValues[1]}.")
            }
        }

        if (t.contains("volume up")) { adjustVolume(true); return Result.Handled("Turning it up.") }
        if (t.contains("volume down")) { adjustVolume(false); return Result.Handled("Turning it down.") }

        return Result.NotACommand
    }

    private fun gateSensitive(label: String, action: () -> String): Result {
        return if (security.isEnabled()) {
            pendingAction = action
            Result.Handled("Before I $label, please say the passphrase.")
        } else {
            Result.Handled(action())
        }
    }

    private fun openAppResult(appName: String): Result {
        return if (openApp(appName)) Result.Handled("Opening $appName.")
        else Result.Handled("I couldn't find an app called $appName on this phone.")
    }

    private fun performCall(target: String): String {
        return if (Regex("""[\d+][\d\s-]{5,}""").matches(target)) {
            dial(target)
            "Calling $target."
        } else {
            val number = contactsHelper.findPhoneNumber(target)
            if (number != null) {
                dial(number)
                "Calling $target."
            } else {
                dialSearch(target)
                "I couldn't find $target in your contacts, so I've opened the dialer."
            }
        }
    }

    private fun sendWhatsAppMessage(name: String, message: String): String {
        val number = contactsHelper.findPhoneNumber(name)
            ?: return "I couldn't find a contact named $name to message."
        val encoded = Uri.encode(message)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://api.whatsapp.com/send?phone=$number&text=$encoded")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        Handler(Looper.getMainLooper()).postDelayed({
            AssistantAccessibilityService.instance?.tapByText("send")
        }, 3000)
        return "Opening WhatsApp to message $name: \"$message\"."
    }

    private fun setTorch(on: Boolean): Boolean {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return false
            cameraManager.setTorchMode(cameraId, on)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun openApp(name: String): Boolean {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val needle = normalize(name)
        val match = apps.firstOrNull {
            normalize(pm.getApplicationLabel(it).toString()).contains(needle)
        } ?: return false
        val launchIntent = pm.getLaunchIntentForPackage(match.packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return true
    }

    private fun normalize(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun dial(number: String) {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun dialSearch(query: String) {
        val intent = Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun sendSms(number: String, message: String) {
        val smsManager = context.getSystemService(SmsManager::class.java)
        smsManager.sendTextMessage(number, null, message, null, null)
    }

    private fun setAlarm(hour: Int, minute: Int) {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun webSearch(query: String) {
        val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra("query", query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun adjustVolume(up: Boolean) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
    }
}
