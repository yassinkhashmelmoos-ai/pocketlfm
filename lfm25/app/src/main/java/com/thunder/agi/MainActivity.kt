package com.thunder.agi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
                        onSaveUserName       = vm::saveUserName,
                        onSaveSystemPrompt   = vm::saveSystemPrompt,
                        currentSystemPrompt  = vm::fetchSystemPrompt,
                        onClearCrash         = vm::clearCrashLog,
                        onToggleImageGen     = vm::toggleImageGen,
                        onExportResponse     = vm::exportLastResponse,
                        onExportConversation = vm::exportConversation,
                        onClearExport        = vm::clearExportResult
                    )
                }
            }
        }
    }
}
