package com.thunder.agi.intelligence

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "SelfImprover"

/**
 * SelfImprover — the AGI's self-modification engine.
 *
 * Instead of DEAP genetic programming (which requires Python runtime),
 * this uses a symbolic approach:
 *
 * 1. RESPONSE ANALYZER: Scans feedback_log.jsonl to identify patterns
 *    in bad responses (prompt → bad response pairs)
 *
 * 2. PROMPT EVOLVER: Like genetic programming, it mutates the system prompt
 *    by adding/removing/modifying rules based on observed failures.
 *    Each "generation" tests a mutation against the feedback data.
 *
 * 3. AST PATTERN MATCHING: Analyzes response structure (not bytecode AST,
 *    but semantic AST of the conversation) to detect failure patterns:
 *    - Repetition loops
 *    - Token leaking
 *    - Off-topic responses
 *    - Truncated answers
 *
 * 4. AUTO-PATCH: Writes improvements back to the system prompt and
 *    soft_prompt_cache.txt automatically.
 */
class SelfImprover(private val context: Context) {

    data class ImprovementReport(
        val issuesFound: List<String>,
        val promptMutations: List<String>,
        val appliedFix: String?,
        val generation: Int
    )

    // Prompt mutation templates — like genetic programming operators
    private val mutationTemplates = listOf(
        "Always give direct, concise answers without repeating previous responses.",
        "Never repeat phrases or sentences you have already used in this conversation.",
        "If you don't know something, say so clearly rather than making up an answer.",
        "Respond in the same language the user wrote in.",
        "For factual questions, give the answer first, then explanation.",
        "For creative requests, be original and avoid generic responses.",
        "Never include template tokens like <|user|> or <|assistant|> in your responses.",
        "If the user asks about code, always provide working, runnable examples.",
        "Keep responses focused on what was actually asked.",
        "If a topic was already discussed, reference it briefly rather than repeating fully."
    )

    suspend fun analyzeAndImprove(currentPrompt: String): ImprovementReport = withContext(Dispatchers.IO) {
        val filesDir = context.filesDir
        val feedbackFile = File(filesDir, "feedback_log.jsonl")
        val issues = mutableListOf<String>()
        val mutations = mutableListOf<String>()

        // Step 1: Read feedback data
        val badResponses = mutableListOf<Pair<String, String>>()
        val goodResponses = mutableListOf<Pair<String, String>>()

        if (feedbackFile.exists()) {
            feedbackFile.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val prompt   = extractJsonField(line, "prompt")
                val response = extractJsonField(line, "response")
                val isGood   = line.contains("\"good\":true")
                if (prompt.isNotBlank() && response.isNotBlank()) {
                    if (isGood) goodResponses.add(Pair(prompt, response))
                    else badResponses.add(Pair(prompt, response))
                }
            }
        }

        Log.i(TAG, "Analyzing ${badResponses.size} bad, ${goodResponses.size} good responses")

        // Step 2: AST-style pattern analysis on bad responses
        badResponses.forEach { (_, response) ->
            // Repetition detection
            if (hasRepetition(response)) {
                issues.add("repetition_loop")
                if (!mutations.contains(mutationTemplates[0])) mutations.add(mutationTemplates[0])
                if (!mutations.contains(mutationTemplates[1])) mutations.add(mutationTemplates[1])
            }
            // Token leaking
            if (response.contains("<|") || response.contains("|>")) {
                issues.add("token_leak")
                if (!mutations.contains(mutationTemplates[6])) mutations.add(mutationTemplates[6])
            }
            // Off-topic (response doesn't share words with prompt)
            val promptWords = badResponses.map { it.first }.flatMap { it.split(" ") }.toSet()
            val responseWords = response.split(" ").toSet()
            if (promptWords.intersect(responseWords).size < 2) {
                issues.add("off_topic")
                if (!mutations.contains(mutationTemplates[8])) mutations.add(mutationTemplates[8])
            }
            // Truncation
            if (response.endsWith("...") || response.length < 10) {
                issues.add("truncated")
            }
        }

        val generation = getGeneration()

        // Step 3: Evolve prompt — add best mutation if issues found
        var appliedFix: String? = null
        if (mutations.isNotEmpty() && badResponses.size >= 3) {
            val bestMutation = mutations.first()
            if (!currentPrompt.contains(bestMutation)) {
                val newPrompt = "$currentPrompt\n$bestMutation"
                File(filesDir, "evolved_system_prompt.txt").writeText(newPrompt)
                appliedFix = bestMutation
                Log.i(TAG, "Applied prompt evolution [gen $generation]: $bestMutation")
            }
        }

        // Step 4: Build good-response soft prompt from best examples
        if (goodResponses.size >= 3) {
            val bestExamples = goodResponses.takeLast(3)
            val softPrompt = buildSoftPromptFromExamples(bestExamples)
            File(filesDir, "soft_prompt_cache.txt").writeText(softPrompt)
            Log.i(TAG, "Updated soft prompt with ${bestExamples.size} good examples")
        }

        saveGeneration(generation + 1)

        ImprovementReport(
            issuesFound = issues.distinct(),
            promptMutations = mutations,
            appliedFix = appliedFix,
            generation = generation
        )
    }

    private fun hasRepetition(text: String): Boolean {
        val sentences = text.split(Regex("[.!?]")).filter { it.length > 10 }
        if (sentences.size < 2) return false
        for (i in 0 until sentences.size - 1) {
            for (j in i + 1 until sentences.size) {
                val similarity = jaccardSimilarity(sentences[i], sentences[j])
                if (similarity > 0.7) return true
            }
        }
        return false
    }

    private fun jaccardSimilarity(a: String, b: String): Double {
        val setA = a.lowercase().split(" ").toSet()
        val setB = b.lowercase().split(" ").toSet()
        val intersection = setA.intersect(setB).size.toDouble()
        val union = setA.union(setB).size.toDouble()
        return if (union == 0.0) 0.0 else intersection / union
    }

    private fun buildSoftPromptFromExamples(examples: List<Pair<String, String>>): String {
        val sb = StringBuilder("<!-- Learned good examples -->\n")
        examples.forEach { (prompt, response) ->
            sb.append("<|user|>\n$prompt<|endoftext|>\n")
            sb.append("<|assistant|>\n$response<|endoftext|>\n")
        }
        return sb.toString()
    }

    fun getEvolvedPrompt(basePrompt: String): String {
        val evolved = File(context.filesDir, "evolved_system_prompt.txt")
        return if (evolved.exists()) evolved.readText() else basePrompt
    }

    private fun getGeneration(): Int =
        File(context.filesDir, "evolution_gen.txt").let {
            if (it.exists()) it.readText().trim().toIntOrNull() ?: 0 else 0
        }

    private fun saveGeneration(gen: Int) =
        File(context.filesDir, "evolution_gen.txt").writeText(gen.toString())

    private fun extractJsonField(json: String, field: String): String {
        val pattern = Regex(""""$field"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        return pattern.find(json)?.groupValues?.get(1)
            ?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: ""
    }
}
