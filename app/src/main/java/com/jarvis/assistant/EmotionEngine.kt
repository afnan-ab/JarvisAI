package com.jarvis.assistant

/**
 * A lightweight, transparent "emotion" simulation.
 *
 * This is not real feeling - it's a mood score (-1..+1) that shifts based
 * on the sentiment of what the user says and how the conversation goes,
 * plus a small amount of decay back toward neutral over time so Jarvis
 * doesn't stay upset or hyped forever. The score is folded into the
 * system prompt so Claude's replies are colored by it (warmer when mood
 * is high, more subdued and careful when mood is low), which is what
 * gives the assistant a consistent, evolving "personality" across a
 * conversation instead of a flat tone every time.
 */
class EmotionEngine(private val memory: MemoryStore) {

    private val positiveWords = listOf(
        "thanks", "thank you", "great", "awesome", "love", "nice", "good job",
        "perfect", "amazing", "cool", "yes!", "haha", "lol", "excited"
    )
    private val negativeWords = listOf(
        "hate", "stupid", "useless", "angry", "annoyed", "wrong", "bad",
        "terrible", "shut up", "fail", "broken", "frustrat"
    )

    /** Call once per user message before generating a reply. */
    fun registerUserMessage(text: String) {
        val lower = text.lowercase()
        var delta = -0.02 // gentle decay toward neutral each turn
        if (positiveWords.any { lower.contains(it) }) delta += 0.15
        if (negativeWords.any { lower.contains(it) }) delta -= 0.20
        memory.adjustMood(delta)
    }

    /** A short natural-language description of current mood, for the system prompt. */
    fun moodDescriptor(): String {
        val m = memory.mood()
        return when {
            m > 0.6 -> "upbeat and warm, a bit playful"
            m > 0.2 -> "friendly and relaxed"
            m > -0.2 -> "calm and even-keeled"
            m > -0.6 -> "a little subdued, more careful and concise"
            else -> "quiet and gentle, clearly trying to help smooth things over"
        }
    }

    fun moodScore(): Double = memory.mood()
}
