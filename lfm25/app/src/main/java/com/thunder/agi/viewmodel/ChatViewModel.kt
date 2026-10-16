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
import com.thunder.agi.llama.ThunderModel
import com.thunder.agi.agent.AgentToolkit
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

private const val TAG       = "ThunderAGI"
private const val PREFS_NAME = "thunder_agi_prefs"

// ── Prompt builder ────────────────────────────────────────────────────────────
// FIX: LFM2.5-VL uses a ChatML-style template (<|im_start|>role ... <|im_end|>),
// confirmed against Liquid AI's own model card and GGUF conversion docs — NOT the
// Zephyr-style <|user|>/<|assistant|>/<|system|>/<|endoftext|> tags this used to
// emit. The model was never trained on those tags, which is why so many odd stop
// sequences were needed downstream just to contain the confusion they caused.
// BOS ("<|startoftext|>") is intentionally NOT written here: nativeGenerate()
// already tokenizes with add_special=true, which auto-prepends the model's real
// BOS token — writing it again as literal text would double it up.
// Turn-boundary tokens also changed from "<|user|>" to "<|im_start|>"; the native
// truncation logic in llama-jni.cpp was updated to match (see FIX there too).
private fun buildChatPrompt(
    sessionHistory:  List<ChatMessage>,
    userText:        String,
    imagePath:       String?,
    systemPrompt:    String,
    softPromptCache: String
): String {
    val sb = StringBuilder()

    var systemBlock = systemPrompt
    if (softPromptCache.isNotBlank()) {
        val clean = softPromptCache.take(400)
            .replace("<|im_start|>", "")
            .replace("<|im_end|>",   "")
            .replace("<|user|>",      "Q:")
            .replace("<|assistant|>", "A:")
            .replace("<|endoftext|>", "")
            .replace("<|system|>",    "")
            .trim()
        if (clean.isNotBlank()) {
            systemBlock += "\n\nExamples of good responses:\n$clean"
        }
    }
    sb.append("<|im_start|>system\n").append(systemBlock).append("<|im_end|>\n")

    val recent = sessionHistory.takeLast(6)
    recent.forEach { msg ->
        val role = if (msg.isUser) "user" else "assistant"
        sb.append("<|im_start|>$role\n${msg.content}<|im_end|>\n")
    }

    val imgNote = if (imagePath != null) "\n[Image: $imagePath]" else ""
    sb.append("<|im_start|>user\n$userText$imgNote<|im_end|>\n")
    sb.append("<|im_start|>assistant\n")
    return sb.toString()
}

