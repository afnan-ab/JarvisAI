package com.jarvis.assistant

import kotlinx.coroutines.delay
import org.json.JSONObject

class AgentRunner(
    private val gemini: GroqApiClient,
    private val commands: CommandProcessor
) {
    suspend fun run(instruction: String, maxSteps: Int = 15): String {
        val service = AssistantAccessibilityService.instance
            ?: return "I need the Accessibility permission turned on first - go to Settings > Accessibility > Jarvis and enable it, then try again."

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

                Reply with ONLY one JSON object, nothing else, no markdown fences, no explanation. Pick one of:
                {"action":"open_app","app":"<app name>"}
                {"action":"tap","index":<number>}
                {"action":"type","index":<number>,"text":"<text to type>"}
                {"action":"scroll"}
                {"action":"back"}
                {"action":"done","summary":"<short summary of what was accomplished, spoken to the user>"}

                If there is no visible text search box on screen yet, look for a search icon or button (often labeled "desc: Search" or a magnifying glass) in a bottom or top navigation bar, and tap that first to navigate to the search screen before trying to type.
                Elements list search fields by hint text (e.g. "hint: Search") or id (e.g. "id: search_src_text") when they have no visible label - use those to identify a search box. If the element you need is not in the list, try "scroll" first before giving up.
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
