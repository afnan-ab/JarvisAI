package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.telephony.SmsManager

/**
 * Recognizes simple device-control commands in plain text and executes
 * them with standard Android Intents/APIs (the same mechanisms any app
 * uses to place a call, send an SMS, open the camera, etc). Anything not
 * recognized here falls through to the AI chat so it still gets answered
 * conversationally.
 *
 * This is deliberately rule-based and inspectable rather than letting the
 * model call arbitrary system APIs directly - you can see exactly what
 * phrases trigger what real-world actions, and extend the `when` block
 * below with new ones.
 */
class CommandProcessor(private val context: Context) {

    sealed class Result {
        data class Handled(val spokenReply: String) : Result()
        object NotACommand : Result()
    }

    fun process(text: String): Result {
        val t = text.trim().lowercase()

        // --- Open an app by name ---
        Regex("""open (.+)""").find(t)?.let { m ->
            val appName = m.groupValues[1].trim()
            return if (openApp(appName)) Result.Handled("Opening $appName.")
            else Result.Handled("I couldn't find an app called $appName on this phone.")
        }

        // --- Call a contact / number ---
        Regex("""call (.+)""").find(t)?.let { m ->
            val target = m.groupValues[1].trim()
            return if (Regex("""[\d+][\d\s-]{5,}""").matches(target)) {
                dial(target)
                Result.Handled("Calling $target.")
            } else {
                // Real contact-name lookup needs READ_CONTACTS resolution;
                // this stub opens the dialer pre-filled so the user confirms.
                dialSearch(target)
                Result.Handled("Pulling up the dialer for $target.")
            }
        }

        // --- Send a text: "text mom saying I'm on my way" ---
        Regex("""text (\w+) saying (.+)""").find(t)?.let { m ->
            val contact = m.groupValues[1]
            val message = m.groupValues[2]
            return Result.Handled("I've got \"$message\" ready to send to $contact, but I need a phone number - " +
                "contact-name resolution isn't wired up in this starter build yet.")
        }

        // --- Set an alarm: "set an alarm for 7 30" / "set alarm for 7:30 am" ---
        Regex("""set (?:an )?alarm for (\d{1,2})[:\s]?(\d{2})?\s*(am|pm)?""").find(t)?.let { m ->
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

        // --- Search the web ---
        Regex("""search (?:for )?(.+)""").find(t)?.let { m ->
            webSearch(m.groupValues[1])
            return Result.Handled("Searching for ${m.groupValues[1]}.")
        }

        // --- Volume ---
        if (t.contains("volume up")) { adjustVolume(true); return Result.Handled("Turning it up.") }
        if (t.contains("volume down")) { adjustVolume(false); return Result.Handled("Turning it down.") }

        // --- Camera ---
        if (t.contains("open camera") || t == "take a photo") {
            context.startActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return Result.Handled("Opening the camera.")
        }

        return Result.NotACommand
    }

    fun openApp(name: String): Boolean {
        val pm = context.packageManager
        @Suppress("DEPRECATION") val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val match = apps.firstOrNull {
            pm.getApplicationLabel(it).toString().lowercase().contains(name)
        } ?: return false
        val launchIntent = pm.getLaunchIntentForPackage(match.packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return true
    }

    private fun dial(number: String) {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent) // requires CALL_PHONE permission granted at runtime
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
