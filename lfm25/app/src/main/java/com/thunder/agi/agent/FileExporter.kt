package com.thunder.agi.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "FileExporter"

/**
 * FileExporter — saves AI responses as downloadable files.
 *
 * Supports:
 * - .txt  — plain text response
 * - .md   — markdown formatted response
 * - .json — structured JSON export of conversation
 * - .csv  — tabular data if response contains a table
 * - .html — rich HTML with Thunder AGI branding
 *
 * Files are saved to app's external storage and shared via Android's
 * file sharing intent so the user can save to Downloads, send via
 * WhatsApp, email, etc.
 */
class FileExporter(private val context: Context) {

    enum class ExportFormat(val extension: String, val mimeType: String) {
        TXT("txt", "text/plain"),
        MARKDOWN("md", "text/markdown"),
        JSON("json", "application/json"),
        HTML("html", "text/html"),
        CSV("csv", "text/csv")
    }

    data class ExportResult(
        val file: File,
        val uri: Uri,
        val format: ExportFormat,
        val sizeKb: Long
    )

    private val exportDir = File(
        context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
        "ThunderAGI"
    ).also { it.mkdirs() }

    suspend fun exportResponse(
        content: String,
        prompt: String = "",
        format: ExportFormat = ExportFormat.TXT,
        filename: String? = null
    ): ExportResult? = withContext(Dispatchers.IO) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
            val name = filename ?: "thunder_agi_$timestamp"
            val file = File(exportDir, "$name.${format.extension}")

            val formattedContent = when (format) {
                ExportFormat.TXT  -> formatTxt(content, prompt, timestamp)
                ExportFormat.MARKDOWN -> formatMarkdown(content, prompt, timestamp)
                ExportFormat.JSON -> formatJson(content, prompt, timestamp)
                ExportFormat.HTML -> formatHtml(content, prompt, timestamp)
                ExportFormat.CSV  -> formatCsv(content)
            }

            file.writeText(formattedContent, Charsets.UTF_8)
            Log.i(TAG, "Exported: ${file.name} (${file.length()/1024}KB)")

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            ExportResult(file, uri, format, file.length() / 1024)
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            null
        }
    }

    suspend fun exportConversation(
        messages: List<Pair<Boolean, String>>, // isUser, content
        sessionTitle: String
    ): ExportResult? = withContext(Dispatchers.IO) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
            val file = File(exportDir, "${sessionTitle.take(30)}_$timestamp.md")

            val sb = StringBuilder()
            sb.appendLine("# $sessionTitle")
            sb.appendLine("*Exported from Thunder AGI — ${SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.ENGLISH).format(Date())}*")
            sb.appendLine()
            sb.appendLine("---")
            sb.appendLine()

            messages.forEach { (isUser, content) ->
                if (isUser) {
                    sb.appendLine("**You:** $content")
                } else {
                    sb.appendLine("**Thunder AGI:**")
                    sb.appendLine(content)
                }
                sb.appendLine()
            }

            file.writeText(sb.toString(), Charsets.UTF_8)

            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            ExportResult(file, uri, ExportFormat.MARKDOWN, file.length() / 1024)
        } catch (e: Exception) {
            Log.e(TAG, "Conversation export failed", e)
            null
        }
    }

    fun shareFile(result: ExportResult): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = result.format.mimeType
            putExtra(Intent.EXTRA_STREAM, result.uri)
            putExtra(Intent.EXTRA_SUBJECT, "Thunder AGI Response")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun openFile(result: ExportResult): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(result.uri, result.format.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun listExports(): List<File> =
        exportDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun clearExports() = exportDir.listFiles()?.forEach { it.delete() }

    // ── Formatters ────────────────────────────────────────────────────────────

    private fun formatTxt(content: String, prompt: String, ts: String): String = buildString {
        appendLine("Thunder AGI Response")
        appendLine("Generated: $ts")
        if (prompt.isNotBlank()) {
            appendLine("Question: $prompt")
            appendLine()
        }
        appendLine("=".repeat(50))
        appendLine()
        appendLine(content)
    }

    private fun formatMarkdown(content: String, prompt: String, ts: String): String = buildString {
        appendLine("# Thunder AGI Response")
        appendLine("*$ts*")
        appendLine()
        if (prompt.isNotBlank()) {
            appendLine("**Question:** $prompt")
            appendLine()
            appendLine("---")
            appendLine()
        }
        appendLine(content)
    }

    private fun formatJson(content: String, prompt: String, ts: String): String =
        """{"timestamp":"$ts","model":"LFM2.5-VL-450M","prompt":${prompt.jsonStr()},"response":${content.jsonStr()}}"""

    private fun formatHtml(content: String, prompt: String, ts: String): String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Thunder AGI Response</title>
<style>
  body { font-family: -apple-system, sans-serif; max-width: 800px; margin: 40px auto; padding: 20px; background: #0a0a0f; color: #eaf6ff; }
  h1 { color: #00d4ff; }
  .meta { color: #8899aa; font-size: 0.9em; margin-bottom: 20px; }
  .prompt { background: #1a3a5c; padding: 16px; border-radius: 8px; margin-bottom: 20px; border-left: 3px solid #00d4ff; }
  .response { background: #111827; padding: 16px; border-radius: 8px; line-height: 1.7; white-space: pre-wrap; }
  code { background: #0d1117; padding: 2px 6px; border-radius: 4px; font-family: monospace; color: #4fc3f7; }
  pre { background: #0d1117; padding: 12px; border-radius: 8px; overflow-x: auto; }
</style>
</head>
<body>
<h1>⚡ Thunder AGI</h1>
<div class="meta">الرَّعد للذكاء العام المصطنع · $ts</div>
${if (prompt.isNotBlank()) """<div class="prompt"><strong>Question:</strong> ${prompt.escapeHtml()}</div>""" else ""}
<div class="response">${content.escapeHtml()}</div>
</body>
</html>
""".trimIndent()

    private fun formatCsv(content: String): String {
        // Try to detect table-like content and convert to CSV
        val lines = content.lines()
        val sb = StringBuilder()
        lines.forEach { line ->
            if (line.contains("|")) {
                val cells = line.split("|").map { it.trim() }.filter { it.isNotBlank() && it != "---" }
                if (cells.isNotEmpty()) sb.appendLine(cells.joinToString(",") { "\"$it\"" })
            } else if (line.isNotBlank()) {
                sb.appendLine("\"${line.replace("\"", "\"\"")}\"")
            }
        }
        return sb.toString()
    }

    private fun String.jsonStr() = "\"${replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")}\""
    private fun String.escapeHtml() = replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
}
