package com.thunder.agi.agent

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

private const val WILL_TAG = "ThunderAGI_Will"

/**
 * AgentLoop: the tool-use "will" of Meta's Muse Spark 1.0, adapted to an on-device model.
 *
 * SOURCE. There is no published Spark source code. What exists is the system prompt
 * captured on launch day (8 Apr 2026; public gist praveen-palanisamy/ec25c7fbec40e2abaab98fa47de429f9),
 * and Simon Willison's launch-day write-up of the tool harness, which agrees with it on the
 * core tools. This file re-implements the BEHAVIOUR those instructions describe. It is not a port.
 *
 * TAKEN FROM THE 1.0 PROMPT (sections "Search", "Python execution", "User Context"):
 *  1. The model, not a keyword list, decides per message whether a lookup helps and which
 *     one. Common knowledge, small talk, creative writing, translation and code get none.
 *  2. Independent lookups are requested together and run in parallel.
 *  3. Act immediately and never announce it. If any part of a question needs a lookup,
 *     finish the lookups before answering (no partial answers).
 *  4. Queries carry no dates/years unless the year identifies the thing; today's date is
 *     supplied on every turn (dateContext()).
 *  5. Every numeric result goes through the calculator: the model's own arithmetic is
 *     unreliable (the prompt's own example is 9.8 - 9.11, which is 0.69).
 *  6. After a lookup the answer leads with the finding instead of listing sources, and if a
 *     lookup failed the answer says so honestly (LoopResult.note).
 *
 * MINE, NOT SPARK'S. Round 2 (deciding again after a failed lookup) is a standard agent
 * pattern; the prompt only says to try key terms via search when a resource is unreachable.
 * The one-line tool format replaces Spark's XML function-call blocks; it was chosen with a
 * small model in mind and has not been benchmarked against the XML form.
 *
 * NOT PORTED ON PURPOSE. The 1.0 prompt says the assistant exists only within the current
 * response and cannot act afterwards, so there is no background behaviour in 1.0 to copy.
 * Media-tool rules (follow-ups reuse the same tool, never fabricate ids, never pre-refuse)
 * belong to the image path, not to this text-tool loop.
 */

/** Runs one tool call. The default wraps AgentToolkit; tests substitute a fake. */
interface ToolExecutor {
    suspend fun execute(call: ToolCall): AgentToolkit.ToolResult
    /** Keyword fallback; also detects pure arithmetic (local, no model needed). */
    fun fallbackCalls(message: String, webEnabled: Boolean): List<ToolCall>
}

class ToolkitExecutor(private val toolkit: AgentToolkit) : ToolExecutor {
    override suspend fun execute(call: ToolCall) = toolkit.execute(call.tool, call.arg)
    override fun fallbackCalls(message: String, webEnabled: Boolean) =
        toolkit.heuristicCalls(message, webEnabled)
}

class AgentLoop(private val executor: ToolExecutor) {

    constructor(toolkit: AgentToolkit) : this(ToolkitExecutor(toolkit))

    data class LoopResult(
        /** "[Tool Results ...]" block to prepend to the user turn; "" when nothing succeeded. */
        val toolContext: String,
        /** One short instruction for the SYSTEM prompt; "" when no tool was involved. */
        val note: String,
        /** Every call that actually ran, in order. */
        val calls: List<ToolCall>,
        /** How many times the model was asked to decide (0 = no model call was needed). */
        val decisions: Int
    ) {
        companion object { val NONE = LoopResult("", "", emptyList(), 0) }
    }

    internal sealed class Decision {
        data class Calls(val calls: List<ToolCall>) : Decision()
        object NoTools : Decision()     // the model looked and said none are needed
        object Unusable : Decision()    // model error, exception, or output we cannot read
    }

