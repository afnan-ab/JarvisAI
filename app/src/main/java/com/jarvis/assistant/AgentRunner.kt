package com.jarvis.assistant

import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class AgentRunner(
    private val gemini: GroqApiClient,
    private val commands: CommandProcessor
) {
    suspend fun run(instruction: String, maxSteps: Int = 12): String {
        val service = AssistantAccessibilityService.instance
            ?: return "I need the Accessibility permission turned on first - go to Settings > Accessibility > Jarvis and enable it, then try again."

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return runTextBased(instruction, service, maxSteps)
        }

        val actionsTaken = mutableListOf<String>()
        var consecutiveFailures = 0

        repeat(maxSteps) {
            val bitmap = captureScreenshotSuspend(service)
                ?: return "I couldn't capture the screen to see what's happening."
            val base64 = bitmapToBase64(bitmap)
            val width = bitmap.width
            val height = bitmap.height

            val prompt = """
                You are Jarvis controlling an Android phone screen step by step. Complete the ENTIRE instruction, not just the first action:
                "$instruction"

                Treat the instruction as a small task plan. Preserve the exact names, search terms, and message text.
                Examples:
                - "open WhatsApp and message X Fnd hi" means open WhatsApp, find the contact/chat X Fnd, open it, type exactly "hi", and send it.
                - "open Instagram Lite and search for bas boy on it" means open Instagram Lite, use its search UI, search exactly "bas boy", and stop when the results are visible.
                - "open Settings and find developer option" means open Settings, locate Developer options, and stop when it is visible.
                Do not stop after merely opening the requested app. Use the next screenshot to decide the next step.
                Prefer visible UI labels over guessing coordinates. If a search icon/button must be opened before typing, tap it first.
                IMPORTANT WhatsApp messaging rule: whenever the instruction asks to message/send a WhatsApp contact, ALWAYS use WhatsApp's own search flow. Open WhatsApp, tap the Search control, type the exact recipient name, tap the matching search result/chat, verify the chat is open, then type the exact requested message and press Send. Do NOT use a phone-number deep link, recent-chat shortcut, or direct URL to choose the recipient. Do NOT type the message before the recipient search result has been opened.
                Keep executing until the full instruction is completed. Only use "done" when it really is complete.

                Steps already taken: ${if (actionsTaken.isEmpty()) "none yet" else actionsTaken.joinToString("; ")}
                The screenshot is $width x $height pixels. Give tap coordinates within these bounds.

                Reply with ONLY one JSON object, nothing else, no markdown fences, no explanation. Pick one of:
                {"action":"open_app","app":"<app name>"}
                {"action":"tap","x":<number>,"y":<number>}
                {"action":"type","x":<number>,"y":<number>,"text":"<text to type>"}
                {"action":"send"}
                {"action":"scroll"}
                {"action":"back"}
                {"action":"done","summary":"<short summary spoken to the user>"}

                For "type", tap the field first at the given coordinates, then the text will be entered automatically.
                For message tasks, after typing the requested message, use "send" to press the visible Send button. Do not use "done" until the message is visibly present in the conversation.
            """.trimIndent()

            val raw = try {
                gemini.sendVisionMessage("Respond with raw JSON only, nothing else.", base64, prompt)
            } catch (e: Exception) {
                return "Agent stopped: ${e.message}"
            }

            val json = parseAgentJson(raw)
                ?: return "The agent returned an unreadable action, so I stopped safely."

            when (json.optString("action")) {
                "open_app" -> {
                    val app = json.optString("app")
                    if (!commands.openApp(app)) {
                        consecutiveFailures++
                        actionsTaken.add("failed to open $app")
                        if (consecutiveFailures >= 2) {
                            return "I couldn't open $app, so I stopped safely."
                        }
                        delay(700)
                        return@repeat
                    }
                    consecutiveFailures = 0
                    actionsTaken.add("opened $app")
                    delay(700)
                }
                "tap" -> {
                    val x = json.optDouble("x", -1.0).toFloat()
                    val y = json.optDouble("y", -1.0).toFloat()
                    if (x !in 0f..width.toFloat() || y !in 0f..height.toFloat() || !service.tapAt(x, y)) {
                        consecutiveFailures++
                        actionsTaken.add("failed tap ($x, $y)")
                        if (consecutiveFailures >= 2) {
                            return "I couldn't perform the requested tap, so I stopped safely."
                        }
                        delay(500)
                        return@repeat
                    }
                    consecutiveFailures = 0
                    actionsTaken.add("tapped ($x, $y)")
                    delay(600)
                }
                "type" -> {
                    val x = json.optDouble("x", -1.0).toFloat()
                    val y = json.optDouble("y", -1.0).toFloat()
                    val text = json.optString("text")
                    if (x !in 0f..width.toFloat() || y !in 0f..height.toFloat()) {
                        consecutiveFailures++
                        actionsTaken.add("failed type tap ($x, $y)")
                        if (consecutiveFailures >= 2) {
                            return "I couldn't reach the text field, so I stopped safely."
                        }
                        delay(500)
                        return@repeat
                    }
                    if (!service.tapAt(x, y)) {
                        consecutiveFailures++
                        actionsTaken.add("failed to focus text field")
                        if (consecutiveFailures >= 2) {
                            return "I couldn't focus the text field, so I stopped safely."
                        }
                        delay(500)
                        return@repeat
                    }
                    delay(500)
                    if (!service.typeIntoFocusedField(text)) {
                        consecutiveFailures++
                        actionsTaken.add("failed to type \"$text\"")
                        if (consecutiveFailures >= 2) {
                            return "I couldn't enter the requested text, so I stopped safely."
                        }
                        delay(500)
                        return@repeat
                    }
                    consecutiveFailures = 0
                    actionsTaken.add("typed \"$text\" at ($x, $y)")
                    delay(700)
                }
                "send" -> {
                    if (!service.tapByText("send")) {
                        consecutiveFailures++
                        actionsTaken.add("failed to press Send")
                        if (consecutiveFailures >= 2) {
                            return "I couldn't find the Send button, so I did not claim the message was sent."
                        }
                        delay(700)
                        return@repeat
                    }
                    consecutiveFailures = 0
                    actionsTaken.add("pressed Send")
                    delay(1200)
                }
                "scroll" -> {
                    if (!service.scrollDown()) {
                        consecutiveFailures++
                        actionsTaken.add("failed to scroll")
                        if (consecutiveFailures >= 2) {
                            return "I couldn't scroll this screen, so I stopped safely."
                        }
                        delay(500)
                        return@repeat
                    }
                    consecutiveFailures = 0
                    actionsTaken.add("scrolled")
                    delay(700)
                }
                "back" -> {
                    if (!service.pressBack()) {
                        consecutiveFailures++
                        actionsTaken.add("failed to press back")
                        if (consecutiveFailures >= 2) {
                            return "I couldn't go back from this screen, so I stopped safely."
                        }
                        delay(500)
                        return@repeat
                    }
                    consecutiveFailures = 0
                    actionsTaken.add("pressed back")
                    delay(700)
                }
                "done" -> {
                    val summary = json.optString("summary", "Done.")
                    if (verifyCompletion(instruction, service, actionsTaken)) {
                        return summary
                    }
                    consecutiveFailures++
                    actionsTaken.add("agent claimed done, but verification failed")
                    if (consecutiveFailures >= 2) {
                        return "The requested task does not look complete yet, so I stopped instead of claiming success."
                    }
                    delay(700)
                    return@repeat
                }
                else -> return "I wasn't sure how to continue, so I stopped."
            }

            delay(1000)
        }

        return "I tried several steps but couldn't finish that fully - want me to keep going?"
    }

    private fun verifyCompletion(
        instruction: String,
        service: AssistantAccessibilityService,
        actionsTaken: List<String>
    ): Boolean {
        val text = service.readScreenText().lowercase()
        val pkg = service.activePackageName().lowercase()
        val t = instruction.lowercase()

        fun hasAny(vararg values: String) = values.any { text.contains(it) }
        fun appIs(vararg values: String) = values.any { pkg.contains(it) }

        val appOk = when {
            t.contains("whatsapp") -> appIs("whatsapp")

            t.contains("instagram lite") -> appIs("instagram")
            t.contains("instagram") -> appIs("instagram")
            t.contains("settings") -> appIs("settings")
            t.contains("file manager") -> appIs("file", "documents", "files")
            else -> true
        }

        val contentOk = when {
            Regex("""\b(message|send|bhejo|kaho|saying)\b""").containsMatchIn(t) -> {
                val sent = actionsTaken.any { it == "pressed Send" }
                sent && (text.isNotBlank() || appOk)
            }
            t.contains("search") -> {
                val query = Regex("""search(?: for)?\s+(.+?)(?:\s+on it|$)""").find(t)?.groupValues?.getOrNull(1)?.trim()
                query.isNullOrBlank() || text.contains(query)
            }
            t.contains("developer option") -> hasAny("developer options", "developer option")
            t.contains("find jarvis") -> text.contains("jarvis")
            else -> true
        }

        return appOk && contentOk
    }

    private fun parseAgentJson(raw: String): JSONObject? {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        try {
            return JSONObject(cleaned)
        } catch (_: Exception) {
            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            if (start >= 0 && end > start) {
                try {
                    return JSONObject(cleaned.substring(start, end + 1))
                } catch (_: Exception) {
                    return null
                }
            }
        }
        return null
    }

    private suspend fun captureScreenshotSuspend(service: AssistantAccessibilityService): Bitmap? =
        suspendCancellableCoroutine { cont ->
            service.captureScreenshot { bitmap -> cont.resume(bitmap, null) }
        }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    // Fallback for Android below 11 (no screenshot API): reads element labels as text instead.
    private suspend fun runTextBased(
        instruction: String,
        service: AssistantAccessibilityService,
        maxSteps: Int
    ): String {
        val actionsTaken = mutableListOf<String>()
        repeat(maxSteps) {
            val elements = service.listInteractiveElements()
            val screenDescription = if (elements.isEmpty()) "(no interactive elements detected on screen)"
                else elements.joinToString("\n") {
                    "${it.index}: \"${it.label}\" ${if (it.editable) "[text field]" else if (it.clickable) "[button]" else ""}"
                }

            val prompt = """
                You are Jarvis controlling an Android phone screen step by step. Complete the ENTIRE instruction:
                "$instruction"

                Treat it as a multi-step task and preserve exact names, search terms, and message text.
                Do not stop after opening an app. If the instruction says "open WhatsApp and message X Fnd hi",
                continue through finding X Fnd, opening the chat, entering "hi", and sending it.
                If it says "open Instagram Lite and search for bas boy", continue until the search results are visible.
                If it says "open Settings and find developer option", continue until Developer options is visible.
                Use visible labels/hints/IDs when available. If a search box is not visible, open the search control first.
                Only use "done" when the whole instruction is complete.

                Steps already taken: ${if (actionsTaken.isEmpty()) "none yet" else actionsTaken.joinToString("; ")}

                Current screen elements (numbered):
                $screenDescription

                If there is no visible text search box on screen yet, look for a search icon or button
                (often labeled "desc: Search" or a magnifying glass) and tap that first.
                For WhatsApp messaging, the recipient search box is mandatory: never choose a person from the home/recent list when the Search control is available.
                Elements list search fields by hint text or id when they have no visible label - use those.
                If the element you need is not in the list, try "scroll" first before giving up.
                Reply with ONLY one JSON object, nothing else, no markdown fences, no explanation. Pick one of:
                {"action":"open_app","app":"<app name>"}
                {"action":"tap","index":<number>}
                {"action":"type","index":<number>,"text":"<text to type>"}
                {"action":"send"}
                {"action":"scroll"}
                {"action":"back"}
                {"action":"done","summary":"<short summary spoken to the user>"}

                Use "done" once the instruction is complete, or if you're stuck after repeated attempts.
            """.trimIndent()

            val raw = try {
                gemini.sendMessage("Respond with raw JSON only, nothing else.", emptyList(), prompt)
            } catch (e: Exception) {
                return "Agent stopped: ${e.message}"
            }

            val json = parseAgentJson(raw)
                ?: return "The agent returned an unreadable action, so I stopped safely."

            when (json.optString("action")) {
                "open_app" -> {
                    val app = json.optString("app")
                    if (!commands.openApp(app)) return "I couldn't open $app, so I stopped safely."
                    actionsTaken.add("opened $app")
                    delay(700)
                }
                "tap" -> {
                    val idx = json.optInt("index", -1)
                    if (!service.tapElement(idx)) return "I couldn't tap the requested control, so I stopped safely."
                    actionsTaken.add("tapped element $idx")
                }
                "type" -> {
                    val idx = json.optInt("index", -1)
                    val text = json.optString("text")
                    if (!service.typeIntoElement(idx, text)) return "I couldn't enter the requested text, so I stopped safely."
                    actionsTaken.add("typed \"$text\" into element $idx")
                }
                "send" -> {
                    if (!service.tapByText("send")) {
                        return "I couldn't find the Send button, so I did not claim the message was sent."
                    }
                    actionsTaken.add("pressed Send")
                    delay(1200)
                }
                "scroll" -> {
                    if (!service.scrollDown()) return "I couldn't scroll this screen, so I stopped safely."
                    actionsTaken.add("scrolled")
                }
                "back" -> {
                    if (!service.pressBack()) return "I couldn't go back from this screen, so I stopped safely."
                    actionsTaken.add("pressed back")
                }
                "done" -> {
                    val summary = json.optString("summary", "Done.")
                    if (verifyCompletion(instruction, service, actionsTaken)) {
                        return summary
                    }
                    actionsTaken.add("agent claimed done, but verification failed")
                    delay(700)
                }
                else -> return "I wasn't sure how to continue, so I stopped."
            }

            delay(1200)
        }

        return "I tried several steps but couldn't finish that fully - want me to keep going?"
    }
}
