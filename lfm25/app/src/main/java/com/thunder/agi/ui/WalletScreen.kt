package com.thunder.agi.ui

import android.app.Activity
import android.app.KeyguardManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thunder.agi.ledger.StoredTx
import com.thunder.agi.ledger.Transaction
import com.thunder.agi.ledger.TxWire
import com.thunder.agi.ledger.WalletAddress
import com.thunder.agi.ledger.fmtSDG
import com.thunder.agi.node.NodeEvent
import com.thunder.agi.node.Perms
import com.thunder.agi.node.ThunderRuntime
import com.thunder.agi.node.ThunderService
import com.thunder.agi.node.WalletSnapshot
import com.thunder.agi.relay.WhatsAppCloudSender
import com.thunder.agi.relay.WhatsAppShare
import com.thunder.agi.ui.theme.ThunderBlack
import com.thunder.agi.ui.theme.ThunderDeepBlue
import com.thunder.agi.ui.theme.ThunderElectric
import com.thunder.agi.ui.theme.ThunderGray
import com.thunder.agi.ui.theme.ThunderGreen
import com.thunder.agi.ui.theme.ThunderMidBlue
import com.thunder.agi.ui.theme.ThunderRed
import com.thunder.agi.ui.theme.ThunderWhite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun parseSdg(s: String): Long? = try {
    BigDecimal(s.trim()).setScale(3, RoundingMode.UNNECESSARY).movePointRight(3).longValueExact()
} catch (e: Exception) { null }

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = ThunderElectric, unfocusedBorderColor = ThunderMidBlue,
    focusedTextColor = ThunderWhite, unfocusedTextColor = ThunderWhite, cursorColor = ThunderElectric,
    focusedContainerColor = ThunderDeepBlue, unfocusedContainerColor = ThunderDeepBlue
)

/**
 * Wallet screen. Open it with ThunderNav.showWallet.value = true (see MainActivity patch).
 * Every spend and every mint first asks for the phone's PIN / pattern / fingerprint (system confirm-credentials screen),
 * so someone who picks up an unlocked phone with the app open still cannot move money.
 */
