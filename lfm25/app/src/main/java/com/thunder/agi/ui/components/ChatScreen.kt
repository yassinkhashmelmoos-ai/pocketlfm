package com.thunder.agi.ui.components

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import com.thunder.agi.ui.theme.*
import com.thunder.agi.viewmodel.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    uiState:             ChatUiState,
    onInputChanged:      (String) -> Unit,
    onSendMessage:       () -> Unit,
    onClearChat:         () -> Unit,
    onRetryLoadModel:    () -> Unit,
    onNewSession:        () -> Unit,
    onSwitchSession:     (String) -> Unit,
    onDeleteSession:     (String) -> Unit,
    onRenameSession:     (String, String) -> Unit,
    onToggleDrawer:      () -> Unit,
    onStartVoice:        () -> Unit,
    onStopVoice:         () -> Unit,
    onThumbsUp:          (String) -> Unit,
    onThumbsDown:        (String) -> Unit,
    onDeleteMessage:     (String) -> Unit,
    onImportFineTune:    (Uri) -> Unit,
    onExportFeedback:    () -> Unit,
    onClearSnackbar:     () -> Unit,
    onToggleWebSearch:   () -> Unit,
    onSetPendingImage:   (Uri?) -> Unit,
    onToggleSettings:    () -> Unit,
    onSaveUserName:      (String) -> Unit,
    onSaveSystemPrompt:  (String) -> Unit,
    currentSystemPrompt: () -> String,
    onClearCrash:        () -> Unit,
    onToggleImageGen:    () -> Unit,
    onExportResponse:    () -> Unit,
    onExportConversation:() -> Unit,
    onClearExport:       () -> Unit,
    onShowAbout:         () -> Unit = {},
    onGetPeerSync:       () -> com.thunder.agi.p2p.PeerSync,
    onEditMessage:       ((String, String) -> Unit)? = null,   // FIX: edit+resend
    onDismissFeedbackDialog: (() -> Unit)? = null,             // FIX: feedback dialog
    trialDaysRemaining:  Long    = -1L,
    isAdmin:             Boolean = false,
    modifier:            Modifier = Modifier
) {
    val listState    = rememberLazyListState()
    val scope        = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val context      = LocalContext.current

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        onSetPendingImage(uri)
    }
    val ftPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onImportFineTune(it) }
    }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) onStartVoice()
    }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty())
            scope.launch { listState.animateScrollToItem(uiState.messages.size - 1) }
    }

    LaunchedEffect(uiState.snackbar) {
        uiState.snackbar?.let { snackbarHost.showSnackbar(it); onClearSnackbar() }
    }

    // FIX: Feedback log dialog (was a snackbar hidden behind Settings panel)
    uiState.feedbackLogDialog?.let { msg ->
        AlertDialog(
            onDismissRequest = { onDismissFeedbackDialog?.invoke() },
            title = { Text("📄 Feedback Log", color = ThunderWhite) },
            text = { Text(msg, color = ThunderGray, style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                val clipboard = LocalClipboardManager.current
                TextButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(msg))
                    onDismissFeedbackDialog?.invoke()
                }) { Text("Copy Path", color = ThunderElectric) }
            },
            dismissButton = {
                TextButton(onClick = { onDismissFeedbackDialog?.invoke() }) {
                    Text("Close", color = ThunderGray)
                }
            },
            containerColor = ThunderDeepBlue
        )
    }

    // Export result dialog
    uiState.exportResult?.let { result ->
        AlertDialog(
            onDismissRequest = onClearExport,
            title = { Text("✅ Exported", color = ThunderWhite) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(result.file.name, color = ThunderElectric,
                        style = MaterialTheme.typography.bodyMedium)
                    Text("Size: ${result.sizeKb}KB • Format: ${result.format.extension.uppercase()}",
                        color = ThunderGray, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = result.format.mimeType
                        putExtra(Intent.EXTRA_STREAM, result.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share file"))
                    onClearExport()
                }) { Text("Share / Save", color = ThunderElectric) }
            },
            dismissButton = { TextButton(onClick = onClearExport) { Text("Close", color = ThunderGray) } },
            containerColor = ThunderDeepBlue
        )
    }

    // Crash reporter dialog
    uiState.crashLog?.let { crash ->
        val clipboard = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = onClearCrash,
            title = { Text("⚠️ Error Report") },
            text = {
                Column {
                    Text("An error occurred. Copy and send to developer:",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(crash, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = ThunderRed)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(crash))
                    onClearCrash()
                }) { Text("Copy & Close") }
            },
            dismissButton = { TextButton(onClick = onClearCrash) { Text("Dismiss") } }
        )
    }

    if (uiState.showSettings) {
        SettingsSheet(
            userName            = uiState.userName,
            systemPrompt        = currentSystemPrompt(),
            onSaveUserName      = onSaveUserName,
            onSaveSystemPrompt  = onSaveSystemPrompt,
            onExportFeedback    = onExportFeedback,
            onImportFineTune    = { ftPicker.launch("*/*") },
            onShowAbout         = onShowAbout,
            onGetPeerSync       = onGetPeerSync,
            onDismiss           = onToggleSettings
        )
        return
    }

    if (uiState.showSessionDrawer) {
        SessionDrawer(
            sessions  = uiState.sessions,
            currentId = uiState.currentSessionId,
            onNew     = onNewSession,
            onSwitch  = onSwitchSession,
            onDelete  = onDeleteSession,
            onRename  = onRenameSession,
            onDismiss = onToggleDrawer
        )
        return
    }

    Scaffold(
        snackbarHost  = { SnackbarHost(snackbarHost) },
        containerColor = ThunderBlack,
        topBar = {
            val title = uiState.sessions.find { it.id == uiState.currentSessionId }?.title ?: "Thunder AGI"
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onToggleDrawer) {
                        Icon(Icons.Default.Menu, "Sessions", tint = ThunderElectric)
                    }
                },
                title = {
                    Column {
                        Text("Thunder AGI", style = MaterialTheme.typography.labelSmall, color = ThunderElectric)
                        Text(title, style = MaterialTheme.typography.titleSmall,
                            color = ThunderWhite, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    IconButton(onClick = onToggleImageGen) {
                        Icon(Icons.Default.Image, "Image generation",
                            tint = if (uiState.imageGenEnabled) ThunderPurple else ThunderGray)
                    }
                    IconButton(onClick = onToggleWebSearch) {
                        Icon(Icons.Default.Search, "Web search",
                            tint = if (uiState.webSearchEnabled) ThunderElectric else ThunderGray)
                    }
                    if (uiState.messages.any { !it.isUser && !it.isLoading }) {
                        IconButton(onClick = onExportResponse) {
                            Icon(Icons.Default.SaveAlt, "Export response", tint = ThunderGray)
                        }
                    }
                    IconButton(onClick = onNewSession) {
                        Icon(Icons.Default.Add, "New chat", tint = ThunderGray)
                    }
                    IconButton(onClick = onToggleSettings) {
                        Icon(Icons.Default.Settings, "Settings", tint = ThunderGray)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor   = ThunderDeepBlue,
                    titleContentColor = ThunderWhite
                )
            )
        },
        bottomBar = {
            if (uiState.modelLoaded) {
                InputSection(
                    text        = uiState.inputText,
                    generating  = uiState.isGenerating,
                    recording   = uiState.isRecording,
                    webSearchOn = uiState.webSearchEnabled,
                    pendingImage = uiState.pendingImageUri,
                    onTextChange = onInputChanged,
                    onSend      = onSendMessage,
                    onAttach    = { imagePicker.launch("image/*") },
                    onRemoveImage = { onSetPendingImage(null) },
                    onMicPress  = { micPerm.launch(Manifest.permission.RECORD_AUDIO) },
                    modifier    = Modifier.imePadding()
                )
            }
        }
    ) { padding ->
        Box(modifier.fillMaxSize().padding(padding)) {
            when {
                uiState.isModelLoading -> LoadingView()
                !uiState.modelLoaded   -> ErrorView(uiState.error ?: "Unknown error", onRetryLoadModel)
                else -> MessageList(
                    messages        = uiState.messages,
                    listState       = listState,
                    userName        = uiState.userName,
                    onUp            = onThumbsUp,
                    onDown          = onThumbsDown,
                    onDeleteMessage = onDeleteMessage,
                    onEditMessage   = onEditMessage,
                    onClear         = if (uiState.messages.isNotEmpty()) onClearChat else null,
                    modifier        = Modifier.fillMaxSize()
                )
            }
        }
    }
}

