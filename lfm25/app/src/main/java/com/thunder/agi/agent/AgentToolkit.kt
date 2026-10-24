package com.thunder.agi.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder

private const val TAG     = "ThunderAGI_Agent"
private const val TIMEOUT = 6000

/** One tool invocation: which tool, and the argument to give it. */
data class ToolCall(val tool: String, val arg: String) {
    /** Identity used to stop the loop repeating a call it already made. */
    internal fun key(): String = tool + "|" + arg.trim().lowercase()
}

class AgentToolkit(private val context: Context) {

    data class ToolResult(
        val toolName: String,
        val query:    String,
        val result:   String,
        val success:  Boolean
    )

    companion object {
        const val WEB_SEARCH = "web_search"
        const val WIKIPEDIA  = "wikipedia"
        const val WEATHER    = "weather"
        const val CURRENCY   = "currency"
        const val DICTIONARY = "dictionary"
        const val MATH       = "math"
    }

    /**
     * Run ONE tool by name with an argument the caller already extracted.
     * AgentLoop uses this: the model decides the tool AND its argument, so none of
     * the keyword regexes in this file are involved.
     */
    suspend fun execute(tool: String, arg: String): ToolResult = when (tool) {
        WEB_SEARCH -> webSearchTool(arg)
        WIKIPEDIA  -> getWikipedia(arg)
        WEATHER    -> getWeather(arg.ifBlank { "Khartoum" })
        CURRENCY   -> getCurrency(arg)
        DICTIONARY -> getDictionary(arg.trim().split(Regex("\\s+")).first().trim('"', '\'', '.', ',', '?'))
        MATH       -> evalMath(arg) ?: ToolResult("Calculator", arg, "Not a valid expression.", false)
        else       -> ToolResult(tool, arg, "Unknown tool.", false)
    }

    /**
     * Keyword fallback with the same triggers gatherContext() always had. AgentLoop
     * uses it only when the model's own decision is unusable. Pure arithmetic is
     * answered here as well, since it is local and needs no model call at all.
     * With web off, only the calculator is offered.
     */
    fun heuristicCalls(message: String, webEnabled: Boolean): List<ToolCall> {
        val calls = mutableListOf<ToolCall>()
        pureMathExpression(message)?.let { calls += ToolCall(MATH, it) }
        if (!webEnabled) return calls

        val lower = message.lowercase()
        if (lower.contains(Regex("\\b(weather|temperature|rain|forecast|humidity|wind|climate)\\b")))
            calls += ToolCall(WEATHER, extractLocation(message))
        if (lower.contains(Regex("what is|who is|who was|what was|explain|define|tell me about|history of|when did|where is"))) {
            val subject = extractSubject(message)
            if (subject.isNotBlank()) calls += ToolCall(WIKIPEDIA, subject)
        }
        if (lower.contains(Regex("meaning of|definition of|what does .* mean|define "))) {
            val word = extractWordToDefine(message)
            if (word.isNotBlank()) calls += ToolCall(DICTIONARY, word)
        }
        if (lower.contains(Regex("price of|exchange rate|how much is .*(dollar|euro|btc|bitcoin|usd|gbp|sar|egp)")))
            calls += ToolCall(CURRENCY, message.take(120))
        if (lower.contains(Regex("search|find|look up|latest news|recent|today's|current events")))
            calls += ToolCall(WEB_SEARCH, message.take(120))
        return calls
    }

