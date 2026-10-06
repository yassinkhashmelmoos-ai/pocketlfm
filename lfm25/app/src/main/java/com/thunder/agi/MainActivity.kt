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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thunder.agi.subscription.SubscriptionManager
import com.thunder.agi.ui.AboutScreen
import com.thunder.agi.ui.OnboardingScreen
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
    val sub = remember { SubscriptionManager(context) }
    var subState by remember { mutableStateOf(sub.getState()) }
    var showAbout by remember { mutableStateOf(false) }
    var verifyMessage by remember { mutableStateOf("") }
    var isVerifying by remember { mutableStateOf(false) }

    // About screen overlay
    if (showAbout) {
        AboutScreen(onDismiss = { showAbout = false })
        return
    }

    when (subState) {
        // ── First launch: ask for username ────────────────────────────────────
        SubscriptionManager.SubscriptionState.FIRST_LAUNCH -> {
            OnboardingScreen(
                onRegister = { username ->
                    sub.registerUser(username)
                    subState = sub.getState()
                }
            )
        }

        // ── Trial expired: show payment screen ────────────────────────────────
        SubscriptionManager.SubscriptionState.TRIAL_EXPIRED -> {
            PaymentScreen(
                username = sub.getUsername(),
                onVerifyReceipt = { uri, process, date ->
                    isVerifying = true
                    val result = sub.verifyReceipt(uri, process, date)
                    verifyMessage = result.reason
                    isVerifying = false
                    if (result.valid) subState = sub.getState()
                },
                onResetKey = { key ->
                    if (sub.applyResetKey(key)) {
                        subState = sub.getState()
                        Toast.makeText(context, "تم تفعيل شهر جديد! ⚡", Toast.LENGTH_LONG).show()
                    } else {
                        verifyMessage = "مفتاح غير صحيح"
                    }
                },
                verificationMessage = verifyMessage,
                isVerifying = isVerifying
            )
        }

        // ── Active (trial or paid or admin): show main app ────────────────────
        SubscriptionManager.SubscriptionState.TRIAL_ACTIVE,
        SubscriptionManager.SubscriptionState.PAID_ACTIVE,
        SubscriptionManager.SubscriptionState.ADMIN -> {
            val vm: ChatViewModel = viewModel()
            val ui by vm.uiState.collectAsStateWithLifecycle()
            val ctx = LocalContext.current

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
                // Show days remaining in drawer if trial
                trialDaysRemaining   = if (subState == SubscriptionManager.SubscriptionState.TRIAL_ACTIVE)
                    sub.getDaysRemaining() else -1L,
                isAdmin              = subState == SubscriptionManager.SubscriptionState.ADMIN
            )
        }
    }
}