// ── Settings Sheet ────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    userName:           String,
    systemPrompt:       String,
    onSaveUserName:     (String) -> Unit,
    onSaveSystemPrompt: (String) -> Unit,
    onExportFeedback:   () -> Unit,
    onImportFineTune:   () -> Unit,
    onShowAbout:        () -> Unit,
    onGetPeerSync:      () -> com.thunder.agi.p2p.PeerSync,
    onDismiss:          () -> Unit
) {
    var nameEdit by remember { mutableStateOf(userName) }

    Column(
        Modifier.fillMaxSize().background(ThunderDeepBlue).verticalScroll(rememberScrollState())
    ) {
        TopAppBar(
            title = { Text("Settings", color = ThunderWhite) },
            navigationIcon = {
                IconButton(onClick = {
                    onSaveUserName(nameEdit)
                    onDismiss()
                }) { Icon(Icons.Default.Check, null, tint = ThunderElectric) }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = ThunderDeepBlue)
        )
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {

            Text("Your Name", style = MaterialTheme.typography.labelMedium, color = ThunderGray)
            OutlinedTextField(
                value       = nameEdit,
                onValueChange = { nameEdit = it },
                modifier    = Modifier.fillMaxWidth(),
                singleLine  = true,
                colors      = outlinedTextFieldColors(),
                placeholder = { Text("e.g. Yassin") }
            )

            HorizontalDivider(color = ThunderMidBlue)
            Text("Fine-tuning", style = MaterialTheme.typography.labelMedium, color = ThunderGray)

            OutlinedButton(onClick = onImportFineTune, modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, ThunderElectric)) {
                Icon(Icons.Default.Upload, null, tint = ThunderElectric)
                Spacer(Modifier.width(8.dp))
                Text("Import Fine-tune (.gguf)", color = ThunderElectric)
            }

            // FIX: clicking this now shows a dialog with the path (was hidden snackbar)
            OutlinedButton(onClick = onExportFeedback, modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, ThunderGray)) {
                Icon(Icons.Default.Download, null, tint = ThunderGray)
                Spacer(Modifier.width(8.dp))
                Text("View Feedback Log Path", color = ThunderGray)
            }

            HorizontalDivider(color = ThunderMidBlue)

            // FIX: WhatsApp Sync note in Arabic + English
            Text("WhatsApp Sync  /  مزامنة واتساب",
                style = MaterialTheme.typography.labelMedium, color = ThunderGray)
            Text(
                "Create a WhatsApp chat named \"Thunder AGI\". Messages starting with [UPDATE] " +
                "are treated as model instructions; others are saved as training data.\n\n" +
                "أنشئ محادثة واتساب باسم \"Thunder AGI\". الرسائل التي تبدأ بـ [UPDATE] " +
                "تُعامَل كتعليمات للنموذج؛ وغيرها يُحفظ كبيانات تدريب.",
                style = MaterialTheme.typography.bodySmall, color = ThunderGray
            )

            HorizontalDivider(color = ThunderMidBlue)
            PeerSyncPanel(peerSync = onGetPeerSync())

            OutlinedButton(
                onClick = { onSaveUserName(nameEdit); onDismiss(); onShowAbout() },
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, ThunderElectric)
            ) {
                Icon(Icons.Default.Info, null, tint = ThunderElectric)
                Spacer(Modifier.width(8.dp))
                // FIX: bilingual About button
                Text("About • عن التطبيق", color = ThunderElectric)
            }
        }
    }
}