    /**
     * @param recentContext last few turns as plain text, so "and tomorrow?" can be resolved
     * @param generateFn    a one-off model call (prompt, maxTokens, temperature) -> text
     */
    suspend fun run(
        userMessage: String,
        recentContext: String,
        webEnabled: Boolean,
        generateFn: suspend (prompt: String, maxTokens: Int, temperature: Float) -> String,
        onStep: suspend (String) -> Unit = {},
        now: Date = Date()
    ): LoopResult {
        val msg = userMessage.trim()
        if (isTrivial(msg)) return LoopResult.NONE

        val log  = ArrayList<Pair<ToolCall, AgentToolkit.ToolResult>>()
        val seen = HashSet<String>()

        // 1) Pure arithmetic is local and deterministic: no model call, works with web off.
        val arithmetic = executor.fallbackCalls(msg, webEnabled = false)
        if (arithmetic.isNotEmpty()) {
            runBatch(arithmetic, seen, log, onStep)
            if (log.any { it.second.success }) return finish(log, decisions = 0)
            log.clear(); seen.clear()           // unparseable: carry on as a normal message
        }

        // 2) Without web access there is nothing else the loop may call.
        if (!webEnabled) return LoopResult.NONE

        // 3) The model decides. If it answers "none", that decision stands. Keywords are used
        //    only when its output is unusable (an error string, an exception, garbage).
        onStep(STEP_PLAN)
        var decisions = 0
        var modelUsable = true
        var decision = decide(msg, recentContext, log, generateFn, now); decisions++
        var pending: List<ToolCall> = when (decision) {
            is Decision.Calls -> decision.calls
            Decision.NoTools  -> emptyList()
            Decision.Unusable -> { modelUsable = false; executor.fallbackCalls(msg, webEnabled = true) }
        }

        var rounds = 0
        while (pending.isNotEmpty()) {
            val ran = runBatch(pending, seen, log, onStep)
            rounds++
            if (ran == 0 || rounds >= MAX_ROUNDS || !modelUsable || log.size >= MAX_CALLS) break
            // Only re-decide when something in this round failed.
            if (log.takeLast(ran).all { it.second.success }) break
            onStep(STEP_REFINE)
            decision = decide(msg, recentContext, log, generateFn, now); decisions++
            pending = (decision as? Decision.Calls)?.calls ?: emptyList()
        }
        return finish(log, decisions)
    }

    // ── execution ────────────────────────────────────────────────────────────

    private suspend fun runBatch(
        wanted: List<ToolCall>,
        seen: MutableSet<String>,
        log: MutableList<Pair<ToolCall, AgentToolkit.ToolResult>>,
        onStep: suspend (String) -> Unit
    ): Int {
        val room  = (MAX_CALLS - log.size).coerceAtLeast(0)
        val fresh = wanted.filter { seen.add(it.key()) }.take(room)     // never repeat a call
        if (fresh.isEmpty()) return 0
        onStep(STEP_ACT)
        val results =
            if (fresh.size == 1) listOf(safeExecute(fresh[0]))
            else coroutineScope { fresh.map { c -> async { safeExecute(c) } }.map { it.await() } }  // parallel
        fresh.zip(results).forEach { log.add(it) }
        return fresh.size
    }

    private suspend fun safeExecute(call: ToolCall): AgentToolkit.ToolResult =
        try {
            executor.execute(call)
        } catch (e: CancellationException) {
            throw e                                  // never swallow cancellation
        } catch (e: Exception) {
            Log.w(WILL_TAG, "${call.tool} threw: ${e.message}")
            AgentToolkit.ToolResult(call.tool, call.arg, "Tool error.", false)
        }

    private fun finish(log: List<Pair<ToolCall, AgentToolkit.ToolResult>>, decisions: Int): LoopResult {
        if (log.isEmpty()) return LoopResult("", "", emptyList(), decisions)
        val calls = log.map { it.first }
        val ok    = log.filter { it.second.success }
        if (ok.isEmpty()) return LoopResult("", NOTE_FAILED, calls, decisions)
        val block = buildString {
            append("[Tool Results — use this information to answer accurately]\n")
            ok.forEach { (_, r) -> append(r.toolName).append(": ").append(r.result.take(600)).append('\n') }
            append("[End Tool Results]\n\n")
        }
        return LoopResult(block, NOTE_USE_RESULTS, calls, decisions)
    }

    // ── the decision step (the "will") ───────────────────────────────────────