    /** Legacy keyword pipeline. ChatViewModel now goes through AgentLoop; kept as-is. */
    suspend fun gatherContext(userMessage: String, webEnabled: Boolean): String {
        val tools = mutableListOf<ToolResult>()
        val lower = userMessage.lowercase()

        // Local tools — always fast, zero network cost
        checkDateTime(lower)?.let { tools.add(it) }
        checkMath(userMessage)?.let { tools.add(it) }

        if (webEnabled) {
            if (lower.contains(Regex("weather|temperature|rain|forecast|humidity|wind|climate")))
                tools.add(getWeather(extractLocation(userMessage)))

            if (lower.contains(Regex("what is|who is|who was|what was|explain|define|tell me about|history of|when did|where is"))) {
                val subject = extractSubject(userMessage)
                if (subject.isNotBlank()) tools.add(getWikipedia(subject))
            }

            if (lower.contains(Regex("meaning of|definition of|what does .* mean|define "))) {
                val word = extractWordToDefine(userMessage)
                if (word.isNotBlank()) tools.add(getDictionary(word))
            }

            if (lower.contains(Regex("price of|exchange rate|how much is .*(dollar|euro|btc|bitcoin|usd|gbp|sar|egp)")))
                tools.add(getCurrency(userMessage))

            // FIX: web search fallback ONLY for explicit search intent.
            // Old code called webSearch() for EVERY web-enabled message,
            // which produced empty [Tool Results] blocks that the model
            // echoed verbatim as "[End Tool Results]" instead of responding.
            if (lower.contains(Regex("search|find|look up|latest news|recent|today's|current events")))
                tools.add(webSearchTool(userMessage))
        }

        // FIX: only build the block when there are SUCCESSFUL results.
        // An empty block was the primary cause of the model output corruption.
        val successful = tools.filter { it.success }
        if (successful.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("[Tool Results — use this information to answer accurately]\n")
        successful.forEach { t -> sb.append("${t.toolName}: ${t.result}\n") }
        sb.append("[End Tool Results]\n\n")
        return sb.toString()
    }

    private suspend fun webSearchTool(query: String): ToolResult = withContext(Dispatchers.IO) {
        try {
            val json   = JSONObject(httpGet("https://api.duckduckgo.com/?q=${enc(query)}&format=json&no_html=1&skip_disambig=1"))
            val answer = json.optString("Answer",       "")
            val abstr  = json.optString("AbstractText", "")
            val result = answer.ifBlank { abstr }
            if (result.isNotBlank()) return@withContext ToolResult("Web Search", query, result.take(600), true)
        } catch (e: Exception) {
            Log.w(TAG, "Web search: ${e.message}")
        }
        // DuckDuckGo's instant-answer API only answers entity-style questions and comes
        // back empty for most natural-language queries. Fall back to an encyclopedia
        // search so the agent gets *something* real instead of "No result."
        val wiki = getWikipedia(query)
        if (wiki.success) ToolResult("Web Search", query, wiki.result, true)
        else ToolResult("Web Search", query, "No result.", false)
    }

    private suspend fun getWikipedia(subject: String): ToolResult = withContext(Dispatchers.IO) {
        val topic = subject.trim()
        val lang  = if (topic.any { it in '\u0600'..'\u06FF' }) "ar" else "en"
        // 1) exact title  2) otherwise let Wikipedia's own search pick the best article
        var extract = wikiSummary(lang, topic)
        if (extract.isBlank()) {
            val title = wikiSearchTitle(lang, topic)
            if (title.isNotBlank()) extract = wikiSummary(lang, title)
        }
        ToolResult("Wikipedia", subject, extract.take(500).ifBlank { "Not found." }, extract.isNotBlank())
    }

    // URLEncoder turns spaces into '+', which is only valid in a query string. In a URL
    // *path* (the REST summary endpoint) that '+' is literal, so "Albert Einstein" was
    // looked up as "Albert+Einstein" and missed. Use %20.
    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection().apply {
            connectTimeout = TIMEOUT; readTimeout = TIMEOUT
            setRequestProperty("User-Agent", "ThunderAGI/1.0")
        }
        return conn.getInputStream().bufferedReader().readText()
    }

    private fun wikiSummary(lang: String, title: String): String = try {
        JSONObject(httpGet("https://$lang.wikipedia.org/api/rest_v1/page/summary/${enc(title.replace(' ', '_'))}"))
            .optString("extract", "")
    } catch (e: Exception) { "" }

    private fun wikiSearchTitle(lang: String, query: String): String = try {
        val hits = JSONObject(httpGet(
            "https://$lang.wikipedia.org/w/api.php?action=query&list=search&srsearch=${enc(query)}&srlimit=1&format=json&utf8=1"))
            .getJSONObject("query").getJSONArray("search")
        if (hits.length() > 0) hits.getJSONObject(0).optString("title", "") else ""
    } catch (e: Exception) { "" }