// ── Session Drawer ────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionDrawer(
    sessions:  List<ChatSession>,
    currentId: String?,
    onNew:     () -> Unit,
    onSwitch:  (String) -> Unit,
    onDelete:  (String) -> Unit,
    onRename:  (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var renamingId  by remember { mutableStateOf<String?>(null) }
    var renameText  by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(ThunderDeepBlue)) {
        TopAppBar(
            title = { Text("Chats", color = ThunderWhite) },
            navigationIcon = {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null, tint = ThunderGray) }
            },
            actions = {
                IconButton(onClick = onNew) { Icon(Icons.Default.Add, "New chat", tint = ThunderElectric) }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = ThunderDeepBlue)
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(sessions, key = { it.id }) { s ->
                val isActive   = s.id == currentId
                val isRenaming = renamingId == s.id
                Column(
                    Modifier.fillMaxWidth()
                        .background(if (isActive) ThunderMidBlue else Color.Transparent)
                        .clickable { onSwitch(s.id) }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    if (isRenaming) {
                        OutlinedTextField(
                            value         = renameText,
                            onValueChange = { renameText = it },
                            modifier      = Modifier.fillMaxWidth(),
                            singleLine    = true,
                            colors        = outlinedTextFieldColors(),
                            keyboardActions = KeyboardActions(onDone = {
                                onRename(s.id, renameText); renamingId = null
                            }),
                            trailingIcon = {
                                IconButton(onClick = { onRename(s.id, renameText); renamingId = null }) {
                                    Icon(Icons.Default.Check, null, tint = ThunderElectric)
                                }
                            }
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ChatBubbleOutline, null,
                                tint = if (isActive) ThunderElectric else ThunderGray,
                                modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(s.title, color = if (isActive) ThunderWhite else ThunderGray,
                                modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium)
                            IconButton(onClick = { renameText = s.title; renamingId = s.id },
                                modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Edit, null, tint = ThunderGray, modifier = Modifier.size(16.dp))
                            }
                            IconButton(onClick = { onDelete(s.id) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Delete, null, tint = ThunderRed, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
                HorizontalDivider(color = ThunderMidBlue.copy(alpha = 0.5f))
            }
        }
    }
}

// ── Message List ──────────────────────────────────────────────────────────────
@Composable
private fun MessageList(
    messages:       List<ChatMessage>,
    listState:      LazyListState,
    userName:       String,
    onUp:           (String) -> Unit,
    onDown:         (String) -> Unit,
    onDeleteMessage:(String) -> Unit,
    onEditMessage:  ((String, String) -> Unit)?,
    onClear:        (() -> Unit)?,
    modifier:       Modifier
) {
    if (messages.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("⚡", fontSize = 48.sp)
                Spacer(Modifier.height(16.dp))
                Text("Thunder AGI", style = MaterialTheme.typography.headlineSmall, color = ThunderElectric)
                Text("الرَّعد للذكاء العام المصطنع",
                    style = MaterialTheme.typography.bodyMedium, color = ThunderGray)
            }
        }
    } else {
        LazyColumn(
            state             = listState,
            modifier          = modifier,
            contentPadding    = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageBubble(msg, userName, onUp, onDown, onDeleteMessage, onEditMessage)
            }
            if (onClear != null) {
                item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(onClick = onClear) {
                            Icon(Icons.Default.Delete, null, tint = ThunderRed, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Clear conversation", color = ThunderRed,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

// ── Message Bubble ────────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    msg:            ChatMessage,
    userName:       String,
    onUp:           (String) -> Unit,
    onDown:         (String) -> Unit,
    onDeleteMessage:(String) -> Unit,
    onEditMessage:  ((String, String) -> Unit)?
) {
    val isUser    = msg.isUser
    val clipboard = LocalClipboardManager.current
    var showMenu      by remember { mutableStateOf(false) }
    var showTimestamp by remember { mutableStateOf(false) }

    // FIX: edit state for user messages
    var editMode   by remember { mutableStateOf(false) }
    var editText   by remember { mutableStateOf(msg.content) }

    val formattedTime = remember(msg.timestamp) {
        java.text.SimpleDateFormat("HH:mm  dd/MM/yyyy", java.util.Locale.getDefault())
            .format(java.util.Date(msg.timestamp))
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Text(
                if (isUser) userName else "Thunder AGI",
                style = MaterialTheme.typography.labelSmall,
                color = if (isUser) ThunderGlow else ThunderElectric,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )

            // FIX: single ThinkingBox — only inside the bubble (removed duplicate above bubble)
            Box(
                Modifier
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(
                        topStart    = 16.dp, topEnd   = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd   = if (isUser) 4.dp else 16.dp
                    ))
                    .background(if (isUser) UserBubble else AIBubble)
                    .border(0.5.dp,
                        if (isUser) ThunderElectric.copy(.3f) else ThunderMidBlue,
                        RoundedCornerShape(
                            topStart    = 16.dp, topEnd   = 16.dp,
                            bottomStart = if (isUser) 16.dp else 4.dp,
                            bottomEnd   = if (isUser) 4.dp else 16.dp
                        ))
                    // FIX: tap to show/hide timestamp; long-press for menu
                    .combinedClickable(
                        onClick    = { if (!editMode) showTimestamp = !showTimestamp },
                        onLongClick = { showMenu = true }
                    )
                    .padding(12.dp)
            ) {
                Column {
                    msg.mediaPath?.let { path ->
                        val model: Any = if (path.startsWith("http")) path else File(path)
                        AsyncImage(model = model, contentDescription = null,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Fit)
                        Spacer(Modifier.height(6.dp))
                    }

                    if (msg.isLoading) {
                        // FIX: single ThinkingBox with rotating bilingual labels
                        ThinkingBox(msg.thinkingStep ?: "معالجة…  Processing…")
                    } else if (editMode && isUser) {
                        // FIX: inline edit mode
                        OutlinedTextField(
                            value         = editText,
                            onValueChange = { editText = it },
                            modifier      = Modifier.fillMaxWidth(),
                            colors        = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor      = ThunderElectric,
                                unfocusedBorderColor    = ThunderMidBlue,
                                focusedTextColor        = ThunderWhite,
                                unfocusedTextColor      = ThunderWhite,
                                cursorColor             = ThunderElectric,
                                focusedContainerColor   = ThunderDeepBlue,
                                unfocusedContainerColor = ThunderDeepBlue
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
                        )
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { editMode = false; editText = msg.content }) {
                                Text("Cancel", color = ThunderGray, style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = {
                                editMode = false
                                onEditMessage?.invoke(msg.id, editText.trim())
                            }) {
                                Text("Resend ↺", color = ThunderElectric,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    } else {
                        // FIX: use MarkdownText so **bold** etc. render properly
                        val hasCode = msg.content.contains("```")
                        if (hasCode) CodeAwareText(msg.content)
                        else MarkdownText(msg.content)
                    }
                }

                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Copy", color = ThunderWhite) },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null, tint = ThunderElectric) },
                        onClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(msg.content))
                            showMenu = false
                        }
                    )
                    // FIX: Edit & Resend for user messages — creates conversation branch
                    if (isUser && !msg.isLoading && onEditMessage != null) {
                        DropdownMenuItem(
                            text = { Text("✏️ Edit & Resend", color = ThunderElectric) },
                            onClick = { editText = msg.content; editMode = true; showMenu = false }
                        )
                    }
                    if (!isUser && !msg.isLoading) {
                        DropdownMenuItem(
                            text = { Text("👍 Good response", color = ThunderGreen) },
                            onClick = { onUp(msg.id); showMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text("👎 Bad response", color = ThunderRed) },
                            onClick = { onDown(msg.id); showMenu = false }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Delete", color = ThunderRed) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = ThunderRed) },
                        onClick = { onDeleteMessage(msg.id); showMenu = false }
                    )
                }
            }

            // FIX: timestamp shown below bubble on tap
            AnimatedVisibility(showTimestamp) {
                Text(formattedTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = ThunderGray,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
            }
        }
    }
}

// ── ThinkingBox — compact rotating bilingual labels ───────────────────────────
// FIX: replaced the long static architecture description + removed the duplicate
// outer ThinkingBox. Now shows short rotating bilingual labels like Claude does.
@Composable
private fun ThinkingBox(step: String) {
    val labels = listOf(
        "معالجة…  Processing…",
        "تفكير…  Thinking…",
        "ربط…  Entangling…",
        "استرجاع…  Recalling…",
        "صياغة…  Composing…",
        "تحليل…  Analyzing…",
        "بحث…  Searching…",
        "قريباً…  Almost there…"
    )
    var idx by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1800)
            idx = (idx + 1) % labels.size
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(ThunderMidBlue.copy(.6f))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(12.dp),
            strokeWidth = 1.5.dp, color = ThunderElectric)
        Spacer(Modifier.width(6.dp))
        Text(labels[idx], style = MaterialTheme.typography.labelSmall, color = ThunderElectric)
    }
}

