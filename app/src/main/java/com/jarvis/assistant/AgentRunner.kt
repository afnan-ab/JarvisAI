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

        repeat(maxSteps) {
            val bitmap = captureScreenshotSuspend(service)
                ?: return "I couldn't capture the screen to see what's happening."
            val base64 = bitmapToBase64(bitmap)
            val width = bitmap.width
            val height = bitmap.height

            val prompt = """
                You are controlling an Android phone by looking at a screenshot. Complete this instruction: "$instruction"
                Steps already taken: ${if (actionsTaken.isEmpty()) "none yet" else actionsTaken.joinToString("; ")}
                The screenshot is $width x $height pixels. Give tap coordinates within these bounds.

                Reply with ONLY one JSON object, nothing else, no markdown fences, no explanation. Pick one of:
                {"action":"open_app","app":"<app name>"}
                {"action":"tap","x":<number>,"y":<number>}
                {"action":"type","x":<number>,"y":<number>,"text":"<text to type>"}
                {"action":"scroll"}
                {"action":"back"}
                {"action":"done","summary":"<short summary spoken to the user>"}

                For "type", tap the field first at the given coordinates, then the text will be entered automatically.
                Use "done" once the instruction is complete, or if you're stuck after repeated attempts.
            """.trimIndent()

            val raw = try {
                gemini.sendVisionMessage("Respond with raw JSON only, nothing else.", base64, prompt)
            } catch (e: Exception) {
                return "Agent stopped: ${e.message}"
            }

            val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val json = try {
                JSONObject(cleaned)
            } catch (e: Exception) {
                return "I got confused reading the screen and stopped there."
            }

            when (json.optString("action")) {
                "open_app" -> {
                    val app = json.optString("app")
                    commands.openApp(app)
                    actionsTaken.add("opened $app")
                }
                "tap" -> {
                    val x = json.optDouble("x", -1.0).toFloat()
                    val y = json.optDouble("y", -1.0).toFloat()
                    service.tapAt(x, y)
                    actionsTaken.add("tapped ($x, $y)")
                    delay(600)
                }
                "type" -> {
                    val x = json.optDouble("x", -1.0).toFloat()
                    val y = json.optDouble("y", -1.0).toFloat()
                    val text = json.optString("text")
                    service.tapAt(x, y)
                    delay(500)
                    service.typeIntoFocusedField(text)
                    actionsTaken.add("typed \"$text\" at ($x, $y)")
                }
                "scroll" -> {
                    service.scrollDown()
                    actionsTaken.add("scrolled")
                }
                "back" -> {
                    service.pressBack()
                    actionsTaken.add("pressed back")
                }
                "done" -> {
                    return json.optString("summary", "Done.")
                }
                else -> return "I wasn't sure how to continue, so I stopped."
            }

            delay(1000)
        }

        return "I tried several steps but couldn't finish that fully - want me to keep going?"
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
                You are controlling an Android phone screen step by step to complete this instruction: "$instruction"
                Steps already taken: ${if (actionsTaken.isEmpty()) "none yet" else actionsTaken.joinToString("; ")}

                Current screen elements (numbered):
                $screenDescription

                If there is no visible text search box on screen yet, look for a search icon or button
                (often labeled "desc: Search" or a magnifying glass) and tap that first.
                Elements list search fields by hint text or id when they have no visible label - use those.
                If the element you need is not in the list, try "scroll" first before giving up.
                Reply with ONLY one JSON object, nothing else, no markdown fences, no explanation. Pick one of:
                {"action":"open_app","app":"<app name>"}
                {"action":"tap","index":<number>}
                {"action":"type","index":<number>,"text":"<text to type>"}
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

            val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val json = try {
                JSONObject(cleaned)
            } catch (e: Exception) {
                return "I got confused reading the screen and stopped there."
            }

            when (json.optString("action")) {
                "open_app" -> {
                    val app = json.optString("app")
                    commands.openApp(app)
                    actionsTaken.add("opened $app")
                }
                "tap" -> {
                    val idx = json.optInt("index", -1)
                    service.tapElement(idx)
                    actionsTaken.add("tapped element $idx")
                }
                "type" -> {
                    val idx = json.optInt("index", -1)
                    val text = json.optString("text")
                    service.typeIntoElement(idx, text)
                    actionsTaken.add("typed \"$text\" into element $idx")
                }
                "scroll" -> {
                    service.scrollDown()
                    actionsTaken.add("scrolled")
                }
                "back" -> {
                    service.pressBack()
                    actionsTaken.add("pressed back")
                }
                "done" -> {
                    return json.optString("summary", "Done.")
                }
                else -> return "I wasn't sure how to continue, so I stopped."
            }

            delay(1200)
        }

        return "I tried several steps but couldn't finish that fully - want me to keep going?"
    }
}
