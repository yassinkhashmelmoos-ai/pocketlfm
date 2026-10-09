package com.thunder.agi

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thunder.agi.license.LicenseValidator
import com.thunder.agi.subscription.SubscriptionManager
import com.thunder.agi.ui.AboutScreen
import com.thunder.agi.ui.ActivationScreen
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

    // ── License check: BOTH paths must be checked ────────────────────────────
    // Path 1: HMAC-signed license key (LicenseValidator)
    // Path 2: Bank of Khartoum receipt (SubscriptionManager — keeps AI receipt check)
    val sub = remember { SubscriptionManager(context) }
    var licenseInfo by remember { mutableStateOf(LicenseValidator.check(context)) }
    var subState    by remember { mutableStateOf(sub.getState()) }

    // User is allowed in if EITHER path is valid
    val isActivated = licenseInfo.status == LicenseValidator.Status.VALID ||
            subState == SubscriptionManager.SubscriptionState.PAID_ACTIVE ||
            subState == SubscriptionManager.SubscriptionState.ADMIN

    var showAbout      by remember { mutableStateOf(false) }
    var verifyMessage  by remember { mutableStateOf("") }
    var isVerifying    by remember { mutableStateOf(false) }

    if (showAbout) {
        AboutScreen(onDismiss = { showAbout = false })
        return
    }

    if (!isActivated) {
        // ── Not activated: show activation screen (first launch AND every launch) ─
        ActivationScreen(
            onActivateLicense = { username, key ->
                val ok = LicenseValidator.activate(context, username, key)
                if (ok) licenseInfo = LicenseValidator.check(context)
                ok
            },
            onVerifyReceipt = { uri, process, date ->
                isVerifying = true
                val result = sub.verifyReceipt(uri, process, date)
                verifyMessage = result.reason
                isVerifying = false
                if (result.valid) subState = sub.getState()
            },
            receiptVerificationMessage = verifyMessage,
            isVerifyingReceipt = isVerifying
        )
        return
    }

    // ── Activated: show main app ─────────────────────────────────────────────
    val vm: ChatViewModel = viewModel()
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    // Pass the stored username (from whichever path activated)
    val activatedUsername = licenseInfo.username.ifBlank { sub.getUsername() }
    LaunchedEffect(activatedUsername) {
        if (activatedUsername.isNotBlank()) vm.saveUserName(activatedUsername)
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
        trialDaysRemaining   = licenseInfo.daysRemaining,
        isAdmin              = subState == SubscriptionManager.SubscriptionState.ADMIN
    )
}
