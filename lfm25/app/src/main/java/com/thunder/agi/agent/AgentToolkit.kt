package com.thunder.agi.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder

private const val TAG = "ThunderAGI_Agent"
private const val TIMEOUT = 6000

/**
 * AgentToolkit — gives Thunder AGI the ability to use external tools autonomously.
 *
 * This implements a ReAct (Reasoning + Acting) pattern:
 * 1. Model sees user message
 * 2. Agent scans message for tool triggers
 * 3. If tool needed, agent calls it and prepends result to model context
 * 4. Model generates response grounded in real data
 *
 * Tools available:
 * - Web search (DuckDuckGo Instant Answer API — free, no key)
 * - Wikipedia summary (Wikipedia REST API — free, no key)
 * - Weather (Open-Meteo API — free, no key, no sign-up)
 * - News headlines (GNews API free tier OR RSS parsing)
 * - Dictionary definitions (Free Dictionary API — free, no key)
 * - Currency/crypto rates (ExchangeRate-API free tier)
 * - Math evaluation (local)
 * - Code syntax check (local)
 * - Time/date (local)
 */
class AgentToolkit(private val context: Context) {

    data class ToolResult(
        val toolName: String,
        val query: String,
        val result: String,
        val success: Boolean
    )

    // ── Tool detection — decide which tools to call based on user message ──────
    suspend fun gatherContext(userMessage: String, webEnabled: Boolean): String {
        val tools = mutableListOf<ToolResult>()
        val lower = userMessage.lowercase()

        // Always try these lightweight local tools
        checkDateTime(lower)?.let { tools.add(it) }
        checkMath(userMessage)?.let { tools.add(it) }

        if (webEnabled) {
            // Weather
            if (lower.contains(Regex("weather|temperature|rain|forecast|humidity|wind|climate"))) {
                tools.add(getWeather(extractLocation(userMessage)))
            }
            // Wikipedia for factual questions
            if (lower.contains(Regex("what is|who is|who was|what was|explain|define|tell me about|history of|when did|where is"))) {
                val subject = extractSubject(userMessage)
                if (subject.isNotBlank()) tools.add(getWikipedia(subject))
            }
            // Dictionary
            if (lower.contains(Regex("meaning of|definition of|what does .* mean|define "))) {
                val word = extractWordToDefine(userMessage)
                if (word.isNotBlank()) tools.add(getDictionary(word))
            }
            // Currency/crypto
            if (lower.contains(Regex("price of|exchange rate|how much is .*(dollar|euro|btc|bitcoin|eth|usd|gbp|sar|egp)"))) {
                tools.add(getCurrency(userMessage))
            }
            // General web search as fallback for web-enabled questions
            if (tools.none { it.success } || lower.contains(Regex("search|find|look up|latest|recent|news|today"))) {
                tools.add(webSearch(userMessage))
            }
        }

        if (tools.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("[Tool Results — use this information to answer accurately]\n")
        tools.filter { it.success }.forEach { tool ->
            sb.append("${tool.toolName}: ${tool.result}\n")
        }
        sb.append("[End Tool Results]\n\n")
        return sb.toString()
    }

    // ── Tools ──────────────────────────────────────────────────────────────────

    private suspend fun webSearch(query: String): ToolResult = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1")
            val conn = url.openConnection().apply { connectTimeout = TIMEOUT; readTimeout = TIMEOUT }
            val json = JSONObject(conn.getInputStream().bufferedReader().readText())
            val answer   = json.optString("Answer", "")
            val abstract = json.optString("AbstractText", "")
            val result = answer.ifBlank { abstract }
            ToolResult("Web Search", query,
                result.ifBlank { "No instant answer found." },
                result.isNotBlank())
        } catch (e: Exception) {
            Log.w(TAG, "Web search failed: ${e.message}")
            ToolResult("Web Search", query, "Search unavailable.", false)
        }
    }

    private suspend fun getWikipedia(subject: String): ToolResult = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(subject, "UTF-8")
            val url = URL("https://en.wikipedia.org/api/rest_v1/page/summary/$encoded")
            val conn = url.openConnection().apply {
                connectTimeout = TIMEOUT; readTimeout = TIMEOUT
                setRequestProperty("User-Agent", "ThunderAGI/1.0")
            }
            val json = JSONObject(conn.getInputStream().bufferedReader().readText())
            val summary = json.optString("extract", "")
            ToolResult("Wikipedia", subject,
                summary.take(500).ifBlank { "No Wikipedia article found." },
                summary.isNotBlank())
        } catch (e: Exception) {
            Log.w(TAG, "Wikipedia failed: ${e.message}")
            ToolResult("Wikipedia", subject, "Unavailable.", false)
        }
    }

    private suspend fun getWeather(location: String): ToolResult = withContext(Dispatchers.IO) {
        try {
            // First geocode the location
            val geoUrl = URL("https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(location, "UTF-8")}&count=1")
            val geoConn = geoUrl.openConnection().apply { connectTimeout = TIMEOUT; readTimeout = TIMEOUT }
            val geoJson = JSONObject(geoConn.getInputStream().bufferedReader().readText())
            val results = geoJson.optJSONArray("results")
            if (results == null || results.length() == 0) {
                return@withContext ToolResult("Weather", location, "Location not found.", false)
            }
            val loc = results.getJSONObject(0)
            val lat = loc.getDouble("latitude")
            val lon = loc.getDouble("longitude")
            val name = loc.optString("name", location)

            // Get weather
            val wUrl = URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,wind_speed_10m,weather_code&wind_speed_unit=kmh")
            val wConn = wUrl.openConnection().apply { connectTimeout = TIMEOUT; readTimeout = TIMEOUT }
            val wJson = JSONObject(wConn.getInputStream().bufferedReader().readText())
            val current = wJson.getJSONObject("current")
            val temp = current.getDouble("temperature_2m")
            val humidity = current.getInt("relative_humidity_2m")
            val wind = current.getDouble("wind_speed_10m")
            val code = current.getInt("weather_code")
            val desc = weatherCodeToDesc(code)

            ToolResult("Weather", location,
                "In $name: $desc, ${temp}°C, humidity ${humidity}%, wind ${wind} km/h", true)
        } catch (e: Exception) {
            Log.w(TAG, "Weather failed: ${e.message}")
            ToolResult("Weather", location, "Weather unavailable.", false)
        }
    }

    private suspend fun getDictionary(word: String): ToolResult = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(word.trim(), "UTF-8")
            val url = URL("https://api.dictionaryapi.dev/api/v2/entries/en/$encoded")
            val conn = url.openConnection().apply { connectTimeout = TIMEOUT; readTimeout = TIMEOUT }
            val text = conn.getInputStream().bufferedReader().readText()
            // Parse first definition
            val arr = org.json.JSONArray(text)
            val entry = arr.getJSONObject(0)
            val meanings = entry.getJSONArray("meanings")
            val meaning = meanings.getJSONObject(0)
            val partOfSpeech = meaning.optString("partOfSpeech", "")
            val defs = meaning.getJSONArray("definitions")
            val def = defs.getJSONObject(0).optString("definition", "")
            ToolResult("Dictionary", word, "$word ($partOfSpeech): $def", def.isNotBlank())
        } catch (e: Exception) {
            ToolResult("Dictionary", word, "Definition not found.", false)
        }
    }

    private suspend fun getCurrency(query: String): ToolResult = withContext(Dispatchers.IO) {
        try {
            // Exchange rates relative to USD — free, no key
            val url = URL("https://api.exchangerate-api.com/v4/latest/USD")
            val conn = url.openConnection().apply { connectTimeout = TIMEOUT; readTimeout = TIMEOUT }
            val json = JSONObject(conn.getInputStream().bufferedReader().readText())
            val rates = json.getJSONObject("rates")
            val sb = StringBuilder("Exchange rates (vs USD): ")
            listOf("EUR","GBP","SAR","EGP","AED","JPY","BTC").forEach { code ->
                if (rates.has(code)) sb.append("$code=${rates.getDouble(code)} ")
            }
            ToolResult("Currency", query, sb.toString().trim(), true)
        } catch (e: Exception) {
            ToolResult("Currency", query, "Rates unavailable.", false)
        }
    }

    private fun checkDateTime(lower: String): ToolResult? {
        if (!lower.contains(Regex("time|date|day|today|now|what year|current"))) return null
        val now = java.util.Calendar.getInstance()
        val fmt = java.text.SimpleDateFormat("EEEE, MMMM d yyyy, HH:mm", java.util.Locale.ENGLISH)
        return ToolResult("DateTime", "current", "Current date and time: ${fmt.format(now.time)}", true)
    }

    private fun checkMath(input: String): ToolResult? {
        // Simple math expressions
        val mathPattern = Regex("""^\s*[\d\s\+\-\*\/\^\(\)\.]+\s*[=?]?\s*$""")
        val cleaned = input.trim().removeSuffix("?").removeSuffix("=").trim()
        if (!mathPattern.matches(cleaned) || cleaned.length > 50) return null
        return try {
            // Use JS engine for safe math eval
            val result = evalMath(cleaned)
            ToolResult("Calculator", cleaned, "$cleaned = $result", true)
        } catch (e: Exception) { null }
    }

    private fun evalMath(expr: String): String {
        return try {
            val result = MathEval(expr.replace(" ", "")).parse()
            if (result == result.toLong().toDouble()) result.toLong().toString()
            else "%.4f".format(result)
        } catch (e: Exception) { "?" }
    }

    // Simple recursive descent math parser — no forward reference issues
    private inner class MathEval(private val s: String) {
        private var p = 0
        fun parse(): Double = addSub()
        private fun addSub(): Double {
            var v = mulDiv()
            while (p < s.length && (s[p] == '+' || s[p] == '-')) {
                val op = s[p++]; v = if (op == '+') v + mulDiv() else v - mulDiv()
            }
            return v
        }
        private fun mulDiv(): Double {
            var v = unary()
            while (p < s.length && (s[p] == '*' || s[p] == '/')) {
                val op = s[p++]; v = if (op == '*') v * unary() else v / unary()
            }
            return v
        }
        private fun unary(): Double {
            if (p < s.length && s[p] == '-') { p++; return -atom() }
            return atom()
        }
        private fun atom(): Double {
            if (p < s.length && s[p] == '(') {
                p++; val v = addSub()
                if (p < s.length && s[p] == ')') p++
                return v
            }
            val start = p
            while (p < s.length && (s[p].isDigit() || s[p] == '.')) p++
            return s.substring(start, p).ifEmpty { "0" }.toDouble()
        }
    }

    private fun evalArithmetic(expr: String): Double {
        var pos = 0
        fun num(): Double {
            val neg = pos < expr.length && expr[pos] == '-'
            if (neg) pos++
            val s = pos
            while (pos < expr.length && (expr[pos].isDigit() || expr[pos] == '.')) pos++
            val n = expr.substring(s, pos).ifEmpty { "0" }.toDouble()
            return if (neg) -n else n
        }
        return MathEval(expr).parse()
    }


    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun extractLocation(text: String): String {
        val patterns = listOf(
            Regex("""weather (?:in|at|for) ([A-Za-z\s]+)""", RegexOption.IGNORE_CASE),
            Regex("""(?:in|at|for) ([A-Za-z\s]+)(?:'s)? weather""", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            val m = p.find(text)
            if (m != null) return m.groupValues[1].trim()
        }
        return "Khartoum" // default to user's location
    }

    private fun extractSubject(text: String): String {
        val patterns = listOf(
            Regex("""what is (?:a |an |the )?(.+?)(?:\?|$)""", RegexOption.IGNORE_CASE),
            Regex("""who (?:is|was) (.+?)(?:\?|$)""", RegexOption.IGNORE_CASE),
            Regex("""tell me about (.+?)(?:\?|$)""", RegexOption.IGNORE_CASE),
            Regex("""explain (.+?)(?:\?|$)""", RegexOption.IGNORE_CASE),
            Regex("""history of (.+?)(?:\?|$)""", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            val m = p.find(text)
            if (m != null) return m.groupValues[1].trim().take(50)
        }
        return ""
    }

    private fun extractWordToDefine(text: String): String {
        val p = Regex("""(?:meaning of|definition of|define|what does) ["']?([a-zA-Z]+)["']? mean?""",
            RegexOption.IGNORE_CASE)
        return p.find(text)?.groupValues?.get(1) ?: ""
    }

    private fun weatherCodeToDesc(code: Int) = when(code) {
        0 -> "Clear sky"
        in 1..3 -> "Partly cloudy"
        in 45..48 -> "Foggy"
        in 51..67 -> "Rainy"
        in 71..77 -> "Snowy"
        in 80..82 -> "Rain showers"
        in 95..99 -> "Thunderstorm"
        else -> "Cloudy"
    }
}