// ── Markdown renderer ─────────────────────────────────────────────────────────
@Composable
private fun MarkdownText(text: String) {
    val arabicChars = text.count { it in '\u0600'..'\u06FF' }
    val isRTL       = arabicChars > text.length / 3
    val textAlign   = if (isRTL) TextAlign.Right else TextAlign.Start

    val lines = text.lines()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { line ->
            when {
                line.startsWith("### ") -> Text(line.removePrefix("### "),
                    style = MaterialTheme.typography.titleSmall, color = ThunderElectric,
                    textAlign = textAlign, modifier = Modifier.fillMaxWidth())
                line.startsWith("## ")  -> Text(line.removePrefix("## "),
                    style = MaterialTheme.typography.titleMedium, color = ThunderElectric,
                    textAlign = textAlign, modifier = Modifier.fillMaxWidth())
                line.startsWith("# ")   -> Text(line.removePrefix("# "),
                    style = MaterialTheme.typography.titleLarge, color = ThunderElectric,
                    textAlign = textAlign, modifier = Modifier.fillMaxWidth())
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                    Row(horizontalArrangement = Arrangement.Start) {
                        Text("• ", color = ThunderElectric, style = MaterialTheme.typography.bodyMedium)
                        Text(buildInlineMarkdown(line.trimStart().removePrefix("- ").removePrefix("* ")),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ThunderWhite, lineHeight = 22.sp)
                    }
                }
                line.trimStart().matches(Regex("\\d+\\..*")) -> {
                    Row(horizontalArrangement = Arrangement.Start) {
                        Text(line.trimStart().substringBefore(".") + ". ",
                            color = ThunderElectric, style = MaterialTheme.typography.bodyMedium)
                        Text(buildInlineMarkdown(line.trimStart().substringAfter(". ")),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ThunderWhite, lineHeight = 22.sp)
                    }
                }
                line.trim() == "---" || line.trim() == "***" ->
                    HorizontalDivider(color = ThunderMidBlue, modifier = Modifier.padding(vertical = 4.dp))
                line.isBlank() -> Spacer(Modifier.height(4.dp))
                else -> Text(buildInlineMarkdown(line),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ThunderWhite, lineHeight = 22.sp,
                    textAlign = textAlign, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

fun buildInlineMarkdown(text: String): androidx.compose.ui.text.AnnotatedString {
    val b = androidx.compose.ui.text.AnnotatedString.Builder()
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end != -1) {
                    b.pushStyle(androidx.compose.ui.text.SpanStyle(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = androidx.compose.ui.graphics.Color(0xFFEAF6FF)))
                    b.append(text.substring(i + 2, end))
                    b.pop(); i = end + 2
                } else { b.append(text[i]); i++ }
            }
            (text.startsWith("*", i) && !text.startsWith("**", i)) ||
            (text.startsWith("_", i) && !text.startsWith("__", i)) -> {
                val delim = text[i].toString()
                val end   = text.indexOf(delim, i + 1)
                if (end != -1 && end > i + 1) {
                    b.pushStyle(androidx.compose.ui.text.SpanStyle(
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic))
                    b.append(text.substring(i + 1, end))
                    b.pop(); i = end + 1
                } else { b.append(text[i]); i++ }
            }
            text.startsWith("`", i) -> {
                val end = text.indexOf("`", i + 1)
                if (end != -1) {
                    b.pushStyle(androidx.compose.ui.text.SpanStyle(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        background = androidx.compose.ui.graphics.Color(0xFF0D1117),
                        color      = androidx.compose.ui.graphics.Color(0xFF4FC3F7)))
                    b.append(text.substring(i + 1, end))
                    b.pop(); i = end + 1
                } else { b.append(text[i]); i++ }
            }
            else -> { b.append(text[i]); i++ }
        }
    }
    return b.toAnnotatedString()
}