    private suspend fun decide(
        msg: String,
        recent: String,
        log: List<Pair<ToolCall, AgentToolkit.ToolResult>>,
        generateFn: suspend (String, Int, Float) -> String,
        now: Date
    ): Decision {
        val raw = try {
            generateFn(buildDecisionPrompt(msg, recent, log, now), DECISION_MAX_TOKENS, DECISION_TEMPERATURE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(WILL_TAG, "decision call threw: ${e.message}")
            return Decision.Unusable
        }
        // ThunderModel.generate() does not throw on failure; it RETURNS "Error: ...".
        if (raw.trimStart().startsWith("Error:")) {
            Log.w(WILL_TAG, "decision call returned an error string: ${raw.take(80)}")
            return Decision.Unusable
        }
        val d = parseDecision(raw)
        Log.i(WILL_TAG, "decision=$d raw=${raw.take(140).replace('\n', '|')}")
        return d
    }

    internal fun buildDecisionPrompt(
        msg: String,
        recent: String,
        log: List<Pair<ToolCall, AgentToolkit.ToolResult>>,
        now: Date
    ): String = buildString {
        append("<|im_start|>system\n").append(DECISION_RULES)
        append("\nToday is ").append(dateLine(now)).append(". The date and time are always known; never look them up.\n")
        append("<|im_end|>\n<|im_start|>user\n")
        if (recent.isNotBlank()) append("Conversation so far:\n").append(recent.take(700)).append("\n\n")
        append("Message: ").append(msg.take(300)).append('\n')
        if (log.isNotEmpty()) {
            append("\nLookups already run:\n")
            log.forEach { (c, r) ->
                append("- ").append(c.tool).append(": ").append(c.arg).append(" -> ")
                append(if (r.success) r.result.take(120) else "FAILED").append('\n')
            }
            append("A failed lookup may deserve one different attempt (another tool, or a rephrased query). ")
            append("Never repeat a call. If nothing more is needed, reply none.\n")
        }
        append("<|im_end|>\n<|im_start|>assistant\n")
    }

    /** Lenient on purpose (Spark's own tool calls are regex-parsed, not strict XML). */
    internal fun parseDecision(raw: String): Decision {
        val calls = ArrayList<ToolCall>()
        var saidNone = false
        for (line in raw.lineSequence()) {
            val t = line.trim().trimStart('-', '*', '•', '`', ' ').trimEnd('`', ' ')
            if (t.isEmpty()) continue
            if (NONE_LINE.matches(t)) { saidNone = true; continue }
            val m = LINE_COLON.matchEntire(t) ?: LINE_PAREN.matchEntire(t) ?: continue
            val tool = canonicalTool(m.groupValues[1]) ?: continue
            val arg  = cleanArg(m.groupValues[2])
            if (arg.isNotEmpty()) calls.add(ToolCall(tool, arg))
            if (calls.size >= MAX_CALLS_PER_ROUND) break
        }
        return when {
            calls.isNotEmpty() -> Decision.Calls(calls)
            saidNone           -> Decision.NoTools
            else               -> Decision.Unusable
        }
    }

    private fun canonicalTool(name: String): String? = when (name.lowercase()) {
        "web_search", "websearch", "web", "search", "google", "browser_search" -> AgentToolkit.WEB_SEARCH
        "wikipedia", "wiki"                                                    -> AgentToolkit.WIKIPEDIA
        "weather", "forecast"                                                  -> AgentToolkit.WEATHER
        "currency", "exchange", "rates", "fx"                                  -> AgentToolkit.CURRENCY
        "dictionary", "define", "definition"                                   -> AgentToolkit.DICTIONARY
        "math", "calc", "calculator", "calculate"                              -> AgentToolkit.MATH
        else                                                                   -> null
    }

    private fun cleanArg(raw: String): String =
        raw.trim().trim('"', '\'', '`')
            .replace(ARG_LABEL, "")                 // web_search: query="..." -> ...
            .trim().trim('"', '\'', '`')
            .replace(Regex("\\s+"), " ")
            .take(MAX_ARG_CHARS)

    /** Greetings and fillers never need a lookup (the prompt says so explicitly). */
    internal fun isTrivial(msg: String): Boolean = msg.length < 3 || TRIVIAL.matches(msg)

    companion object {
        const val MAX_ROUNDS = 2
        const val MAX_CALLS  = 4
        private const val MAX_CALLS_PER_ROUND  = 3
        private const val MAX_ARG_CHARS        = 120
        private const val DECISION_MAX_TOKENS  = 64
        private const val DECISION_TEMPERATURE = 0.2f

        const val STEP_PLAN   = "🧠 تخطيط…  Planning…"
        const val STEP_ACT    = "🔍 بحث…  Searching…"
        const val STEP_REFINE = "🔁 تحسين…  Refining…"

        private const val NOTE_USE_RESULTS =
            "\n\nThis message comes with tool results. Answer directly from them and lead with the key finding. " +
            "Do not list sources or mention the tools."
        private const val NOTE_FAILED =
            "\n\nA live lookup for this message failed. Answer from what you know and say plainly that you " +
            "could not verify current details."

        private val NONE_LINE  = Regex("^(none|no tools?|no tool needed|nothing|done|n/a)[.!]*$", RegexOption.IGNORE_CASE)
        private val LINE_COLON = Regex("^([A-Za-z_]+)\\s*[:=]\\s*(.+)$")
        private val LINE_PAREN = Regex("^([A-Za-z_]+)\\s*\\(\\s*(.+?)\\s*\\)$")
        private val ARG_LABEL  = Regex("^(query|q|city|location|word|expression|topic|codes?)\\s*=\\s*", RegexOption.IGNORE_CASE)
        private val TRIVIAL = Regex(
            "^(hi|hello|hey|yo|thanks|thank you|thx|ok|okay|yes|no|good (morning|evening|night)|" +
            "مرحبا|اهلا|أهلا|السلام عليكم|شكرا|شكراً|تمام|حسنا|نعم|لا)[\\s.!?؟،]*$", RegexOption.IGNORE_CASE)

        /** Appended to the SYSTEM prompt every turn (Spark's "User Context" block). */
        fun dateContext(now: Date = Date()): String {
            val cal  = Calendar.getInstance().apply { time = now }
            val part = when (cal.get(Calendar.HOUR_OF_DAY)) {
                in 5..11  -> "morning"
                in 12..16 -> "afternoon"
                in 17..20 -> "evening"
                else      -> "night"
            }
            val clock = SimpleDateFormat("HH:mm", Locale.ENGLISH).format(now)
            return "\n\nThe current date is ${dateLine(now)}, local time $clock ($part)."
        }

        internal fun dateLine(now: Date): String =
            SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.ENGLISH).format(now)

        private val DECISION_RULES = """
You are the tool-routing step of an assistant. Read the message and reply with ONLY tool lines, one per line, written as "tool: argument". If no tool is needed, reply with the single word none.

Tools:
web_search: <short query> - news, sports, prices, anything recent or that may have changed, and facts you are unsure of
wikipedia: <topic> - background facts about a person, place, thing or event
weather: <city> - current weather
currency: <codes> - exchange rates, for example USD EGP
dictionary: <word> - the meaning of an English word
math: <expression> - ANY calculation, even a simple one. Use only digits and + - * / ^ ( ) . because your own arithmetic is unreliable

Rules:
- Use a tool only when it makes the answer more accurate or more current. Greetings, small talk, opinions, creative writing, editing, translation, code, hypotheticals and well-known common knowledge need none: reply none.
- If any part of the message needs a lookup, request it now. Never explain and never announce.
- Independent lookups go on separate lines.
- Make every argument self-contained. Use the conversation to resolve words like "it" or "there".
- Do not put dates or years in a query unless the year identifies the thing, such as a model year.

Examples:
Message: weather in Cairo and the dollar rate in Egyptian pounds
weather: Cairo
currency: USD EGP

Message: who was Ibn Battuta
wikipedia: Ibn Battuta

Message: what is 15 percent of 240
math: 0.15*240

Message: write me a short poem about rain
none
""".trimIndent()
    }
}
