package com.thunder.agi.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thunder.agi.data.ChatDatabase
import com.thunder.agi.data.MessageEntity
import com.thunder.agi.data.SessionEntity
import com.thunder.agi.intelligence.KnowledgeGraph
import com.thunder.agi.intelligence.NightlyTrainer
import com.thunder.agi.intelligence.TinyRL
import com.thunder.agi.intelligence.SelfImprover
import com.thunder.agi.p2p.PeerSync
import com.thunder.agi.llama.ThunderModel
import com.thunder.agi.agent.AgentToolkit
import com.thunder.agi.agent.CodeExecutor
import com.thunder.agi.agent.CrashReporter
import com.thunder.agi.agent.EmotionDetector
import com.thunder.agi.agent.FileExporter
import com.thunder.agi.imaging.ImageGenerator
import com.thunder.agi.notification.UpdateNotificationListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.UUID

private const val TAG = "ThunderAGI"
private const val PREFS_NAME = "thunder_agi_prefs"

// ── LFM2.5-VL Zephyr prompt builder ──────────────────────────────────────────
// CRITICAL FIX: Only pass messages already in DB (not the current turn being built)
// and only from the CURRENT session. Context is reset on session switch.
private fun buildZephyrPrompt(
    sessionHistory: List<ChatMessage>,   // messages already saved, current session only
    userText: String,
    imagePath: String?,
    systemPrompt: String,
    softPromptCache: String
): String {
    val sb = StringBuilder()
    sb.append("<|system|>\n$systemPrompt<|endoftext|>\n")

    // Inject soft-prompt learned examples if available
    if (softPromptCache.isNotBlank()) {
        sb.append(softPromptCache).append("\n")
    }

    // Last 6 turns of THIS session only (3 user + 3 AI pairs)
    // Keeping it short prevents the repetition loop seen in testing
    val recent = sessionHistory.takeLast(6)
    recent.forEach { msg ->
        if (msg.isUser) sb.append("<|user|>\n${msg.content}<|endoftext|>\n")
        else            sb.append("<|assistant|>\n${msg.content}<|endoftext|>\n")
    }

    // Current user turn — image note goes here only, not in history
    val imgNote = if (imagePath != null) "\n[Image provided: $imagePath]" else ""
    sb.append("<|user|>\n$userText$imgNote<|endoftext|>\n")
    sb.append("<|assistant|>\n")

    return sb.toString()
}