// ── Web search ────────────────────────────────────────────────────────────────
suspend fun webSearch(query: String): String = withContext(Dispatchers.IO) {
    try {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url  = URL("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1")
        val conn = url.openConnection()
        conn.connectTimeout = 5000; conn.readTimeout = 5000
        val resp = conn.getInputStream().bufferedReader().readText()
        val abs  = Regex(""""AbstractText"\s*:\s*"([^"]{10,})"""").find(resp)?.groupValues?.get(1)
        val ans  = Regex(""""Answer"\s*:\s*"([^"]{5,})"""").find(resp)?.groupValues?.get(1)
        (ans ?: abs ?: "").replace("\\u003c", "<").replace("\\u003e", ">")
    } catch (e: Exception) { "" }
}

// ── Data classes ──────────────────────────────────────────────────────────────
data class ChatMessage(
    val id:        String  = UUID.randomUUID().toString(),
    val content:   String,
    val isUser:    Boolean,
    val isLoading: Boolean = false,
    val thinkingStep: String? = null,
    val mediaPath: String? = null,
    val mediaType: String? = null,
    val timestamp: Long    = System.currentTimeMillis()
)

data class ChatSession(val id: String, val title: String, val updatedAt: Long)

data class ChatUiState(
    val sessions:           List<ChatSession> = emptyList(),
    val currentSessionId:   String?           = null,
    val messages:           List<ChatMessage> = emptyList(),
    val inputText:          String            = "",
    val isModelLoading:     Boolean           = false,
    val isGenerating:       Boolean           = false,
    val modelLoaded:        Boolean           = false,
    val error:              String?           = null,
    val isRecording:        Boolean           = false,
    val showSessionDrawer:  Boolean           = false,
    val showSettings:       Boolean           = false,
    val snackbar:           String?           = null,
    // FIX: feedback log shown in AlertDialog, not snackbar (snackbar hidden behind Settings panel)
    val feedbackLogDialog:  String?           = null,
    val notifPermissionNeeded: Boolean        = false,
    val webSearchEnabled:   Boolean           = false,
    val userName:           String            = "You",
    val pendingImageUri:    Uri?              = null,
    val crashLog:           String?           = null,
    val exportResult:       FileExporter.ExportResult? = null,
    val imageGenAvailable:  Boolean           = false,
    val imageGenEnabled:    Boolean           = false
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
    private val agentToolkit   = AgentToolkit(application)
    private val imageGenerator = ImageGenerator(application)
    val fileExporter           = FileExporter(application)
    private val selfImprover   = SelfImprover(application)
    // FIX: PeerSync is created only in MainActivity — removed from ViewModel to avoid
    //      double BroadcastReceiver registration causing WiFi Direct conflicts.

    private var sessionJob: Job? = null
    private var speechRecognizer: SpeechRecognizer? = null

    private var systemPrompt: String
        get() {
            val base = prefs.getString("system_prompt",
                "You are Thunder AGI (الرَّعد للذكاء العام المصطنع), a powerful and helpful AI. " +
                "Rules: " +
                "(1) Never repeat sentences or phrases you already wrote. " +
                "(2) Each response adds new information — never restate what you said. " +
                "(3) Answer in the user's language — Arabic or English. " +
                "(4) Give direct, complete answers. For code, provide working examples. " +
                "(5) Never output <|user|>, <|assistant|>, <|system|> or other template tokens. " +
                "(6) Format: use **bold** for key terms, bullet points for lists. " +
                "(7) For Arabic text: use proper Modern Standard Arabic.") ?: ""
            // FIX: avoid doubling system prompt — only append evolved part if truly different
            val evolved = selfImprover.getEvolvedPrompt(base)
            return if (evolved.isNotBlank() && evolved != base) evolved else base
        }
        set(v) { prefs.edit().putString("system_prompt", v).apply() }

    private var userName: String
        get()  = prefs.getString("user_name", "You") ?: "You"
        set(v) { prefs.edit().putString("user_name", v).apply() }

    init {
        _ui.update { it.copy(userName = userName) }
        observeSessions()
        ensureModelLoaded()
        nightlyTrainer.schedule()
        checkNotifPermission()
        checkImageGenAvailable()  // quick flag read — true once preload below finishes

        // FIX: ImageGenerator used to copy ~700MB of ONNX models and load them into
        // ONNX Runtime from its *constructor*, which runs here, synchronously, on
        // whatever thread creates this ViewModel — the main/UI thread. That froze
        // (or ANR'd) first launch for every user, even ones who never generate an
        // image. The constructor is cheap now; kick the real loading off here, in
        // the background, and refresh the UI flag once it's actually ready.
        viewModelScope.launch(Dispatchers.IO) {
            imageGenerator.preload()
            withContext(Dispatchers.Main) { checkImageGenAvailable() }
        }
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
        sessionJob?.cancel()
        // FIX: nativeResetContext() blocks on the same native mutex nativeGenerate()
        // holds for the whole duration of a reply. Calling it directly here used to
        // run it on whichever thread called switchSession() — the main/UI thread —
        // so switching chats while a previous reply was still generating froze the
        // app until that generation finished. Run it in the background instead; the
        // UI below updates immediately regardless.
        viewModelScope.launch(Dispatchers.Default) { model.resetContext() }
        _ui.update { it.copy(currentSessionId = id, messages = emptyList(),
            showSessionDrawer = false, isGenerating = false) }
        sessionJob = viewModelScope.launch {
            db.messageDao().getMessagesForSession(id).collect { rows ->
                if (_ui.value.currentSessionId == id) {
                    _ui.update { s -> s.copy(messages = rows.map { r ->
                        ChatMessage(id = r.id, content = r.content, isUser = r.isUser,
                            mediaPath = r.mediaPath, mediaType = r.mediaType,
                            timestamp = r.timestamp)
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
    fun setPendingImage(uri: Uri?)   = _ui.update { it.copy(pendingImageUri = uri) }

    // FIX: editAndResend — truncates history after the edited message and re-generates
    fun editAndResend(messageId: String, newContent: String) {
        val sid = _ui.value.currentSessionId ?: return
        if (_ui.value.isGenerating) return
        viewModelScope.launch {
            val msgs = _ui.value.messages.toMutableList()
            val idx  = msgs.indexOfFirst { it.id == messageId }
            if (idx < 0) return@launch

            // Delete messages from idx onward from DB
            withContext(Dispatchers.IO) {
                msgs.subList(idx, msgs.size).forEach { db.messageDao().deleteById(it.id) }
            }

            val editedMsg = msgs[idx].copy(content = newContent, timestamp = System.currentTimeMillis())
            withContext(Dispatchers.IO) {
                db.messageDao().insert(MessageEntity(
                    id = editedMsg.id, sessionId = sid,
                    content = editedMsg.content, isUser = true,
                    timestamp = editedMsg.timestamp
                ))
            }

            _ui.update { it.copy(messages = msgs.subList(0, idx) + editedMsg) }
            generateResponse(newContent, null, sid)
        }
    }

    fun sendMessage() {
        val text     = _ui.value.inputText.trim()
        val imageUri = _ui.value.pendingImageUri
        if (text.isBlank() && imageUri == null) return
        if (_ui.value.isGenerating) return
        val sid = _ui.value.currentSessionId ?: return

        _ui.update { it.copy(inputText = "", pendingImageUri = null) }

        viewModelScope.launch {
            val imgPath     = imageUri?.let { saveUri(it) }
            val displayText = text.ifBlank { "📎 Image" }
            val userMsg     = ChatMessage(content = displayText, isUser = true,
                mediaPath = imgPath, mediaType = if (imgPath != null) "image" else null)
            persistAndShow(userMsg, sid)

            if (_ui.value.messages.count { it.isUser } <= 1 && text.isNotBlank()) {
                db.sessionDao().insert(SessionEntity(id = sid, title = text.take(45).trim(),
                    updatedAt = System.currentTimeMillis()))
            }
            generateResponse(text.ifBlank { "Describe this image." }, imgPath, sid)
        }
    }

    private fun generateResponse(userText: String, imagePath: String?, sessionId: String) {
        // FIX: removed SupervisorJob() which detached coroutine from viewModelScope,
        // causing generation to leak even after ViewModel is cleared (memory+CPU waste).
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                _ui.update { it.copy(isGenerating = true) }
                _ui.update { s -> s.copy(messages =
                    s.messages.filterNot { m -> m.isLoading } +
                    ChatMessage(content = "", isUser = false, isLoading = true,
                        thinkingStep = "معالجة…  Processing…")) }
            }

            try {
                val lowerText = userText.lowercase()

                // FIX: tighter image detection — requires visual keyword near image keyword
                // to avoid false-positives like "make dinner" or "create a plan"
                val isImageRequest = _ui.value.imageGenEnabled ||
                    lowerText.contains(Regex(
                        "(generate|create|draw|make|paint|sketch|render|show)\\s.{0,25}" +
                        "(image|picture|photo|art|illustration|diagram|portrait|landscape)|" +
                        "(image|picture|photo)\\s(of|showing|with)"))

                if (isImageRequest) {
                    val editIdMatch = Regex("(?:image|picture) #(\\d+)").find(lowerText)
                    val editId = editIdMatch?.groupValues?.get(1)?.toIntOrNull()

                    withContext(Dispatchers.Main) {
                        _ui.update { s -> s.copy(messages = s.messages.map {
                            if (it.isLoading) it.copy(thinkingStep = "🎨 رسم…  Drawing…") else it
                        })}
                    }

                    var attempts = 0
                    var genResult: ImageGenerator.GenerationResult? = null
                    while (attempts < 3) {
                        genResult = if (editId != null) {
                            // FIX: always webEnabled=true — online fallback must not be blocked
                            imageGenerator.editImage(editId, userText, true)
                        } else {
                            val cleanPrompt = userText
                                .replace(Regex("(generate|create|draw|make|paint|render|" +
                                    "show me a?n?|sketch)\\s", RegexOption.IGNORE_CASE), "")
                                .trim().ifBlank { userText }
                            imageGenerator.generate(cleanPrompt, null, true,
                                progressCallback = { step, total ->
                                    _ui.update { s -> s.copy(messages = s.messages.map {
                                        if (it.isLoading) it.copy(thinkingStep = "🎨 $step/$total") else it
                                    })}
                                })
                        }
                        if (genResult is ImageGenerator.GenerationResult.Success) break
                        attempts++
                    }

                    when (val r = genResult) {
                        is ImageGenerator.GenerationResult.Success -> {
                            persistAndShow(ChatMessage(
                                content   = "🎨 Image #${r.imageId}: ${r.prompt}",
                                isUser    = false,
                                mediaPath = r.imagePath,
                                mediaType = "image"
                            ), sessionId)
                        }
                        is ImageGenerator.GenerationResult.Failure -> {
                            // FIX: show failure message to user instead of silently dropping
                            persistAndShow(ChatMessage(
                                content = "❌ Image generation failed: ${r.reason}. ${r.suggestion}",
                                isUser  = false
                            ), sessionId)
                        }
                        else -> {}
                    }
                    // FIX: return here so LLM text generation doesn't also run after image
                    return@launch
                }

                // ── Web search augmentation ───────────────────────────────────
                var augmentedText = userText
                if (_ui.value.webSearchEnabled) {
                    withContext(Dispatchers.Main) {
                        _ui.update { s -> s.copy(messages = s.messages.map {
                            if (it.isLoading) it.copy(thinkingStep = "🔍 بحث…  Searching…") else it
                        })}
                    }
                    val result = webSearch(userText)
                    if (result.isNotBlank()) augmentedText = "$userText\n\n[Web result: $result]"
                }

                val toolContext = agentToolkit.gatherContext(userText, _ui.value.webSearchEnabled)
                val emotionHint = EmotionDetector.getSystemHint(userText)

                val sessionHistory = withContext(Dispatchers.IO) {
                    db.messageDao().getMessagesForSessionOnce(sessionId).map { r ->
                        ChatMessage(id = r.id, content = r.content, isUser = r.isUser)
                    }
                }.let { history ->
                    // FIX: by the time generateResponse() runs, the current turn has
                    // already been written to the DB (persistAndShow() in sendMessage()/
                    // editAndResend() runs first, so the UI can show it immediately).
                    // That means it's also the last row this query returns — and it gets
                    // appended again below via `userText`, so without this it appeared
                    // TWICE in every prompt. Only drop it when it really is that same
                    // trailing turn, so nothing is lost if the call path ever changes.
                    val last = history.lastOrNull()
                    if (last != null && last.isUser && last.content == userText)
                        history.dropLast(1) else history
                }

                val softCache = if (softPromptFile.exists()) softPromptFile.readText() else ""
                val prompt    = buildChatPrompt(
                    sessionHistory  = sessionHistory,
                    userText        = if (toolContext.isNotBlank()) "$toolContext$augmentedText" else augmentedText,
                    imagePath       = imagePath,
                    systemPrompt    = systemPrompt + emotionHint,
                    softPromptCache = softCache
                )

                val topic    = tinyRL.classifyTopic(userText)
                val rlParams = tinyRL.getParams(topic)

                withContext(Dispatchers.Main) {
                    _ui.update { s -> s.copy(messages = s.messages.map {
                        if (it.isLoading) it.copy(thinkingStep = "⚡ صياغة…  Composing…") else it
                    })}
                }

                val response = model.generate(
                    prompt        = prompt,
                    // FIX: temperature ≥ 0.7 — Q4_K_M quantization noise floor is ~1.15 logit
                    // units; at T=0.4 you're sampling through 288% noise. T≥0.65 clears it.
                    temperature   = maxOf(rlParams.temperature, 0.7f),
                    topP          = rlParams.topP,
                    topK          = 50,
                    repeatPenalty = rlParams.repPenalty,
                    maxTokens     = 512  // FIX: 512 not 768 — reduces mid-sequence truncation risk
                )

                if (_ui.value.currentSessionId != sessionId) return@launch

                withContext(Dispatchers.Main) {
                    _ui.update { it.copy(messages = it.messages.filterNot { m -> m.isLoading }) }
                }

                val cleaned = cleanModelOutput(response)
                persistAndShow(ChatMessage(content = cleaned, isUser = false), sessionId)
                withContext(Dispatchers.IO) {
                    knowledgeGraph.learnFromConversation(userText, response)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Generation error", e)
                withContext(Dispatchers.Main) {
                    _ui.update { it.copy(
                        messages = it.messages.filterNot { m -> m.isLoading },
                        crashLog = "${e.javaClass.simpleName}: ${e.message}"
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
                mediaPath = msg.mediaPath, mediaType = msg.mediaType,
                timestamp = msg.timestamp
            ))
            db.sessionDao().touch(sessionId)
        }
        withContext(Dispatchers.Main) {
            if (_ui.value.currentSessionId == sessionId) {
                _ui.update { it.copy(messages = (it.messages + msg).distinctBy { m -> m.id }) }
            }
        }
    }

    fun deleteMessage(msgId: String) {
        viewModelScope.launch {
            db.messageDao().deleteById(msgId)
            _ui.update { it.copy(messages = it.messages.filterNot { m -> m.id == msgId }) }
        }
    }

    fun clearChat() {
        val id = _ui.value.currentSessionId ?: return
        viewModelScope.launch {
            // FIX: both calls used to run on viewModelScope's default (Main) dispatcher.
            // resetContext() blocks on the same native mutex a generation holds for the
            // whole reply, so clearing the chat mid-generation froze the app; the DB
            // delete is also blocking I/O that doesn't belong on the UI thread.
            withContext(Dispatchers.IO)      { db.messageDao().deleteForSession(id) }
            withContext(Dispatchers.Default) { model.resetContext() }
            _ui.update { it.copy(messages = emptyList()) }
        }
    }

    // ── Settings ───────────────────────────────────────────────────────────────
    fun saveUserName(name: String) {
        userName = name.trim().take(20).ifBlank { "You" }
        _ui.update { it.copy(userName = userName) }
    }

    fun saveSystemPrompt(prompt: String) { systemPrompt = prompt }
    fun fetchSystemPrompt()              = systemPrompt
    fun toggleWebSearch()                = _ui.update { it.copy(webSearchEnabled = !it.webSearchEnabled) }

    // ── Feedback ──────────────────────────────────────────────────────────────
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

    // FIX: show feedback log path in AlertDialog, not snackbar.
    // Snackbar was hidden behind the Settings panel — user couldn't see or copy it.
    fun exportFeedbackLog() {
        val file = File(getApplication<Application>().filesDir, "feedback_log.jsonl")
        val msg  = if (file.exists())
            "📄 Feedback log:\n${file.absolutePath}\n\nSize: ${file.length()/1024} KB\n\n" +
            "Copy this path and open it in a file manager app."
        else
            "No feedback data yet — rate some responses using 👍👎 first."
        _ui.update { it.copy(feedbackLogDialog = msg) }
    }

    fun dismissFeedbackLogDialog() = _ui.update { it.copy(feedbackLogDialog = null) }

    // ── Notifications ─────────────────────────────────────────────────────────
    private fun checkNotifPermission() {
        _ui.update { it.copy(
            notifPermissionNeeded = !UpdateNotificationListener.isPermissionGranted(getApplication())
        )}
    }
    fun openNotifSettings(context: Context) = UpdateNotificationListener.openPermissionSettings(context)
    fun dismissNotifPrompt() = _ui.update { it.copy(notifPermissionNeeded = false) }

    // ── Voice ──────────────────────────────────────────────────────────────────
    fun startVoice(context: Context) {
        // FIX: check permission explicitly before creating recognizer
        val permResult = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO)
        if (permResult != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            _ui.update { it.copy(snackbar =
                "🎤 إذن الميكروفون مطلوب — Microphone permission required\n" +
                "Settings → Apps → Thunder AGI → Permissions") }
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _ui.update { it.copy(snackbar = "Speech recognition not available on this device") }
            return
        }
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).also { sr ->
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: android.os.Bundle?) {
                    _ui.update { it.copy(isRecording = true, snackbar = "🎤 استمع…  Listening…") }
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
                    val msg = when (e) {
                        SpeechRecognizer.ERROR_NO_MATCH ->
                            "لم يُتعرَّف على الكلام — حاول مجدداً / No speech detected"
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            "الصوت يحتاج إنترنت / Voice needs internet"
                        SpeechRecognizer.ERROR_AUDIO ->
                            "خطأ في الميكروفون / Microphone error"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                            "المعرِّف مشغول — اضغط المايك مجدداً / Busy, tap mic again"
                        SpeechRecognizer.ERROR_SERVER ->
                            "خطأ في الخادم / Server error"
                        // FIX: code 12 = ERROR_CLIENT_NOT_AUTHORIZED — mic permission denied
                        12, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                            "إذن الميكروفون مرفوض — افتح الإعدادات / Mic permission denied — open Settings"
                        else ->
                            "خطأ صوتي ($e) — حاول مجدداً / Speech error ($e)"
                    }
                    _ui.update { it.copy(isRecording = false, snackbar = msg) }
                }
                override fun onBeginningOfSpeech() {}
                override fun onEndOfSpeech()       {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onRmsChanged(r: Float) {}
                override fun onEvent(t: Int, p: android.os.Bundle?) {}
            })
        }
        // FIX: removed EXTRA_PREFER_OFFLINE — blocks recognition on most non-Samsung
        // Android devices because Arabic offline model is usually not installed.
        // FIX: removed hardcoded "ar-SA,en-US" — let the device use its configured language.
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            _ui.update { it.copy(isRecording = false,
                snackbar = "Could not start recording: ${e.message}") }
        }
    }

    fun stopVoice() {
        speechRecognizer?.stopListening()
        _ui.update { it.copy(isRecording = false) }
    }

    // ── Crash / export ────────────────────────────────────────────────────────
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
        val sid   = _ui.value.currentSessionId ?: return
        val title = _ui.value.sessions.find { it.id == sid }?.title ?: "conversation"
        val msgs  = _ui.value.messages.map { Pair(it.isUser, it.content) }
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
            val f = File(getApplication<Application>().filesDir, "img_${System.currentTimeMillis()}.$ext")
            getApplication<Application>().contentResolver.openInputStream(uri)
                ?.use { i -> FileOutputStream(f).use { o -> i.copyTo(o) } }
            f.absolutePath
        } catch (e: Exception) { null }
    }

    private fun cleanModelOutput(raw: String): String {
        var s = raw.trim()
        val stopTokens = listOf(
            "<|user|>", "<|assistant|>", "<|system|>", "<|endoftext|>",
            "</s>", "<|end|>", "<|im_end|>", "|assistant|>", "|user|>",
            "|assistant|/>", "</|user>", "</|assistant>", "[INST]", "[/INST]",
            "[End Tool Results]", "[Tool Results"
        )
        for (token in stopTokens) {
            val idx = s.indexOf(token)
            if (idx != -1) s = s.substring(0, idx)
        }
        s = s.replace(Regex("""\[Image provided:[^\]]*\]"""), "").trim()
        s = s.replace(Regex("""\[Tool Results[^\]]*\]""", RegexOption.DOT_MATCHES_ALL), "").trim()
        s = s.replace(Regex("""\[End Tool Results\]"""), "").trim()
        s = s.replace(Regex("""(?m)^Image provided:.*$"""), "").trim()
        s = s.replace(Regex("""^\s*/>""", RegexOption.MULTILINE), "").trim()
        s = s.trimEnd { it == '<' || it == '|' || it == ' ' || it == '\n' }
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