@Composable
private fun CodeAwareText(content: String) {
    val parts = content.split("```")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        parts.forEachIndexed { i, part ->
            if (i % 2 == 0) {
                if (part.isNotBlank()) MarkdownText(part.trim())
            } else {
                val lines     = part.trimStart('\n')
                val firstLine = lines.lines().firstOrNull()?.trim() ?: ""
                val isLangHint = firstLine.matches(Regex("[a-zA-Z]+"))
                val lang = if (isLangHint) firstLine else "code"
                val code = if (isLangHint) lines.lines().drop(1).joinToString("\n") else lines

                Column {
                    Row(Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                        .background(Color(0xFF161B22))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically) {
                        Text(lang, style = MaterialTheme.typography.labelSmall, color = ThunderGray)
                    }
                    Box(Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                        .background(Color(0xFF0D1117))
                        .border(0.5.dp, ThunderMidBlue, RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp)) {
                        Text(code.trim(),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = ThunderGlow, softWrap = false)
                    }
                }
            }
        }
    }
}

// ── Input Section ─────────────────────────────────────────────────────────────
@Composable
private fun InputSection(
    text:         String,
    generating:   Boolean,
    recording:    Boolean,
    webSearchOn:  Boolean,
    pendingImage: Uri?,
    onTextChange: (String) -> Unit,
    onSend:       () -> Unit,
    onAttach:     () -> Unit,
    onRemoveImage:() -> Unit,
    onMicPress:   () -> Unit,
    modifier:     Modifier = Modifier
) {
    val keyboard = LocalSoftwareKeyboardController.current

    Surface(modifier, color = InputBg, shadowElevation = 8.dp) {
        Column {
            pendingImage?.let { uri ->
                Row(Modifier.fillMaxWidth().background(ThunderMidBlue).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(model = uri, contentDescription = null,
                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)),
                        contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(8.dp))
                    Text("Image ready to send", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, color = ThunderGray)
                    IconButton(onClick = onRemoveImage, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, null, tint = ThunderGray)
                    }
                }
            }

            if (webSearchOn) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, null, tint = ThunderElectric, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Web search enabled", style = MaterialTheme.typography.labelSmall, color = ThunderElectric)
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {

                IconButton(onClick = onAttach, enabled = !generating) {
                    Icon(Icons.Default.AttachFile, "Attach",
                        tint = if (!generating) ThunderElectric else ThunderGray)
                }

                OutlinedTextField(
                    value       = text,
                    onValueChange = onTextChange,
                    modifier    = Modifier.weight(1f),
                    placeholder = { Text(
                        when {
                            recording  -> "🎤 Listening…"
                            generating -> "…Generating"
                            else       -> "…Message Thunder AGI"
                        }, color = ThunderGray)
                    },
                    // FIX: ImeAction.Default → Enter adds new line, not sends message
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction      = ImeAction.Default
                    ),
                    keyboardActions = KeyboardActions.Default,
                    singleLine  = false,
                    maxLines    = 5,
                    enabled     = !recording,
                    shape       = RoundedCornerShape(20.dp),
                    colors      = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor      = ThunderElectric,
                        unfocusedBorderColor    = ThunderMidBlue,
                        focusedTextColor        = ThunderWhite,
                        unfocusedTextColor      = ThunderWhite,
                        cursorColor             = ThunderElectric,
                        focusedContainerColor   = ThunderDeepBlue,
                        unfocusedContainerColor = ThunderDeepBlue
                    )
                )

                Box(Modifier.size(48.dp).clip(CircleShape)
                    .background(if (recording) ThunderRed else ThunderMidBlue)
                    .clickable { onMicPress() },
                    contentAlignment = Alignment.Center) {
                    Icon(if (recording) Icons.Default.Stop else Icons.Default.Mic,
                        "Voice", tint = if (recording) Color.White else ThunderElectric)
                }

                AnimatedVisibility((text.isNotBlank() || pendingImage != null) && !generating) {
                    Box(Modifier.size(48.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(ThunderElectric, ThunderPurple)))
                        .clickable(enabled = !generating) { onSend(); keyboard?.hide() },
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send",
                            tint = ThunderBlack, modifier = Modifier.size(20.dp))
                    }
                }

                if (generating) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(2.dp),
                        strokeWidth = 2.dp, color = ThunderElectric)
                }
            }
        }
    }
}