@Composable
fun WalletScreen(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val rt = remember { ThunderRuntime.get(ctx) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    BackHandler { onDismiss() }

    var usable by remember { mutableStateOf<Boolean?>(null) }          // null = still loading keys
    val status by rt.status.collectAsState()
    val version by rt.ledgerVersion.collectAsState()
    var snap by remember { mutableStateOf<WalletSnapshot?>(null) }
    var banner by remember { mutableStateOf<String?>(null) }
    var showSend by remember { mutableStateOf(false) }
    var showReceive by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var sentTx by remember { mutableStateOf<Transaction?>(null) }
    var detailTx by remember { mutableStateOf<Transaction?>(null) }
    var serviceOn by remember { mutableStateOf(ThunderService.isEnabled(ctx)) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    LaunchedEffect(Unit) {
        // Keystore / Kyber key creation can take a second on first run: never on the UI thread.
        usable = withContext(Dispatchers.Default) { val ok = rt.wallet.isUsable(); if (ok) rt.start(); ok }
    }
    LaunchedEffect(version, usable) { if (usable == true) snap = rt.snapshot() }
    LaunchedEffect(Unit) {
        rt.events.onEach { e ->
            banner = when (e) {
                is NodeEvent.PaymentReceived -> "💸 +${e.tx.amountMilli.fmtSDG()}  ←  ${rt.wallet.resolveName(e.tx.from)}"
                is NodeEvent.PaymentNotice -> "🔔 دفعة قادمة ${e.amountMilli.fmtSDG()} — جارٍ المزامنة…  Incoming payment, syncing…"
                is NodeEvent.Info -> e.text
            }
        }.launchIn(this)
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { rt.housekeeping() }
    val unlockLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val action = pendingAction
        pendingAction = null
        if (r.resultCode == Activity.RESULT_OK) action?.invoke() else banner = "تم إلغاء العملية  Cancelled"
    }

    /** Ask for the phone PIN/pattern/fingerprint first (if the phone has a lock), then run [action]. */
    fun requireUnlock(action: () -> Unit) {
        val km = ctx.getSystemService(KeyguardManager::class.java)
        val intent = if (km != null && km.isDeviceSecure) km.createConfirmDeviceCredentialIntent("تأكيد الدفع  Confirm payment", null) else null
        if (intent != null) { pendingAction = action; unlockLauncher.launch(intent) } else action()
    }

    Column(Modifier.fillMaxSize().background(ThunderBlack)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = ThunderWhite) }
            Text("⚡ Thunder Wallet  المحفظة", color = ThunderWhite, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                modifier = Modifier.weight(1f))
            TextButton(onClick = { showAbout = true }) { Text("ⓘ", color = ThunderElectric, fontSize = 18.sp) }
        }

        if (usable == null) {
            Text("جارٍ تحميل المحفظة…  Loading wallet…", color = ThunderGray, modifier = Modifier.padding(16.dp))
        } else if (usable == false) {
            Card(Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = ThunderDeepBlue)) {
                Column(Modifier.padding(16.dp)) {
                    Text("⚠ مفاتيح المحفظة غير متاحة على هذا الهاتف", color = ThunderRed, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("The wallet keys cannot be opened on this phone (for example after restoring a backup onto a new phone). " +
                        "Money of the old address cannot be moved from here. Contact the issuer to migrate your balance.",
                        color = ThunderGray, fontSize = 13.sp)
                }
            }
        } else {
            val me = remember { rt.wallet.address }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {

                item { BalanceCard(snap, onSend = { showSend = true }, onReceive = { showReceive = true }) }

                banner?.let { b ->
                    item {
                        Card(Modifier.fillMaxWidth().clickable { banner = null }, colors = CardDefaults.cardColors(containerColor = ThunderMidBlue)) {
                            Text(b, color = ThunderWhite, modifier = Modifier.padding(12.dp), fontSize = 13.sp)
                        }
                    }
                }

                item {
                    NearbyCard(
                        status = status, serviceOn = serviceOn,
                        onEnable = { val m = Perms.missing(ctx); if (m.isNotEmpty()) permLauncher.launch(m) else rt.housekeeping() },
                        onService = { on ->
                            val m = Perms.missing(ctx)
                            if (on && m.isNotEmpty()) permLauncher.launch(m)
                            ThunderService.setEnabled(ctx, on); serviceOn = on
                        }
                    )
                }

                item {
                    OutlinedButton(
                        onClick = {
                            val text = clipboard.getText()?.text.orEmpty()
                            scope.launch {
                                val r = rt.importText(text)
                                banner = if (r.found == 0) "لا توجد دفعة في النص المنسوخ  No payment found in the copied text"
                                else "استُورد ${r.accepted} · قيد التحقق ${r.pending} · مرفوض ${r.rejected}  (imported / waiting / rejected)"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, ThunderMidBlue)
                    ) { Text("📋 لصق دفعة من واتساب  Paste a payment (TX1:…)", color = ThunderElectric) }
                }

                if (status.isIssuer) item {
                    IssuerCard(
                        onMint = { to, milli, ref, done ->
                            requireUnlock {
                                scope.launch {
                                    val r = rt.mint(to, milli, ref)
                                    r.onSuccess { sentTx = it; banner = "✅ تم الإصدار ${milli.fmtSDG()}" }
                                    r.onFailure { banner = "❌ ${it.message}" }
                                    done()
                                }
                            }
                        }
                    )
                }

                item {
                    Text("السجل  History", color = ThunderWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }

                val history = snap?.history.orEmpty()
                if (history.isEmpty()) {
                    item { Text("لا توجد معاملات بعد  No transactions yet", color = ThunderGray, fontSize = 13.sp) }
                } else {
                    items(history, key = { it.tx.txid }) { s -> TxRow(s, me, rt.wallet::resolveName) { detailTx = s.tx } }
                }
            }
        }
    }

    if (showSend) SendDialog(
        peers = rt.wallet.peers(),
        onDismiss = { showSend = false },
        onConfirm = { to, milli, memo ->
            showSend = false
            requireUnlock {
                scope.launch {
                    val r = rt.sendTransfer(to, milli, memo)
                    r.onSuccess { sentTx = it }
                    r.onFailure { banner = "❌ ${it.message}" }
                }
            }
        }
    )

    if (showReceive) AddressDialog(rt.wallet.address, rt.wallet.displayName, { showReceive = false },
        onCopy = { clipboard.setText(AnnotatedString(rt.wallet.address)) },
        onShare = { WhatsAppShare.share(ctx, "عنوان محفظتي في Thunder / My Thunder address:\n${rt.wallet.address}") })

    sentTx?.let { tx -> ShareDialog(tx, ctx, clipboard::setText) { sentTx = null } }
    detailTx?.let { tx -> DetailDialog(tx, rt.wallet.address, rt.wallet::resolveName, ctx, clipboard::setText) { detailTx = null } }

    if (showAbout) AboutDialog(
        address = rt.wallet.address, name = rt.wallet.displayName, keyHex = rt.issuerKeyHex(),
        hardware = status.hardwareKey, ledgerSize = snap?.ledgerSize ?: 0, pending = snap?.pendingCount ?: 0,
        onName = { rt.wallet.displayName = it }, onCopyKey = { clipboard.setText(AnnotatedString(rt.issuerKeyHex())) },
        onDismiss = { showAbout = false }
    )
}

// ── cards ────────────────────────────────────────────────────────────────────

@Composable
private fun BalanceCard(snap: WalletSnapshot?, onSend: () -> Unit, onReceive: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = ThunderDeepBlue)) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("الرصيد  Balance", color = ThunderGray, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text((snap?.balanceMilli ?: 0L).fmtSDG(), color = ThunderWhite, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
            val pend = snap?.pendingIncomingMilli ?: 0L
            if (pend > 0) {
                Spacer(Modifier.height(4.dp))
                Text("⏳ +${pend.fmtSDG()} قيد التحقق  unverified, waiting to sync", color = ThunderElectric, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
            if (snap?.frozen == true) {
                Spacer(Modifier.height(6.dp))
                Text("⛔ هذه المحفظة مجمّدة بسبب إنفاق مزدوج  This wallet is frozen (double-spend proof)", color = ThunderRed, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onSend, shape = RoundedCornerShape(50), modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = ThunderElectric)) { Text("إرسال  Send", color = ThunderBlack, fontWeight = FontWeight.Bold) }
                OutlinedButton(onClick = onReceive, shape = RoundedCornerShape(50), modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, ThunderElectric)) { Text("استلام  Receive", color = ThunderElectric, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun NearbyCard(status: com.thunder.agi.node.NodeStatus, serviceOn: Boolean, onEnable: () -> Unit, onService: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = ThunderDeepBlue)) {
        Column(Modifier.padding(14.dp)) {
            Text("الدفع القريب بدون اتصال  Nearby payments (no connection)", color = ThunderWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Text("${if (status.bleScanning) "✅" else "⛔"} Bluetooth   " +
                "${if (status.awareActive) "✅" else if (status.awareSupported) "⏸" else "—"} WiFi Aware   " +
                "🔗 ${status.gossipPeers} peers",
                color = ThunderGray, fontSize = 12.sp)
            Text(if (status.hardwareKey) "🔐 مفتاح التوقيع داخل شريحة الأمان  Signing key is in secure hardware"
                else "⚠ مفتاح التوقيع برمجي  Signing key is software-only on this phone", color = if (status.hardwareKey) ThunderGreen else ThunderRed, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onEnable, modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, ThunderMidBlue)) {
                Text("تفعيل  Enable / refresh", color = ThunderElectric)
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("البقاء متصلاً في الخلفية  Stay online in background", color = ThunderGray, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Switch(checked = serviceOn, onCheckedChange = onService)
            }
        }
    }
}

@Composable
private fun IssuerCard(onMint: (to: String, milli: Long, ref: String, done: () -> Unit) -> Unit) {
    val ctx = LocalContext.current
    val sender = remember { WhatsAppCloudSender(ctx) }
    var to by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var ref by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pid by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var cloud by remember { mutableStateOf(sender.isConfigured) }
    val milli = parseSdg(amount)

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = ThunderDeepBlue),
        border = BorderStroke(1.dp, ThunderElectric)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🏦 لوحة المُصدر  Issuer panel", color = ThunderElectric, fontWeight = FontWeight.Bold)
            Text("أصدر رصيداً فقط بعد التحقق من الإيصال البنكي بنفسك. Mint only after YOU verified the bank receipt.", color = ThunderGray, fontSize = 12.sp)
            OutlinedTextField(to, { to = it.trim() }, label = { Text("عنوان المستلم  Recipient address") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), colors = fieldColors())
            OutlinedTextField(amount, { amount = it }, label = { Text("المبلغ SDG  Amount") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(), colors = fieldColors())
            OutlinedTextField(ref, { ref = it }, label = { Text("رقم العملية البنكية  Bank receipt no. (unique)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), colors = fieldColors())
            Button(
                onClick = { busy = true; onMint(to, milli ?: 0L, ref) { busy = false } },
                enabled = !busy && WalletAddress.isValid(to) && (milli ?: 0L) > 0 && ref.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = ThunderElectric), modifier = Modifier.fillMaxWidth()
            ) { Text("إصدار  Mint", color = ThunderBlack, fontWeight = FontWeight.Bold) }

            HorizontalDivider(color = ThunderMidBlue)
            Text("واتساب Cloud API (اختياري)  WhatsApp Cloud API (optional, operator phone only)", color = ThunderGray, fontSize = 11.sp)
            if (cloud) {
                Text("✅ configured — token is stored encrypted on this phone", color = ThunderGreen, fontSize = 12.sp)
                TextButton(onClick = { sender.clear(); cloud = false }) { Text("Remove", color = ThunderRed) }
            } else {
                OutlinedTextField(pid, { pid = it }, label = { Text("Phone number ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
                OutlinedTextField(token, { token = it }, label = { Text("Access token") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), colors = fieldColors())
                TextButton(onClick = { sender.configure(pid, token); token = ""; cloud = sender.isConfigured },
                    enabled = pid.isNotBlank() && token.isNotBlank()) { Text("Save", color = ThunderElectric) }
            }
            if (cloud) Text("After minting, use the share dialog; with the API configured it can also be sent automatically.", color = ThunderGray, fontSize = 11.sp)
        }
    }
}

// ── list row ─────────────────────────────────────────────────────────────────

@Composable
private fun TxRow(s: StoredTx, me: String, name: (String) -> String, onClick: () -> Unit) {
    val tx = s.tx
    val deposit = tx.isGenesis
    val sent = tx.from == me && !deposit
    val color = if (sent) ThunderRed else ThunderGreen
    val who = if (deposit) "🏦 إيداع بنكي  Bank deposit" else name(if (sent) tx.to else tx.from)
    val fmt = remember { SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()) }
    Card(Modifier.fillMaxWidth().clickable { onClick() }, shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = ThunderDeepBlue)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Text(if (deposit) "🏦" else if (sent) "↑" else "↓", color = color, fontSize = 18.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(who, color = ThunderWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (tx.memo.isNotBlank() && !deposit) Text(tx.memo, color = ThunderGray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(fmt.format(Date(tx.timestampSec * 1000)), color = ThunderGray, fontSize = 11.sp)
            }
            Text("${if (sent) "−" else "+"}${tx.amountMilli.fmtSDG()}", color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

// ── dialogs ──────────────────────────────────────────────────────────────────

@Composable
private fun SendDialog(peers: Map<String, String>, onDismiss: () -> Unit, onConfirm: (String, Long, String) -> Unit) {
    var to by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var memo by remember { mutableStateOf("") }
    var picker by remember { mutableStateOf(false) }
    val milli = parseSdg(amount)
    val valid = WalletAddress.isValid(to)
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = ThunderDeepBlue,
        title = { Text("إرسال  Send SDG", color = ThunderWhite) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(to, { to = it.trim() }, label = { Text("عنوان المستلم  To address (S…)") }, singleLine = true,
                    isError = to.isNotEmpty() && !valid, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
                if (peers.isNotEmpty()) {
                    TextButton(onClick = { picker = !picker }) { Text("👥 أجهزة قريبة  Nearby people (${peers.size})", color = ThunderElectric, fontSize = 12.sp) }
                    if (picker) peers.entries.take(8).forEach { (addr, nm) ->
                        Text("${nm.ifBlank { "?" }}  ${WalletAddress.shortForm(addr)}", color = ThunderWhite, fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().clickable { to = addr; picker = false }.padding(vertical = 6.dp))
                    }
                }
                OutlinedTextField(amount, { amount = it }, label = { Text("المبلغ  Amount (SDG, max 3 decimals)") }, singleLine = true,
                    isError = amount.isNotEmpty() && (milli == null || milli <= 0),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(), colors = fieldColors())
                OutlinedTextField(memo, { if (it.toByteArray().size <= TxWire.MAX_MEMO_BYTES) memo = it }, label = { Text("ملاحظة  Note (public, ≤48 bytes)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
                Text("⚠ الملاحظة والمبلغ يراها كل من يملك السجل.  Notes and amounts are visible to everyone who holds the ledger.", color = ThunderGray, fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(to, milli ?: 0L, memo) }, enabled = valid && (milli ?: 0L) > 0) {
                Text("تأكيد  Confirm", color = ThunderElectric)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء  Cancel", color = ThunderGray) } }
    )
}

@Composable
private fun AddressDialog(address: String, name: String, onDismiss: () -> Unit, onCopy: () -> Unit, onShare: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = ThunderDeepBlue,
        title = { Text("عنوان محفظتك  Your address", color = ThunderWhite) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (name.isNotBlank()) Text(name, color = ThunderElectric, fontWeight = FontWeight.Bold)
                Text(address, color = ThunderElectric, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                Text("شاركه مع من يريد أن يرسل لك. العنوان دائم ويحتوي على بصمة مفتاحك.  Share it with anyone who wants to pay you. It never changes.",
                    color = ThunderGray, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onCopy(); onDismiss() }) { Text("نسخ  Copy", color = ThunderElectric) } },
        dismissButton = { Row { TextButton(onClick = onShare) { Text("مشاركة  Share", color = ThunderElectric) }; TextButton(onClick = onDismiss) { Text("إغلاق", color = ThunderGray) } } }
    )
}

/** After sending/minting: the payment already left by Bluetooth, WiFi Aware and gossip; WhatsApp reaches people who are far away. */
@Composable
private fun ShareDialog(tx: Transaction, ctx: android.content.Context, copy: (AnnotatedString) -> Unit, onDismiss: () -> Unit) {
    var phone by remember { mutableStateOf("") }
    val text = WhatsAppShare.message(tx)
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = ThunderDeepBlue,
        title = { Text("✅ ${tx.amountMilli.fmtSDG()}", color = ThunderGreen) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("تم توقيع الدفعة وبثّها للأجهزة القريبة (بلوتوث / WiFi Aware / شبكة محلية).  Signed and broadcast to nearby phones.", color = ThunderGray, fontSize = 12.sp)
                Text("للشخص البعيد: أرسل الرسالة عبر واتساب (تحتوي الدفعة كاملة).  For someone far away, send it by WhatsApp:", color = ThunderWhite, fontSize = 12.sp)
                OutlinedTextField(phone, { phone = it }, label = { Text("رقم واتساب (اختياري)  WhatsApp number, intl. format") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(), colors = fieldColors())
            }
        },
        confirmButton = { TextButton(onClick = { WhatsAppShare.open(ctx, phone, text) }) { Text("واتساب  WhatsApp", color = ThunderElectric) } },
        dismissButton = {
            Row {
                TextButton(onClick = { copy(AnnotatedString(text)) }) { Text("نسخ  Copy", color = ThunderElectric) }
                TextButton(onClick = { WhatsAppShare.share(ctx, text) }) { Text("مشاركة", color = ThunderElectric) }
                TextButton(onClick = onDismiss) { Text("إغلاق", color = ThunderGray) }
            }
        }
    )
}

@Composable
private fun DetailDialog(tx: Transaction, me: String, name: (String) -> String, ctx: android.content.Context,
                         copy: (AnnotatedString) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = ThunderDeepBlue,
        title = { Text(tx.amountMilli.fmtSDG(), color = ThunderWhite) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("من  From: ${if (tx.isGenesis) "🏦 Issuer" else name(tx.from)}", color = ThunderWhite, fontSize = 13.sp)
                Text("إلى  To: ${name(tx.to)}", color = ThunderWhite, fontSize = 13.sp)
                if (tx.memo.isNotBlank()) Text("📝 ${tx.memo}", color = ThunderGray, fontSize = 13.sp)
                Text("#${tx.nonce} · ${tx.txid.take(16)}…", color = ThunderGray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text(if (tx.from == me) "✔ signed by you in secure hardware" else "✔ signature verified by this phone", color = ThunderGreen, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = { WhatsAppShare.open(ctx, null, WhatsAppShare.message(tx)) }) { Text("واتساب", color = ThunderElectric) } },
        dismissButton = {
            Row {
                TextButton(onClick = { copy(AnnotatedString(WhatsAppShare.message(tx))) }) { Text("نسخ  Copy", color = ThunderElectric) }
                TextButton(onClick = onDismiss) { Text("إغلاق", color = ThunderGray) }
            }
        }
    )
}

@Composable
private fun AboutDialog(address: String, name: String, keyHex: String, hardware: Boolean, ledgerSize: Int, pending: Int,
                        onName: (String) -> Unit, onCopyKey: () -> Unit, onDismiss: () -> Unit) {
    var nm by remember { mutableStateOf(name) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = ThunderDeepBlue,
        title = { Text("ⓘ Wallet", color = ThunderWhite) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(nm, { nm = it }, label = { Text("اسمك الظاهر  Display name (shown to peers)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = fieldColors())
                Text("Address: $address", color = ThunderElectric, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text("Signing public key: $keyHex", color = ThunderGray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text("Hardware-backed key: ${if (hardware) "yes" else "NO"} · ledger: $ledgerSize txs · waiting: $pending", color = ThunderGray, fontSize = 11.sp)
                Text("المفتاح العام ليس سرّاً. يحتاجه المُصدر لتفعيل صلاحية الإصدار (MintAuthority).  The public key is not a secret; the issuer pastes it into MintAuthority.",
                    color = ThunderGray, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onName(nm); onDismiss() }) { Text("حفظ  Save", color = ThunderElectric) } },
        dismissButton = { Row { TextButton(onClick = onCopyKey) { Text("Copy key", color = ThunderElectric) }; TextButton(onClick = onDismiss) { Text("إغلاق", color = ThunderGray) } } }
    )
}
