package com.thunder.agi.node

import android.app.Notification
import android.content.Context
import android.os.Bundle
import com.thunder.agi.ledger.TxText
import com.thunder.agi.transfer.ChunkedTransferProtocol

/**
 * Helper for the existing WhatsApp notification listener.
 *
 * WhatsApp notifications can carry the text in several places (single message, expanded text, a list of
 * unread messages, or a messaging-style bundle). [collect] gathers all of them so no payment is missed, and
 * [importThunderPayments] feeds anything that looks like a Thunder payment ("TX1:...") or a bulk chunk ("⚡T:...")
 * into the wallet node. It ignores every other message completely (nothing is stored or logged).
 *
 * Privacy: call it ONLY for the dedicated "Thunder AGI" chat, exactly like the listener's existing filter does.
 */
object NotificationText {
    fun collect(extras: Bundle): List<String> {
        val out = LinkedHashSet<String>()
        fun add(cs: CharSequence?) { cs?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) } }
        add(extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        add(extras.getCharSequence(Notification.EXTRA_TEXT))
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { add(it) }
        @Suppress("DEPRECATION")
        extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.forEach { (it as? Bundle)?.let { b -> add(b.getCharSequence("text")) } }
        return out.toList()
    }

    /** Returns true if at least one text contained a Thunder payment or chunk (so the caller can skip its other handlers). */
    fun importThunderPayments(context: Context, extras: Bundle): Boolean {
        var found = false
        val rt by lazy { ThunderRuntime.get(context.applicationContext) }
        for (t in collect(extras)) {
            if (t.contains(TxText.TAG)) { rt.importTextAsync(t); found = true }
            if (t.contains(ChunkedTransferProtocol.PREFIX)) { rt.importChunkAsync(t); found = true }
        }
        return found
    }
}
