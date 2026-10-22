package com.thunder.agi.relay

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.thunder.agi.crypto.SecureStore
import com.thunder.agi.ledger.Transaction
import com.thunder.agi.ledger.TxText
import com.thunder.agi.util.TLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

private const val TAG = "WhatsApp"

/**
 * USER side: how a normal phone sends a payment through WhatsApp.
 *
 * A normal app cannot send WhatsApp messages by itself, and it must NOT carry a WhatsApp Business API token (anyone could
 * extract it from the APK and abuse or get the account banned). So the app opens WhatsApp with the message prepared and the
 * user taps Send. The message is just "⚡TX1:<base64url>": one short text holding the entire signed payment.
 * The recipient's app imports it from the "Thunder AGI" chat notification, from the clipboard, or by sharing the text.
 */
object WhatsAppShare {
    fun message(tx: Transaction, note: String = "Thunder payment"): String = "$note\n${TxText.encode(tx.raw)}"

    /** Opens WhatsApp (optionally on a specific number, international format) with [text] ready to send. */
    fun open(context: Context, phone: String?, text: String): Boolean {
        val digits = phone?.filter { it.isDigit() }.orEmpty()
        val uri = if (digits.isEmpty()) "https://wa.me/?text=${Uri.encode(text)}" else "https://wa.me/$digits?text=${Uri.encode(text)}"
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) { TLog.w(TAG, "open: ${e.message}"); false }
    }

    /** Generic share sheet (WhatsApp, SMS, Telegram, email...). */
    fun share(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, "Send payment").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * OPERATOR side only (the issuer / relay phone): WhatsApp Cloud API sender.
 *
 * Reality check about the Cloud API:
 *  - it sends FROM your business number TO users; a user's own phone cannot "post to the API";
 *  - free-text messages are only allowed within 24 h after the user last wrote to your business number
 *    (otherwise you need an approved template message, which costs money);
 *  - incoming messages reach you by webhook (a public HTTPS server) or, on a relay phone, by the notification listener;
 *  - the access token is a secret: it is entered once on the operator phone and stored encrypted by the Keystore.
 *    NEVER hard-code it or put it in a build that normal users install.
 */
class WhatsAppCloudSender(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("thunder_wa_operator", Context.MODE_PRIVATE)
    private val secure = SecureStore(context.applicationContext)

    val isConfigured: Boolean get() = !prefs.getString("pid", null).isNullOrBlank() && !prefs.getString("tok", null).isNullOrBlank()

    fun configure(phoneNumberId: String, accessToken: String) {
        prefs.edit().putString("pid", phoneNumberId.trim())
            .putString("tok", Base64.getEncoder().encodeToString(secure.encrypt(accessToken.trim().toByteArray()))).apply()
    }

    fun clear() { prefs.edit().clear().apply() }

    /** Returns true if Meta accepted the message (HTTP 2xx). */
    suspend fun sendText(toPhone: String, text: String): Boolean = withContext(Dispatchers.IO) {
        val pid = prefs.getString("pid", null) ?: return@withContext false
        val token = try { String(secure.decrypt(Base64.getDecoder().decode(prefs.getString("tok", "") ?: ""))) } catch (e: Exception) { return@withContext false }
        val body = JSONObject()
            .put("messaging_product", "whatsapp")
            .put("to", toPhone.filter { it.isDigit() })
            .put("type", "text")
            .put("text", JSONObject().put("body", text))
            .toString()
        try {
            val c = URL("https://graph.facebook.com/v21.0/$pid/messages").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 15_000; c.readTimeout = 20_000; c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
            val ok = c.responseCode in 200..299
            if (!ok) TLog.w(TAG, "Cloud API HTTP ${c.responseCode}: ${c.errorStream?.bufferedReader()?.readText()?.take(200)}")
            c.disconnect()
            ok
        } catch (e: Exception) { TLog.w(TAG, "Cloud API error: ${e.message}"); false }
    }

    suspend fun sendTransaction(toPhone: String, tx: Transaction): Boolean = sendText(toPhone, "Thunder payment\n${TxText.encode(tx.raw)}")
}
