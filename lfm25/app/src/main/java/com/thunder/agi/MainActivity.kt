package com.thunder.agi

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thunder.agi.license.LicenseValidator
import com.thunder.agi.node.ThunderNav
import com.thunder.agi.node.ThunderService
import com.thunder.agi.subscription.AccessGate
import com.thunder.agi.subscription.SubscriptionManager
import com.thunder.agi.ui.AboutScreen
import com.thunder.agi.ui.ActivationScreen
import com.thunder.agi.ui.PaymentScreen
import com.thunder.agi.ui.WalletScreen
import com.thunder.agi.ui.components.ChatScreen
import com.thunder.agi.ui.theme.ThunderAGITheme
import com.thunder.agi.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ThunderAGITheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ThunderAGIApp()
                }
            }
        }
    }
}

@Composable
fun ThunderAGIApp() {
    val context = LocalContext.current

    val scope   = rememberCoroutineScope()

    // NOTE: no auto-granted trial, by design — access is always manual: either a
    // username+key issued through LicenseValidator (the same path whether someone
    // pays or is comped — "paid" is just whatever expiry date the key was signed
    // with), or a verified Bankak receipt. registerUser() / the 30-day trial it
    // starts is intentionally never called from here.
    //
    // The app now OPENS FOR EVERYONE (first launch, an ended month, never activated) and the chat
    // history is always readable. Access is only asked for when the user presses send: ChatViewModel
    // then raises ui.paywallRequested and the activation pop-up at the bottom of this function
    // appears, with a "Not now" button that simply returns to the chat.
    val sub         = remember { SubscriptionManager(context) }
    var licenseInfo by remember { mutableStateOf(LicenseValidator.check(context)) }
    var subState    by remember { mutableStateOf(sub.getState()) }

    var showAbout     by remember { mutableStateOf(false) }
    var verifyMessage by remember { mutableStateOf("") }
    var isVerifying   by remember { mutableStateOf(false) }

    // LicenseValidator.check() is a slow Keystore call, so it runs here, and only its RESULT is
    // handed to AccessGate, which the ViewModel asks at the moment the user presses send.
    LaunchedEffect(licenseInfo) { AccessGate.updateLicense(licenseInfo) }

    if (showAbout) {
        AboutScreen(onDismiss = { showAbout = false })
        return
    }

    val vm: ChatViewModel = viewModel()
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val peerSync = remember { com.thunder.agi.p2p.PeerSync(ctx) }

    DisposableEffect(Unit) {
        peerSync.register()
        onDispose { peerSync.unregister() }
    }

    // FIX: only seed username once — LaunchedEffect(Unit) prevents overwriting
    // the user's manually-changed name on every recomposition
    val activatedUsername = licenseInfo.username.ifBlank { sub.getUsername() }
    LaunchedEffect(Unit) {
        if (activatedUsername.isNotBlank() && ui.userName == "You") {
            vm.saveUserName(activatedUsername)
        }
    }

    // Thunder wallet: resume the opt-in background service if the user enabled it before
    LaunchedEffect(Unit) { ThunderService.restoreIfEnabled(context) }

    // ── activation pop-up wiring ─────────────────────────────────────────────
    LaunchedEffect(ui.paywallRequested) {
        // the month may have ended while the app was open: pick the right variant, drop stale text
        if (ui.paywallRequested) { subState = sub.getState(); verifyMessage = "" }
    }

    val verifyReceipt: (Uri, String) -> Unit = { uri, process ->
        if (!isVerifying) {
            isVerifying   = true
            verifyMessage = ""
            scope.launch {
                val result = sub.verifyReceipt(uri, process)     // decoding + OCR run off the main thread
                verifyMessage = result.reason
                isVerifying   = false
                if (result.valid) {
                    subState = sub.getState()
                    delay(2500)                                  // let the user read what was verified
                    vm.dismissPaywall()
                }
            }
        }
    }
    val activateLicense: (String, String) -> Boolean = { username, key ->
        val ok = LicenseValidator.activate(context, username, key)
        if (ok) {
            licenseInfo = LicenseValidator.check(context)
            AccessGate.updateLicense(licenseInfo)
            vm.dismissPaywall()
            vm.showSnackbar("✅ تم التفعيل — Activated")
        }
        ok
    }
    val applyResetKey: (String) -> Unit = { key ->
        if (sub.applyResetKey(key)) {
            subState = sub.getState()
            vm.dismissPaywall()
            vm.showSnackbar("✅ تم إعادة التفعيل! Reactivated!")
        } else {
            verifyMessage = "مفتاح غير صحيح — Invalid reset key"
        }
    }

    Box(Modifier.fillMaxSize()) {
    if (ThunderNav.showWallet.value) {
        WalletScreen(onDismiss = { ThunderNav.showWallet.value = false })
    } else {
    ChatScreen(
        uiState              = ui,
        onInputChanged       = vm::onInputChanged,
        onSendMessage        = vm::sendMessage,
        onClearChat          = vm::clearChat,
        onRetryLoadModel     = vm::retryLoadModel,
        onNewSession         = vm::newSession,
        onSwitchSession      = vm::switchSession,
        onDeleteSession      = vm::deleteSession,
        onRenameSession      = vm::renameSession,
        onToggleDrawer       = vm::toggleDrawer,
        onStartVoice         = { vm.startVoice(ctx) },
        onStopVoice          = vm::stopVoice,
        onThumbsUp           = { vm.submitFeedback(it, true) },
        onThumbsDown         = { vm.submitFeedback(it, false) },
        onDeleteMessage      = vm::deleteMessage,
        onShareImage         = vm::shareImage,
        onSaveImage          = vm::saveImageToGallery,
        onImportFineTune     = vm::importFineTune,
        onExportFeedback     = vm::exportFeedbackLog,
        onClearSnackbar      = vm::clearSnackbar,
        onToggleWebSearch    = vm::toggleWebSearch,
        onSetPendingImage    = vm::setPendingImage,
        onToggleSettings     = vm::toggleSettings,
        onToggleImageGen     = vm::toggleImageGen,
        onSaveUserName       = vm::saveUserName,
        onSaveSystemPrompt   = vm::saveSystemPrompt,
        currentSystemPrompt  = vm::fetchSystemPrompt,
        onClearCrash         = vm::clearCrashLog,
        onExportResponse     = vm::exportLastResponse,
        onExportConversation = vm::exportConversation,
        onClearExport        = vm::clearExportResult,
        onShowAbout          = { showAbout = true },
        onGetPeerSync        = { peerSync },
        // FIX: wire edit+resend callback
        onEditMessage        = vm::editAndResend,
        // FIX: wire feedback log dialog dismiss
        onDismissFeedbackDialog = vm::dismissFeedbackLogDialog,
        // FIX: this always read licenseInfo.daysRemaining, which only has a real
        // value for a paid license-key activation (0 otherwise) — so every trial
        // user, who now reaches this screen via the fix above, would see "0 days
        // remaining" regardless of their actual trial. Use SubscriptionManager's
        // own count while a trial is active.
        trialDaysRemaining   = if (subState == SubscriptionManager.SubscriptionState.TRIAL_ACTIVE)
            sub.getDaysRemaining() else licenseInfo.daysRemaining,
        isAdmin              = subState == SubscriptionManager.SubscriptionState.ADMIN
    )
    }

    // The two activation options you already built, shown ON TOP of the chat only after the user
    // presses send without access. Back or "Not now" returns to the chat history; the unsent text
    // is still in the input box.
    if (ui.paywallRequested) {
        BackHandler { vm.dismissPaywall() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (subState == SubscriptionManager.SubscriptionState.TRIAL_EXPIRED) {
                PaymentScreen(
                    username            = sub.getUsername(),
                    onVerifyReceipt     = verifyReceipt,
                    onResetKey          = applyResetKey,
                    verificationMessage = verifyMessage,
                    isVerifying         = isVerifying,
                    onSkip              = vm::dismissPaywall
                )
            } else {
                ActivationScreen(
                    onActivateLicense          = activateLicense,
                    onVerifyReceipt            = verifyReceipt,
                    receiptVerificationMessage = verifyMessage,
                    isVerifyingReceipt         = isVerifying,
                    onSkip                     = vm::dismissPaywall
                )
            }
        }
    }
    }
}