    private suspend fun getWeather(location: String): ToolResult = withContext(Dispatchers.IO) {
        try {
            val geoUrl  = URL("https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(location,"UTF-8")}&count=1")
            val geoJson = JSONObject(geoUrl.openConnection().apply { connectTimeout = TIMEOUT; readTimeout = TIMEOUT }
                .getInputStream().bufferedReader().readText())
            val results = geoJson.optJSONArray("results")
                ?: return@withContext ToolResult("Weather", location, "Location not found.", false)
            if (results.length() == 0) return@withContext ToolResult("Weather", location, "Location not found.", false)
            val loc  = results.getJSONObject(0)
            val lat  = loc.getDouble("latitude"); val lon = loc.getDouble("longitude")
            val name = loc.optString("name", location)
            val wJson = JSONObject(
                URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,relative_humidity_2m,wind_speed_10m,weather_code&wind_speed_unit=kmh")
                    .openConnection().apply { connectTimeout = TIMEOUT; readTimeout = TIMEOUT }
                    .getInputStream().bufferedReader().readText())
            val cur  = wJson.getJSONObject("current")
            val temp = cur.getDouble("temperature_2m")
            val hum  = cur.getInt("relative_humidity_2m")
            val wind = cur.getDouble("wind_speed_10m")
            val desc = weatherCodeToDesc(cur.getInt("weather_code"))
            ToolResult("Weather", location, "In $name: $desc, ${temp}°C, humidity ${hum}%, wind ${wind} km/h", true)
        } catch (e: Exception) {
            ToolResult("Weather", location, "Weather unavailable.", false)
        }
    }

    private suspend fun getDictionary(word: String): ToolResult = withContext(Dispatchers.IO) {
        try {
            val url  = URL("https://api.dictionaryapi.dev/api/v2/entries/en/${URLEncoder.encode(word.trim(),"UTF-8")}")
            val arr  = org.json.JSONArray(url.openConnection().apply { connectTimeout = TIMEOUT; readTimeout = TIMEOUT }
                .getInputStream().bufferedReader().readText())
            val m    = arr.getJSONObject(0).getJSONArray("meanings").getJSONObject(0)
            val pos  = m.optString("partOfSpeech","")
            val def  = m.getJSONArray("definitions").getJSONObject(0).optString("definition","")
            ToolResult("Dictionary", word, "$word ($pos): $def", def.isNotBlank())
        } catch (e: Exception) {
            ToolResult("Dictionary", word, "Definition not found.", false)
        }
    }

    private suspend fun getCurrency(query: String): ToolResult = withContext(Dispatchers.IO) {
        try {
            val rates = JSONObject(httpGet("https://api.exchangerate-api.com/v4/latest/USD")).getJSONObject("rates")
            val wanted = LinkedHashSet<String>()
            Regex("\\b[A-Z]{3}\\b").findAll(query).forEach { wanted.add(it.value) }   // codes asked for
            wanted.addAll(listOf("EUR", "GBP", "SAR", "EGP", "AED", "SDG", "JPY"))
            val sb = StringBuilder("Exchange rates (vs USD): ")
            wanted.forEach { c -> if (c != "USD" && rates.has(c)) sb.append("$c=${rates.getDouble(c)} ") }
            ToolResult("Currency", query, sb.toString().trim(), true)
        } catch (e: Exception) {
            ToolResult("Currency", query, "Rates unavailable.", false)
        }
    }

    private fun checkDateTime(lower: String): ToolResult? {
        if (!lower.contains(Regex("time|date|day|today|now|what year|current"))) return null
        val fmt = java.text.SimpleDateFormat("EEEE, MMMM d yyyy, HH:mm", java.util.Locale.ENGLISH)
        return ToolResult("DateTime", "current", "Current date/time: ${fmt.format(java.util.Date())}", true)
    }

    // Arabic-Indic / Persian digits, × ÷ − and ** → plain ASCII so "٣ × ٤" works too.
    private fun normalizeMath(input: String): String = buildString(input.length) {
        for (ch in input) append(when (ch) {
            in '\u0660'..'\u0669' -> '0' + (ch - '\u0660')
            in '\u06F0'..'\u06F9' -> '0' + (ch - '\u06F0')
            '\u066B'              -> '.'
            '\u066C'              -> ' '
            '\u00D7'              -> '*'
            '\u00F7'              -> '/'
            '\u2212', '\u2013'    -> '-'
            else                  -> ch
        })
    }.replace("**", "^")

    /** The message itself, if it is nothing but an arithmetic expression; else null. */
    private fun pureMathExpression(input: String): String? {
        val cleaned = normalizeMath(input).trim()
            .removeSuffix("?").removeSuffix("\u061F").removeSuffix("=").trim()
        if (cleaned.length > 50) return null
        if (!Regex("""^\s*[\d\s\+\-\*\/\^\(\)\.]+\s*$""").matches(cleaned)) return null
        if (cleaned.drop(1).none { it in "+-*/^" }) return null   // a bare "42" is not a calculation
        return cleaned
    }

