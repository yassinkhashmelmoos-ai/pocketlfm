package com.thunder.agi.agent

/**
 * EmotionDetector — detects emotional tone from text.
 * Text-based emotion detection is actually more accurate than face detection
 * for a chat app since it works on the actual words the user types.
 *
 * Uses lexicon-based approach (no model needed) with Arabic + English support.
 * Result is injected into system prompt to make responses more empathetic.
 */
object EmotionDetector {

    enum class Emotion(val label: String, val emoji: String, val responseHint: String) {
        JOY("happy", "😊", "The user seems happy. Match their positive energy."),
        SADNESS("sad", "😢", "The user seems sad or down. Be warm, empathetic and supportive."),
        ANGER("frustrated", "😤", "The user seems frustrated. Be calm, understanding and helpful."),
        FEAR("anxious", "😰", "The user seems worried or anxious. Be reassuring and clear."),
        SURPRISE("surprised", "😲", "The user seems surprised. Acknowledge it and explain clearly."),
        CURIOSITY("curious", "🤔", "The user is curious and eager to learn. Be thorough and engaging."),
        GRATITUDE("grateful", "🙏", "The user is expressing gratitude. Acknowledge it warmly."),
        NEUTRAL("neutral", "😐", "")
    }

    data class EmotionResult(
        val emotion: Emotion,
        val confidence: Float,
        val hint: String
    )

    private val lexicon = mapOf(
        // Joy / happiness
        Emotion.JOY to listOf(
            "happy", "great", "awesome", "excellent", "wonderful", "amazing", "love",
            "fantastic", "good", "nice", "brilliant", "perfect", "thank", "thanks",
            "yay", "wow", "cool", "glad", "excited", "joy", "pleased", "delighted",
            // Arabic
            "سعيد", "رائع", "ممتاز", "جميل", "شكرا", "عظيم", "مبهج", "فرحان"
        ),
        // Sadness
        Emotion.SADNESS to listOf(
            "sad", "unhappy", "depressed", "miserable", "crying", "cry", "tears",
            "lonely", "alone", "hopeless", "terrible", "awful", "hurt", "pain",
            "suffering", "broken", "lost", "miss", "grief", "sorrow",
            // Arabic
            "حزين", "مكتئب", "وحيد", "أبكي", "ألم", "معاناة", "مفقود"
        ),
        // Anger
        Emotion.ANGER to listOf(
            "angry", "mad", "furious", "hate", "stupid", "idiot", "useless",
            "terrible", "worst", "awful", "annoying", "frustrated", "fed up",
            "ridiculous", "nonsense", "garbage", "trash", "fix this", "broken",
            // Arabic
            "غاضب", "أكره", "فاشل", "محبط", "سخيف", "مزعج"
        ),
        // Fear / anxiety
        Emotion.FEAR to listOf(
            "scared", "afraid", "worried", "anxious", "nervous", "fear", "terrified",
            "panic", "stress", "stressed", "overwhelmed", "help", "emergency", "urgent",
            // Arabic
            "خائف", "قلق", "توتر", "مرعوب", "ضغط", "مساعدة", "طارئ"
        ),
        // Curiosity
        Emotion.CURIOSITY to listOf(
            "how", "why", "what", "when", "where", "explain", "tell me", "curious",
            "wonder", "interesting", "learn", "understand", "know more", "question",
            // Arabic
            "كيف", "لماذا", "ماذا", "متى", "أين", "اشرح", "فضولي", "أريد أن أعرف"
        ),
        // Gratitude
        Emotion.GRATITUDE to listOf(
            "thank you", "thanks", "grateful", "appreciate", "helpful", "amazing help",
            "great help", "you're the best", "perfect answer",
            // Arabic
            "شكرا جزيلا", "ممنون", "أقدر", "مفيد جدا"
        ),
        // Surprise
        Emotion.SURPRISE to listOf(
            "wow", "really", "seriously", "no way", "can't believe", "surprising",
            "unexpected", "what!", "omg", "oh my",
            // Arabic
            "لا يصدق", "بجد", "مستحيل", "يا إلهي"
        )
    )

    fun detect(text: String): EmotionResult {
        val lower = text.lowercase()
        val scores = mutableMapOf<Emotion, Int>()

        lexicon.forEach { (emotion, words) ->
            var score = 0
            words.forEach { word ->
                if (lower.contains(word)) score++
            }
            if (score > 0) scores[emotion] = score
        }

        if (scores.isEmpty()) return EmotionResult(Emotion.NEUTRAL, 1.0f, "")

        val topEmotion = scores.maxByOrNull { it.value }!!.key
        val totalSignals = scores.values.sum().toFloat()
        val confidence = (scores[topEmotion] ?: 0) / totalSignals.coerceAtLeast(1f)

        return EmotionResult(
            emotion = topEmotion,
            confidence = confidence,
            hint = if (confidence > 0.3f) topEmotion.responseHint else ""
        )
    }

    /** Returns a short hint to inject into system prompt */
    fun getSystemHint(text: String): String {
        val result = detect(text)
        return if (result.hint.isNotBlank()) "\n[Emotional context: ${result.hint}]" else ""
    }
}