// ── Loading / Error ───────────────────────────────────────────────────────────
@Composable
private fun LoadingView() {
    Column(Modifier.fillMaxSize().background(ThunderBlack),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = ThunderElectric, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(24.dp))
        Text("Loading Thunder AGI…", color = ThunderWhite, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("الرَّعد للذكاء العام المصطنع", color = ThunderElectric,
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ErrorView(error: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().background(ThunderBlack).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text("⚡", fontSize = 48.sp)
        Spacer(Modifier.height(16.dp))
        Text("Failed to Load Model", style = MaterialTheme.typography.titleLarge,
            color = ThunderWhite, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(error, style = MaterialTheme.typography.bodySmall,
            color = ThunderRed, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = ThunderElectric)) {
            Text("Retry", color = ThunderBlack)
        }
    }
}

@Composable
private fun outlinedTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor      = ThunderElectric,
    unfocusedBorderColor    = ThunderMidBlue,
    focusedTextColor        = ThunderWhite,
    unfocusedTextColor      = ThunderWhite,
    cursorColor             = ThunderElectric,
    focusedContainerColor   = ThunderDeepBlue,
    unfocusedContainerColor = ThunderDeepBlue
)

// ── WiFi Sync Panel ───────────────────────────────────────────────────────────
@Composable
fun PeerSyncPanel(peerSync: com.thunder.agi.p2p.PeerSync) {
    val state by peerSync.state.collectAsState()

    Column(Modifier.fillMaxWidth()) {
        Text("WiFi Sync  مزامنة لاسلكية",
            style = MaterialTheme.typography.labelMedium, color = ThunderGray)
        Spacer(Modifier.height(6.dp))

        // FIX: better WiFi Direct error message — mentions Location requirement
        Text(
            text = when {
                state.error != null -> {
                    val err = state.error!!
                    if (err.contains("Internal error", ignoreCase = true) ||
                        err.contains("BUSY", ignoreCase = true))
                        "⚠ WiFi Direct يحتاج تفعيل الموقع (GPS) — Android 10+\n" +
                        "WiFi Direct requires Location Services to be ON (Android 10+)"
                    else "⚠ $err"
                }
                state.transferProgress != null -> "⟳ ${state.transferProgress}"
                state.lastSyncResult  != null  -> "✓ ${state.lastSyncResult}"
                state.isConnected              -> "متصل بـ ${state.connectedDevice}"
                state.isDiscovering            -> "جارٍ البحث عن الأجهزة القريبة…"
                else                           -> "غير نشط"
            },
            style = MaterialTheme.typography.bodySmall,
            color = when {
                state.error != null          -> ThunderRed
                state.lastSyncResult != null -> ThunderGreen
                else                         -> ThunderGray
            }
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                if (state.isDiscovering || state.isConnected) peerSync.disconnect()
                else peerSync.discoverPeers()
            },
            modifier = Modifier.fillMaxWidth(),
            border   = BorderStroke(1.dp, ThunderMidBlue)
        ) {
            Text(if (state.isDiscovering || state.isConnected) "إيقاف المزامنة"
                 else "بحث عن الأجهزة القريبة",
                color = ThunderElectric)
        }

        if (state.peers.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("الأجهزة المكتشفة:", style = MaterialTheme.typography.labelSmall, color = ThunderGray)
            state.peers.forEach { device ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically) {
                    Text(device.deviceName.ifBlank { device.deviceAddress },
                        style = MaterialTheme.typography.bodySmall, color = ThunderWhite)
                    TextButton(onClick = { peerSync.connectToPeer(device) }) {
                        Text("مزامنة", color = ThunderElectric)
                    }
                }
            }
        }
    }
}
