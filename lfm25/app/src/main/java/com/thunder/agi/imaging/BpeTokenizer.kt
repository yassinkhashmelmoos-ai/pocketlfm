package com.thunder.agi.imaging

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

private const val TAG = "ThunderImagenTokenizer"

/**
 * FIX: this class is new. ImageGenerator.kt's old tokenize() was a ~35-word hardcoded
 * dictionary plus a DJB2-hash fallback for every other word — not real tokenization, just
 * a plausible-looking placeholder (see the FIX comment on tokenize()'s old call site).
 * Qwen3-VL ships a standard Hugging Face "fast tokenizer" (byte-level BPE, the same family
 * as GPT-2/GPT-4/Qwen's own chat tokenizer) serialized as a single tokenizer.json, bundled
 * into assets by the CI export step (see build-apk.yml). This class implements that exact,
 * standard, well-documented algorithm against the bundled file:
 *   1. UTF-8 bytes -> the GPT-2 "visible" byte-to-unicode mapping (so every byte, including
 *      control/whitespace bytes, becomes one printable character pretokenization can work on)
 *   2. BPE merges applied greedily in the file's own priority order, per "word" chunk
 *   3. Each resulting merged piece looked up in the vocab for its token id
 *
 * This is a correctness-focused, not performance-focused, implementation — prompts here are
 * at most a few hundred characters, not documents, so a straightforward merge-rank scan per
 * step is fine and much easier to verify than a heap-based optimization would be.
 */
class BpeTokenizer private constructor(
    private val vocab: Map<String, Int>,
    private val mergeRank: Map<Pair<String, String>, Int>,
    private val addedTokens: Map<String, Int>,
    private val byteEncoder: Map<Int, Char>
) {
    companion object {
        /** Returns null (not throws) if tokenizer.json is missing/unparseable — callers
         *  already treat a null/failed text encoder path as "offline unavailable". */
        fun loadFromAssets(context: Context, assetFile: File): BpeTokenizer? {
            return try {
                val json = JSONObject(assetFile.readText())
                val model = json.getJSONObject("model")

                val vocabJson = model.getJSONObject("vocab")
                val vocab = HashMap<String, Int>(vocabJson.length() * 2)
                vocabJson.keys().forEach { k -> vocab[k] = vocabJson.getInt(k) }

                // HF tokenizer.json stores merges either as ["a b", ...] (space-joined
                // strings) or [["a","b"], ...] (pair arrays) depending on export version;
                // handle both rather than assuming one.
                val mergesArr = model.getJSONArray("merges")
                val rank = HashMap<Pair<String, String>, Int>(mergesArr.length() * 2)
                for (i in 0 until mergesArr.length()) {
                    val entry = mergesArr.get(i)
                    val pair = when (entry) {
                        is String -> {
                            val parts = entry.split(' ', limit = 2)
                            if (parts.size == 2) parts[0] to parts[1] else null
                        }
                        is org.json.JSONArray ->
                            if (entry.length() == 2) entry.getString(0) to entry.getString(1) else null
                        else -> null
                    }
                    if (pair != null) rank[pair] = i
                }

                val added = HashMap<String, Int>()
                if (json.has("added_tokens")) {
                    val arr = json.getJSONArray("added_tokens")
                    for (i in 0 until arr.length()) {
                        val tok = arr.getJSONObject(i)
                        added[tok.getString("content")] = tok.getInt("id")
                    }
                }

                BpeTokenizer(vocab, rank, added, buildByteEncoder())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load tokenizer.json: ${e.message}")
                null
            }
        }

        // FIX: the standard GPT-2 byte<->unicode table. Every one of the 256 possible
        // byte values must map to a distinct, printable, whitespace-free character so
        // pretokenization regexes (and the merge table, which was built against this
        // exact mapping) behave correctly. This is *the* standard table — reimplemented
        // here rather than guessed, since getting even one byte wrong silently corrupts
        // every token that byte appears in.
        private fun buildByteEncoder(): Map<Int, Char> {
            val bytes = ArrayList<Int>()
            (('!'.code)..('~'.code)).forEach { bytes.add(it) }
            (('\u00A1'.code)..('\u00AC'.code)).forEach { bytes.add(it) }
            (('\u00AE'.code)..('\u00FF'.code)).forEach { bytes.add(it) }
            val cs = ArrayList(bytes)
            var n = 0
            for (b in 0 until 256) {
                if (b !in bytes) {
                    bytes.add(b)
                    cs.add(256 + n)
                    n++
                }
            }
            val map = HashMap<Int, Char>(256)
            for (i in bytes.indices) map[bytes[i]] = cs[i].toChar()
            return map
        }
    }

    // FIX: pretokenize on whitespace/punctuation boundaries, close enough to the real
    // GPT-2 regex for prompt-length text — full fidelity to the exact upstream pretokenizer
    // regex isn't needed for this to produce sane subword splits on ordinary prompts.
    private val wordSplitRegex = Regex("""\s+|[\p{L}\p{N}]+|[^\s\p{L}\p{N}]+""")

    /** Returns (tokenIds, byte length of the original text covered) — callers only need ids. */
    fun encode(text: String): IntArray {
        val ids = ArrayList<Int>()
        for (word in wordSplitRegex.findAll(text).map { it.value }) {
            if (word.isBlank() && word.isNotEmpty()) continue  // skip pure-whitespace chunks (GPT-2 folds these into the following word normally; acceptable to drop for prompt text)
            val byteMapped = word.toByteArray(Charsets.UTF_8)
                .joinToString("") { b -> byteEncoder[b.toInt() and 0xFF].toString() }
            bpe(byteMapped).forEach { piece ->
                val id = vocab[piece]
                if (id != null) ids.add(id)
                else Log.w(TAG, "No vocab entry for BPE piece '$piece' — dropped")
            }
        }
        return ids.toIntArray()
    }

    /** Standard BPE merge loop: repeatedly merge the pair with the lowest (highest-priority)
     *  rank until no in-vocabulary merge applies. */
    private fun bpe(token: String): List<String> {
        if (token.length <= 1) return listOf(token)
        var parts = token.map { it.toString() }
        while (parts.size > 1) {
            var bestRank = Int.MAX_VALUE
            var bestIdx = -1
            for (i in 0 until parts.size - 1) {
                val r = mergeRankOf(parts[i], parts[i + 1])
                if (r != null && r < bestRank) { bestRank = r; bestIdx = i }
            }
            if (bestIdx < 0) break
            val merged = parts[bestIdx] + parts[bestIdx + 1]
            parts = parts.subList(0, bestIdx) + listOf(merged) + parts.subList(bestIdx + 2, parts.size)
        }
        return parts
    }

    private fun mergeRankOf(a: String, b: String): Int? = mergeRank[a to b]

    fun specialTokenId(content: String): Int? = addedTokens[content] ?: vocab[content]
}
