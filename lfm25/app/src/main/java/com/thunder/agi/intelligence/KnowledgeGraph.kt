package com.thunder.agi.intelligence

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Local knowledge graph stored in SQLite.
 * Learns facts from conversations: (subject, predicate, object, confidence, source).
 * These facts are injected into the system prompt as grounding context,
 * effectively extending the model's knowledge without retraining weights.
 *
 * Example entries:
 *   ("user", "name", "Yassin", 1.0, "user_stated")
 *   ("user", "prefers_language", "Arabic", 0.9, "inferred")
 *   ("Thunder AGI", "last_topic", "AI models", 0.8, "conversation")
 */
class KnowledgeGraph(context: Context) {

    companion object {
        private const val TAG = "KnowledgeGraph"
        private const val DB_NAME = "knowledge_graph.db"
        private const val DB_VERSION = 1
        private const val MAX_FACTS_IN_PROMPT = 12
    }

    private val db: SQLiteDatabase

    init {
        val helper = object : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS facts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        subject TEXT NOT NULL,
                        predicate TEXT NOT NULL,
                        object TEXT NOT NULL,
                        confidence REAL DEFAULT 1.0,
                        source TEXT DEFAULT 'conversation',
                        created_at INTEGER DEFAULT (strftime('%s','now')),
                        access_count INTEGER DEFAULT 0,
                        UNIQUE(subject, predicate) ON CONFLICT REPLACE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_subject ON facts(subject)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_predicate ON facts(predicate)")
            }
            override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}
        }
        db = helper.writableDatabase
    }

    suspend fun storeFact(subject: String, predicate: String, obj: String,
                          confidence: Float = 1.0f, source: String = "conversation") =
        withContext(Dispatchers.IO) {
            try {
                db.execSQL(
                    "INSERT OR REPLACE INTO facts (subject, predicate, object, confidence, source) VALUES (?, ?, ?, ?, ?)",
                    arrayOf(subject.lowercase().trim(), predicate.lowercase().trim(),
                            obj.trim(), confidence, source)
                )
                Log.d(TAG, "Stored: $subject -> $predicate -> $obj")
            } catch (e: Exception) {
                Log.e(TAG, "Store failed", e)
            }
        }

    suspend fun getFactsForSubject(subject: String): List<Triple<String, String, String>> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<Triple<String, String, String>>()
            try {
                val cursor = db.rawQuery(
                    "SELECT subject, predicate, object FROM facts WHERE subject = ? ORDER BY confidence DESC, access_count DESC LIMIT 20",
                    arrayOf(subject.lowercase().trim())
                )
                cursor.use {
                    while (it.moveToNext()) {
                        results.add(Triple(it.getString(0), it.getString(1), it.getString(2)))
                        db.execSQL("UPDATE facts SET access_count = access_count + 1 WHERE subject = ? AND predicate = ?",
                            arrayOf(it.getString(0), it.getString(1)))
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "Query failed", e) }
            results
        }

    /** Build a compact context string injected into the system prompt */
    suspend fun buildContextString(): String = withContext(Dispatchers.IO) {
        try {
            val cursor = db.rawQuery(
                """SELECT subject, predicate, object FROM facts
                   ORDER BY confidence DESC, access_count DESC, created_at DESC
                   LIMIT $MAX_FACTS_IN_PROMPT""", null
            )
            val sb = StringBuilder()
            cursor.use {
                if (!it.moveToFirst()) return@withContext ""
                sb.append("Known facts:\n")
                do {
                    sb.append("- ${it.getString(0)} ${it.getString(1)}: ${it.getString(2)}\n")
                } while (it.moveToNext())
            }
            sb.toString()
        } catch (e: Exception) { "" }
    }

    /** Extract and store facts from a conversation turn using simple heuristics */
    suspend fun learnFromConversation(userText: String, aiResponse: String) =
        withContext(Dispatchers.IO) {
            val lower = userText.lowercase()

            // Name detection
            Regex("""(?:my name is|i(?:'m| am) called|call me)\s+(\w+)""", RegexOption.IGNORE_CASE)
                .find(userText)?.groupValues?.get(1)?.let { name ->
                    storeFact("user", "name", name, 1.0f, "user_stated")
                }

            // Language preference
            if (lower.contains("arabic") || lower.contains("عربي") || lower.contains("بالعربي"))
                storeFact("user", "prefers_language", "Arabic", 0.9f, "inferred")
            else if (lower.contains("english"))
                storeFact("user", "prefers_language", "English", 0.9f, "inferred")

            // Location
            Regex("""(?:i(?:'m| am) in|i live in|from)\s+([A-Z][a-z]+(?:\s+[A-Z][a-z]+)?)""")
                .find(userText)?.groupValues?.get(1)?.let { place ->
                    storeFact("user", "location", place, 0.8f, "inferred")
                }

            // Store last topic
            val topic = userText.take(60).trim()
            if (topic.length > 5) storeFact("conversation", "last_topic", topic, 0.5f, "auto")
        }

    fun close() = db.close()
}
