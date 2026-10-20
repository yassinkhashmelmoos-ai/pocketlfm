package com.thunder.agi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thunder.agi.license.LicenseValidator
import com.thunder.agi.subscription.SubscriptionManager
import com.thunder.agi.ui.AboutScreen
import com.thunder.agi.ui.ActivationScreen
import com.thunder.agi.ui.PaymentScreen
import com.thunder.agi.ui.components.ChatScreen
import com.thunder.agi.ui.theme.ThunderAGITheme
import com.thunder.agi.viewmodel.ChatViewModel

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

    // NOTE: no auto-granted trial, by design — access is always manual: either a
    // username+key issued through LicenseValidator (the same path whether someone
    // pays or is comped — "paid" is just whatever expiry date the key was signed
    // with), or a verified Bankak receipt below. registerUser() / the 30-day
    // trial it starts is intentionally never called from here.
    val sub         = remember { SubscriptionManager(context) }
    var licenseInfo by remember { mutableStateOf(LicenseValidator.check(context)) }
    var subState    by remember { mutableStateOf(sub.getState()) }

    // FIX: use derivedStateOf so UI reacts immediately when subState changes after payment.
    // TRIAL_ACTIVE stays in this check (not a free trial — see note above): applying a
    // reset key on PaymentScreen below sets TRIAL_START without touching PAID_UNTIL, so
    // that's the state a reset-key user is in for their next 30 days.
    val isActivated by remember {
        derivedStateOf {
            licenseInfo.status == LicenseValidator.Status.VALID ||
            subState == SubscriptionManager.SubscriptionState.PAID_ACTIVE  ||
            subState == SubscriptionManager.SubscriptionState.ADMIN        ||
            subState == SubscriptionManager.SubscriptionState.TRIAL_ACTIVE
        }
    }

    var showAbout     by remember { mutableStateOf(false) }
    var verifyMessage by remember { mutableStateOf("") }
    var isVerifying   by remember { mutableStateOf(false) }

    if (showAbout) {
        AboutScreen(onDismiss = { showAbout = false })
        return
    }

    if (!isActivated) {
        // FIX: PaymentScreen existed but nothing ever showed it. TRIAL_EXPIRED is
        // reached by a receipt-paid or reset-key user whose 30 days ran out — they
        // used to fall through to ActivationScreen, which has no "access ended"
        // message and no reset-key option. Every other non-activated state still
        // goes to ActivationScreen below.
        if (subState == SubscriptionManager.SubscriptionState.TRIAL_EXPIRED) {
            PaymentScreen(
                username = sub.getUsername(),
                onVerifyReceipt = { uri, process, date ->
                    isVerifying   = true
                    val result    = sub.verifyReceipt(uri, process, date)
                    verifyMessage = result.reason
                    isVerifying   = false
                    if (result.valid) subState = sub.getState()
                },
                onResetKey = { key ->
                    verifyMessage = if (sub.applyResetKey(key)) {
                        subState = sub.getState()
                        "✅ تم إعادة التفعيل! Reactivated!"
                    } else {
                        "مفتاح غير صحيح — Invalid reset key"
                    }
                },
                verificationMessage = verifyMessage,
                isVerifying         = isVerifying
            )
            return
        }
        ActivationScreen(
            onActivateLicense = { username, key ->
                val ok = LicenseValidator.activate(context, username, key)
                if (ok) licenseInfo = LicenseValidator.check(context)
                ok
            },
            onVerifyReceipt = { uri, process, date ->
                isVerifying   = true
                val result    = sub.verifyReceipt(uri, process, date)
                verifyMessage = result.reason
                isVerifying   = false
                if (result.valid) subState = sub.getState()
            },
            receiptVerificationMessage = verifyMessage,
            isVerifyingReceipt         = isVerifying
        )
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