    private fun checkMath(input: String): ToolResult? = pureMathExpression(input)?.let { evalMath(it) }

    /** Null when [expr] is not a well-formed expression (never a silently wrong number). */
    private fun evalMath(expr: String): ToolResult? {
        val cleaned = normalizeMath(expr).trim()
            .removeSuffix("?").removeSuffix("\u061F").removeSuffix("=").trim()
        val compact = cleaned.replace(" ", "")
        if (compact.isEmpty() || compact.length > 80) return null
        return try {
            val v = MathEval(compact).parseAll()
            val shown = if (v.isNaN() || v.isInfinite()) "undefined" else formatNumber(v)
            ToolResult("Calculator", cleaned, "$cleaned = $shown", true)
        } catch (e: Exception) { null }
    }

    // Locale.US: String.format() with the default locale can emit Arabic-Indic digits or a
    // comma decimal separator on Arabic-locale phones.
    private fun formatNumber(v: Double): String {
        if (Math.floor(v) == v && Math.abs(v) < 1e15) return v.toLong().toString()
        return String.format(java.util.Locale.US, "%.6f", v).trimEnd('0').trimEnd('.')
    }

    // Precedence: + -  <  * /  <  unary -  <  ^ (right-assoc)  <  atom.
    // Old version had no '^' (so "2^3" silently returned 2) and ignored trailing junk.
    private inner class MathEval(private val s: String) {
        private var p = 0
        fun parseAll(): Double {
            val v = addSub()
            require(p == s.length) { "unexpected '${s[p]}' at $p" }
            return v
        }
        private fun addSub(): Double {
            var v = mulDiv()
            while (p < s.length && (s[p] == '+' || s[p] == '-')) {
                val op = s[p++]; val r = mulDiv(); v = if (op == '+') v + r else v - r
            }
            return v
        }
        private fun mulDiv(): Double {
            var v = unary()
            while (p < s.length && (s[p] == '*' || s[p] == '/')) {
                val op = s[p++]; val r = unary(); v = if (op == '*') v * r else v / r
            }
            return v
        }
        private fun unary(): Double {
            if (p < s.length && s[p] == '-') { p++; return -unary() }
            if (p < s.length && s[p] == '+') { p++; return unary() }
            return power()
        }
        private fun power(): Double {
            val base = atom()
            if (p < s.length && s[p] == '^') { p++; return Math.pow(base, unary()) }
            return base
        }
        private fun atom(): Double {
            if (p < s.length && s[p] == '(') {
                p++
                val v = addSub()
                require(p < s.length && s[p] == ')') { "missing )" }
                p++
                return v
            }
            val start = p
            while (p < s.length && (s[p] in '0'..'9' || s[p] == '.')) p++
            require(p > start) { "number expected at $start" }
            return s.substring(start, p).toDouble()
        }
    }

    private fun extractLocation(text: String): String {
        Regex("""weather (?:in|at|for) ([A-Za-z\s]+)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.trim()?.let { return it }
        return "Khartoum"
    }

    private fun extractSubject(text: String): String {
        val patterns = listOf(
            Regex("""what is (?:a |an |the )?(.+?)(?:\?|$)""", RegexOption.IGNORE_CASE),
            Regex("""who (?:is|was) (.+?)(?:\?|$)""", RegexOption.IGNORE_CASE),
            Regex("""tell me about (.+?)(?:\?|$)""", RegexOption.IGNORE_CASE),
            Regex("""explain (.+?)(?:\?|$)""", RegexOption.IGNORE_CASE),
            Regex("""history of (.+?)(?:\?|$)""", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) { val m = p.find(text); if (m != null) return m.groupValues[1].trim().take(50) }
        return ""
    }

    private fun extractWordToDefine(text: String): String =
        Regex("""(?:meaning of|definition of|define|what does) ["']?([a-zA-Z]+)["']? mean?""",
            RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1) ?: ""

    private fun weatherCodeToDesc(code: Int) = when (code) {
        0 -> "Clear sky"; in 1..3 -> "Partly cloudy"; in 45..48 -> "Foggy"
        in 51..67 -> "Rainy"; in 71..77 -> "Snowy"; in 80..82 -> "Rain showers"
        in 95..99 -> "Thunderstorm"; else -> "Cloudy"
    }
}