// ── Web search via DuckDuckGo Instant Answer API (free, no key) ──────────────
suspend fun webSearch(query: String): String = withContext(Dispatchers.IO) {
    try {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = URL("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1")
        val conn = url.openConnection()
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        val response = conn.getInputStream().bufferedReader().readText()
        // Extract AbstractText from JSON
        val abstract = Regex(""""AbstractText"\s*:\s*"([^"]{10,})"""").find(response)?.groupValues?.get(1)
        val answer   = Regex(""""Answer"\s*:\s*"([^"]{5,})"""").find(response)?.groupValues?.get(1)
        val result = answer ?: abstract ?: ""
        if (result.isBlank()) "No instant answer found. Try a more specific query."
        else result.replace("\\u003c", "<").replace("\\u003e", ">")
    } catch (e: Exception) {
        "Search failed: ${e.message}"
    }
}

// ── Data classes ──────────────────────────────────────────────────────────────
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val isUser: Boolean,
    val isLoading: Boolean = false,
    val thinkingStep: String? = null,   // shown in collapsible thought box
    val mediaPath: String? = null,
    val mediaType: String? = null
)

data class ChatSession(val id: String, val title: String, val updatedAt: Long)

data class ChatUiState(
    val sessions: List<ChatSession> = emptyList(),
    val currentSessionId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isModelLoading: Boolean = false,
    val isGenerating: Boolean = false,
    val modelLoaded: Boolean = false,
    val error: String? = null,
    val isRecording: Boolean = false,
    val showSessionDrawer: Boolean = false,
    val showSettings: Boolean = false,
    val snackbar: String? = null,
    val notifPermissionNeeded: Boolean = false,
    val webSearchEnabled: Boolean = false,
    val userName: String = "You",
    val pendingImageUri: Uri? = null,
    val crashLog: String? = null,
    val exportResult: com.thunder.agi.agent.FileExporter.ExportResult? = null,
    val imageGenAvailable: Boolean = false,
    val imageGenEnabled: Boolean = false
)

// ── ViewModel ─────────────────────────────────────────────────────────────────
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val _ui = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _ui.asStateFlow()

    private val model          = ThunderModel.getInstance()
    private val db             = ChatDatabase.getInstance(application)
    private val knowledgeGraph = KnowledgeGraph(application)
    private val tinyRL         = TinyRL(application)
    private val nightlyTrainer = NightlyTrainer(application)
    private val prefs: SharedPreferences =
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val softPromptFile = File(application.filesDir, "soft_prompt_cache.txt")
    private val agentToolkit  = AgentToolkit(application)
    private val codeExecutor  = CodeExecutor(application)
    private val crashReporter  = CrashReporter.install(application)
    private val imageGenerator = ImageGenerator(application)
    val fileExporter           = FileExporter(application)
    private val selfImprover  = SelfImprover(application)
    val peerSync = PeerSync(application)
    private var sessionJob: Job? = null
    private var speechRecognizer: SpeechRecognizer? = null

    // System prompt — user-editable in settings
    private var systemPrompt: String
        get() {
            val evolved = selfImprover.getEvolvedPrompt("")
            val base = prefs.getString("system_prompt",
                "You are Thunder AGI (الرَّعد للذكاء العام المصطنع), a powerful and helpful AI. " +
                "Rules: (1) Never repeat sentences or phrases you already wrote. " +
                "(2) Each response must be DIFFERENT from your previous ones. " +
                "(3) Answer in the same language the user uses — Arabic or English. " +
                "(4) Give direct answers. For code: provide working examples. " +
                "(5) Never include <|user|>, <|assistant|> or other tokens in responses. " +
                "(6) If you have already answered something, give new information or say so.") ?: ""
            return if (evolved.isNotBlank()) "$base\n$evolved" else base
        }
        set(v) { prefs.edit().putString("system_prompt", v).apply() }

    private var userName: String
        get() = prefs.getString("user_name", "You") ?: "You"
        set(v) { prefs.edit().putString("user_name", v).apply() }

    init {
        _ui.update { it.copy(userName = userName) }
        observeSessions()
        ensureModelLoaded()
        nightlyTrainer.schedule()
        checkNotifPermission()
    }

    // ── Model ──────────────────────────────────────────────────────────────────

    private fun ensureModelLoaded() {
        if (model.isLoaded()) {
            _ui.update { it.copy(modelLoaded = true) }
            ensureSession()
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(isModelLoading = true, error = null) }
            val ok = model.loadModel(getApplication())
            _ui.update { it.copy(isModelLoading = false, modelLoaded = ok,
                error = if (!ok) "Failed to load model." else null) }
            if (ok) ensureSession()
        }
    }

    fun retryLoadModel() = ensureModelLoaded()

    // ── Sessions ───────────────────────────────────────────────────────────────

    private fun observeSessions() {
        viewModelScope.launch {
            db.sessionDao().getAllSessions().collect { rows ->
                _ui.update { s -> s.copy(sessions = rows.map { r ->
                    ChatSession(r.id, r.title, r.updatedAt) }) }
            }
        }
    }

    private fun ensureSession() {
        if (_ui.value.currentSessionId != null) return
        val first = _ui.value.sessions.firstOrNull()
        if (first != null) switchSession(first.id) else newSession()
    }

    fun newSession() {
        viewModelScope.launch {
            val id = UUID.randomUUID().toString()
            db.sessionDao().insert(SessionEntity(id = id, title = "New Chat"))
            switchSession(id)
            _ui.update { it.copy(showSessionDrawer = false) }
        }
    }

    fun switchSession(id: String) {
        // Cancel previous session's message stream
        sessionJob?.cancel()
        // CRITICAL: reset KV cache so model doesn't bleed between sessions
        model.resetContext()
        _ui.update { it.copy(
            currentSessionId = id,
            messages = emptyList(),
            showSessionDrawer = false,
            isGenerating = false   // stop any in-progress generation
        )}
        sessionJob = viewModelScope.launch {
            db.messageDao().getMessagesForSession(id).collect { rows ->
                // Only update if this session is still active (prevent race condition)
                if (_ui.value.currentSessionId == id) {
                    _ui.update { s -> s.copy(messages = rows.map { r ->
                        ChatMessage(id = r.id, content = r.content, isUser = r.isUser,
                            mediaPath = r.mediaPath, mediaType = r.mediaType)
                    })}
                }
            }
        }
    }

    fun renameSession(id: String, newTitle: String) {
        viewModelScope.launch {
            db.sessionDao().insert(SessionEntity(id = id, title = newTitle.trim().take(60),
                updatedAt = System.currentTimeMillis()))
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            db.sessionDao().deleteById(id)
            db.messageDao().deleteForSession(id)
            if (_ui.value.currentSessionId == id) {
                _ui.update { it.copy(currentSessionId = null, messages = emptyList()) }
                ensureSession()
            }
        }
    }

    fun toggleDrawer()   = _ui.update { it.copy(showSessionDrawer = !it.showSessionDrawer) }
    fun toggleSettings() = _ui.update { it.copy(showSettings = !it.showSettings) }

    // ── Messaging ──────────────────────────────────────────────────────────────

    fun onInputChanged(text: String) = _ui.update { it.copy(inputText = text) }

    fun setPendingImage(uri: Uri?) = _ui.update { it.copy(pendingImageUri = uri) }

    fun sendMessage() {
        val text     = _ui.value.inputText.trim()
        val imageUri = _ui.value.pendingImageUri
        // FIX: allow send with image even if text is blank
        if (text.isBlank() && imageUri == null) return
        if (_ui.value.isGenerating) return
        val sid = _ui.value.currentSessionId ?: return

        _ui.update { it.copy(inputText = "", pendingImageUri = null) }

        viewModelScope.launch {
            val imgPath = imageUri?.let { saveUri(it) }
            val displayText = text.ifBlank { "📎 Image" }

            val userMsg = ChatMessage(
                content   = displayText,
                isUser    = true,
                mediaPath = imgPath,
                mediaType = if (imgPath != null) "image" else null
            )
            persistAndShow(userMsg, sid)

            // Auto-title from first message
            if (_ui.value.messages.count { it.isUser } <= 1 && text.isNotBlank()) {
                db.sessionDao().insert(SessionEntity(id = sid,
                    title = text.take(45).trim(), updatedAt = System.currentTimeMillis()))
            }

            generateResponse(text.ifBlank { "Describe this image." }, imgPath, sid)
        }
    }

    private fun generateResponse(userText: String, imagePath: String?, sessionId: String) {
        // Use SupervisorJob so background doesn't cancel this
        viewModelScope.launch(Dispatchers.Default + SupervisorJob()) {
            withContext(Dispatchers.Main) {
                _ui.update { it.copy(isGenerating = true) }
                _ui.update { it.copy(messages = it.messages +
                        ChatMessage(content = "", isUser = false, isLoading = true,
                            thinkingStep = "Processing your request…")) }
            }

            try {
                // Agentic image generation — LFM decides when to generate
                val lowerText = userText.lowercase()
                val isImageRequest = _ui.value.imageGenEnabled ||
                    lowerText.contains(Regex("generate|create|draw|make|paint|sketch|image of|picture of|show me a|visualize|render"))
                val isEditRequest = isImageRequest &&
                    lowerText.contains(Regex("edit|modify|change|update|alter|fix|image #\\d+|picture #\\d+"))

                if (isImageRequest) {
                    // Extract which image to edit if specified
                    val editIdMatch = Regex("(?:image|picture) #(\\d+)").find(lowerText)
                    val editId = editIdMatch?.groupValues?.get(1)?.toIntOrNull()

                    withContext(Dispatchers.Main) {
                        _ui.update { s -> s.copy(messages = s.messages.map {
                            if (it.isLoading) it.copy(thinkingStep = "🎨 Generating image…") else it
                        })}
                    }

                    // Agentic retry loop (max 3 attempts)
                    var attempts = 0
                    var genResult: com.thunder.agi.imaging.ImageGenerator.GenerationResult? = null
                    while (attempts < 3) {
                        genResult = if (editId != null) {
                            imageGenerator.editImage(editId, userText, _ui.value.webSearchEnabled)
                        } else {
                            val cleanPrompt = userText
                                .replace(Regex("generate|create|draw|make|paint|image of|picture of|show me a?n?", RegexOption.IGNORE_CASE), "")
                                .trim().ifBlank { userText }
                            imageGenerator.generate(cleanPrompt, null, _ui.value.webSearchEnabled,
                                progressCallback = { step, total ->
                                    _ui.update { s -> s.copy(messages = s.messages.map {
                                        if (it.isLoading) it.copy(thinkingStep = "🎨 Step $step/$total") else it
                                    })}
                                })
                        }
                        if (genResult is com.thunder.agi.imaging.ImageGenerator.GenerationResult.Success) break
                        attempts++
                        Log.w("ChatViewModel", "Image gen attempt $attempts failed")
                    }

                    when (val r = genResult) {
                        is com.thunder.agi.imaging.ImageGenerator.GenerationResult.Success -> {
                            withContext(Dispatchers.IO) {
                                persistAndShow(ChatMessage(
                                    content = "🎨 Image #${r.imageId}: ${r.prompt}",
                                    isUser = false,
                                    mediaPath = r.imagePath,
                                    mediaType = "image"
                                ), sessionId)
                            }
                        }
                        is com.thunder.agi.imaging.ImageGenerator.GenerationResult.Failure -> {
                            Log.e("ChatViewModel", "Image gen failed after 3 attempts: ${r.reason}")
                        }
                        else -> {}
                    }
                }

                // Web search augmentation
                var augmentedText = userText
                if (_ui.value.webSearchEnabled) {
                    withContext(Dispatchers.Main) {
                        _ui.update { s -> s.copy(messages = s.messages.map {
                            if (it.isLoading) it.copy(thinkingStep = "🔍 Searching the web…") else it
                        })}
                    }
                    val searchResult = webSearch(userText)
                    if (!searchResult.startsWith("No instant") && !searchResult.startsWith("Search failed")) {
                        augmentedText = "$userText\n\n[Web search result: $searchResult]"
                    }
                }

                // Get ONLY current session's saved messages for context
                // CRITICAL: fetch directly from DB for this session, not from UI state
                // which may have loading messages mixed in
                val sessionHistory = withContext(Dispatchers.IO) {
                    db.messageDao().getMessagesForSessionOnce(sessionId).map { r ->
                        ChatMessage(id = r.id, content = r.content, isUser = r.isUser)
                    }
                }

                // Gather tool context (weather, wikipedia, search etc.)
                val toolContext = agentToolkit.gatherContext(userText, _ui.value.webSearchEnabled)

                // Emotion detection
                val emotionHint = EmotionDetector.getSystemHint(userText)

                val softCache = if (softPromptFile.exists()) softPromptFile.readText() else ""
                val effectiveSystem = systemPrompt + emotionHint
                val prompt = buildZephyrPrompt(
                    sessionHistory = sessionHistory,
                    userText       = if (toolContext.isNotBlank()) "$toolContext$augmentedText" else augmentedText,
                    imagePath      = imagePath,
                    systemPrompt   = effectiveSystem,
                    softPromptCache = softCache
                )

                val topic    = tinyRL.classifyTopic(userText)
                val rlParams = tinyRL.getParams(topic)

                withContext(Dispatchers.Main) {
                    _ui.update { s -> s.copy(messages = s.messages.map {
                        if (it.isLoading) it.copy(thinkingStep = "⚡ Generating response…") else it
                    })}
                }

                val response = model.generate(
                    prompt        = prompt,
                    temperature   = rlParams.temperature,
                    topP          = rlParams.topP,
                    topK          = 50,
                    repeatPenalty = rlParams.repPenalty,
                    maxTokens     = 768
                )

                // Ensure we're still on the same session before saving
                if (_ui.value.currentSessionId != sessionId) return@launch

                withContext(Dispatchers.Main) {
                    _ui.update { it.copy(messages = it.messages.filterNot { m -> m.isLoading }) }
                }

                val aiMsg = ChatMessage(content = cleanModelOutput(response), isUser = false)

                withContext(Dispatchers.IO) {
                    persistAndShow(aiMsg, sessionId)
                    knowledgeGraph.learnFromConversation(userText, response)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Generation error", e)
                val crash = "${e.javaClass.simpleName}: ${e.message}"
                withContext(Dispatchers.Main) {
                    _ui.update { it.copy(
                        messages = it.messages.filterNot { m -> m.isLoading },
                        crashLog = crash
                    )}
                }
            } finally {
                withContext(Dispatchers.Main) {
                    _ui.update { it.copy(isGenerating = false) }
                }
            }
        }
    }

    private suspend fun persistAndShow(msg: ChatMessage, sessionId: String) {
        withContext(Dispatchers.IO) {
            db.messageDao().insert(MessageEntity(
                id = msg.id, sessionId = sessionId,
                content = msg.content, isUser = msg.isUser,
                mediaPath = msg.mediaPath, mediaType = msg.mediaType
            ))
            db.sessionDao().touch(sessionId)
        }
        withContext(Dispatchers.Main) {
            if (_ui.value.currentSessionId == sessionId) {
                // distinctBy prevents duplicate-key crash in LazyColumn when the
                // scroll animation triggers a remeasure mid-update
                _ui.update { it.copy(messages = (it.messages + msg).distinctBy { m -> m.id }) }
            }
        }
    }

    fun deleteMessage(msgId: String) {
        val sessionId = _ui.value.currentSessionId ?: return
        viewModelScope.launch {
            db.messageDao().deleteById(msgId)
            _ui.update { it.copy(messages = it.messages.filterNot { m -> m.id == msgId }) }
        }
    }

    fun clearChat() {
        val id = _ui.value.currentSessionId ?: return
        viewModelScope.launch {
            db.messageDao().deleteForSession(id)
            model.resetContext()
            _ui.update { it.copy(messages = emptyList()) }
        }
    }

    // ── Settings ───────────────────────────────────────────────────────────────

    fun saveUserName(name: String) {
        userName = name.trim().take(20).ifBlank { "You" }
        _ui.update { it.copy(userName = userName) }
    }

    fun saveSystemPrompt(prompt: String) { systemPrompt = prompt }
    fun fetchSystemPrompt() = systemPrompt
    fun toggleWebSearch() = _ui.update { it.copy(webSearchEnabled = !it.webSearchEnabled) }

    // ── Feedback (TinyRL) ──────────────────────────────────────────────────────

    fun submitFeedback(msgId: String, good: Boolean) {
        val msg  = _ui.value.messages.find { it.id == msgId } ?: return
        val prev = _ui.value.messages.takeWhile { it.id != msgId }.lastOrNull { it.isUser }
        val topic = tinyRL.classifyTopic(prev?.content ?: "")
        tinyRL.applyFeedback(topic, good)
        viewModelScope.launch(Dispatchers.IO) {
            val line = """{"prompt":${prev?.content.orEmpty().jsonStr()},"response":${msg.content.jsonStr()},"good":$good}""" + "\n"
            File(getApplication<Application>().filesDir, "feedback_log.jsonl").appendText(line)
        }
        _ui.update { it.copy(snackbar = if (good) "👍 Saved!" else "👎 Noted") }
    }

    fun importFineTune(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dest = File(getApplication<Application>().filesDir, "finetune_import.gguf")
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { i ->
                    FileOutputStream(dest).use { o -> i.copyTo(o) }
                }
                _ui.update { it.copy(snackbar = "✅ Fine-tune imported! Restart to apply.") }
            } catch (e: Exception) {
                _ui.update { it.copy(snackbar = "❌ Import failed: ${e.message}") }
            }
        }
    }

    fun exportFeedbackLog() {
        val file = File(getApplication<Application>().filesDir, "feedback_log.jsonl")
        _ui.update { it.copy(snackbar = if (file.exists())
            "Feedback log: ${file.absolutePath} (${file.length()/1024}KB)"
            else "No feedback data yet") }
    }

    // ── Notification / WhatsApp ────────────────────────────────────────────────

    private fun checkNotifPermission() {
        _ui.update { it.copy(
            notifPermissionNeeded = !UpdateNotificationListener.isPermissionGranted(getApplication())
        )}
    }

    fun openNotifSettings(context: Context) =
        UpdateNotificationListener.openPermissionSettings(context)

    fun dismissNotifPrompt() = _ui.update { it.copy(notifPermissionNeeded = false) }

    // ── Voice ──────────────────────────────────────────────────────────────────

    fun startVoice(context: Context) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _ui.update { it.copy(snackbar = "Speech recognition not available on this device") }
            return
        }
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).also { sr ->
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: android.os.Bundle?) {
                    _ui.update { it.copy(isRecording = true, snackbar = "🎤 Listening…") }
                }
                override fun onResults(r: android.os.Bundle?) {
                    val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: ""
                    _ui.update { it.copy(inputText = (_ui.value.inputText + " " + t).trim(),
                        isRecording = false, snackbar = null) }
                }
                override fun onPartialResults(r: android.os.Bundle?) {
                    val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: ""
                    if (t.isNotBlank()) _ui.update { it.copy(inputText = t) }
                }
                override fun onError(e: Int) {
                    val msg = when(e) {
                        SpeechRecognizer.ERROR_NO_MATCH        -> "No speech detected — try speaking again"
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice needs internet — type instead, or enable Wi-Fi"
                        SpeechRecognizer.ERROR_AUDIO           -> "Microphone error — check mic permissions"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy — tap mic again"
                        SpeechRecognizer.ERROR_SERVER          -> "Voice server error — try again"
                        else -> "Speech error ($e) — try again"
                    }
                    _ui.update { it.copy(isRecording = false, snackbar = msg) }
                }
                override fun onBeginningOfSpeech() {}
                override fun onEndOfSpeech() {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onRmsChanged(r: Float) {}
                override fun onEvent(t: Int, p: android.os.Bundle?) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA,en-US")
            // Prefer on-device recognition to avoid network errors when offline
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            _ui.update { it.copy(isRecording = false, snackbar = "Could not start recording: ${e.message}") }
        }
    }

    fun stopVoice() {
        speechRecognizer?.stopListening()
        _ui.update { it.copy(isRecording = false) }
    }

    // ── Crash reporter ────────────────────────────────────────────────────────

    fun clearCrashLog() = _ui.update { it.copy(crashLog = null) }

    fun exportLastResponse(format: FileExporter.ExportFormat = FileExporter.ExportFormat.TXT) {
        val lastAI = _ui.value.messages.lastOrNull { !it.isUser && !it.isLoading } ?: run {
            _ui.update { it.copy(snackbar = "No response to export") }; return
        }
        val lastUser = _ui.value.messages.takeWhile { it.id != lastAI.id }.lastOrNull { it.isUser }
        viewModelScope.launch {
            val result = fileExporter.exportResponse(lastAI.content, lastUser?.content ?: "", format)
            if (result != null) {
                _ui.update { it.copy(exportResult = result, snackbar = "✅ Saved as ${result.file.name}") }
            } else {
                _ui.update { it.copy(snackbar = "❌ Export failed") }
            }
        }
    }

    fun exportConversation() {
        val sid = _ui.value.currentSessionId ?: return
        val title = _ui.value.sessions.find { it.id == sid }?.title ?: "conversation"
        val msgs = _ui.value.messages.map { Pair(it.isUser, it.content) }
        viewModelScope.launch {
            val result = fileExporter.exportConversation(msgs, title)
            if (result != null) {
                _ui.update { it.copy(exportResult = result, snackbar = "✅ Conversation exported") }
            } else {
                _ui.update { it.copy(snackbar = "❌ Export failed") }
            }
        }
    }

    fun clearExportResult() = _ui.update { it.copy(exportResult = null) }

    fun toggleImageGen() = _ui.update { it.copy(imageGenEnabled = !it.imageGenEnabled) }

    fun checkImageGenAvailable() {
        _ui.update { it.copy(imageGenAvailable = imageGenerator.isOfflineAvailable()) }
    }

    // ── Misc ──────────────────────────────────────────────────────────────────

    fun clearSnackbar() = _ui.update { it.copy(snackbar = null) }

    private suspend fun saveUri(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val ext = getApplication<Application>().contentResolver.getType(uri)
                ?.substringAfterLast("/") ?: "jpg"
            val f = File(getApplication<Application>().filesDir,
                "img_${System.currentTimeMillis()}.$ext")
            getApplication<Application>().contentResolver.openInputStream(uri)
                ?.use { i -> FileOutputStream(f).use { o -> i.copyTo(o) } }
            f.absolutePath
        } catch (e: Exception) { null }
    }

    /** Strip any leaked prompt tokens from model output */
    private fun cleanModelOutput(raw: String): String {
        var s = raw.trim()

        // Stop at template/format tokens — truncate everything from the token onward
        val stopTokens = listOf(
            "<|user|>", "<|assistant|>", "<|system|>", "<|endoftext|>",
            "</s>", "<|end|>", "<|im_end|>", "|assistant|>", "|user|>",
            "|assistant|/>", "</|user>", "</|assistant>", "[INST]", "[/INST]"
        )
        for (token in stopTokens) {
            val idx = s.indexOf(token)
            if (idx != -1) s = s.substring(0, idx)
        }

        // Strip echoed prompt blocks the model sometimes regurgitates:
        // [Image provided: /path/to/file] and [Tool Results — use this information]
        s = s.replace(Regex("""\[Image provided:[^\]]*\]"""), "").trim()
        s = s.replace(Regex("""\[Tool Results[^\]]*\]""", RegexOption.DOT_MATCHES_ALL), "").trim()
        // Also strip bare "Image provided: /path" lines (without brackets, model drops them)
        s = s.replace(Regex("""(?m)^Image provided:.*$"""), "").trim()
        // Strip leftover "/> " artifacts
        s = s.replace(Regex("""^\s*/>""", RegexOption.MULTILINE), "").trim()

        // Remove trailing incomplete tokens like "<|"
        s = s.trimEnd().trimEnd('<').trimEnd('|').trim()
        return s.ifBlank { "…" }
    }

    private fun String.jsonStr() =
        "\"${replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")}\""

    override fun onCleared() {
        super.onCleared()
        speechRecognizer?.destroy()
        knowledgeGraph.close()
    }
}
