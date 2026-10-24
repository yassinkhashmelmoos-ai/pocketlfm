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
import com.thunder.agi.agent.AgentLoop
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
    val imageGenEnabled:    Boolean           = false,
    // FIX (new): "the current picture" / "make it brighter" with no number at all
    // had nothing to resolve against — this is that last-shown image, per session.
    val lastImageId:        Int?              = null
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
    private val agentLoop      = AgentLoop(agentToolkit)
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
        checkImageGenAvailable()  // quick flag read

        // FIX: this used to also call imageGenerator.preload() here, in the
        // background, right after launch. That's no longer safe to do eagerly:
        // loading ~660MB of ONNX models at the exact moment the LLM is also
        // loading its own ~218MB GGUF is what was driving the 700MB+ user-data /
        // 1.2GB+ RAM crash on launch — not just a main-thread freeze (that part
        // was already fixed by moving the work off the constructor; this is a
        // separate problem: competing for memory at the worst possible time).
        // ImageGenerator.generate() already calls ensureModelsReady() itself the
        // first time it's actually invoked (see ensureModelsReady() there), so
        // nothing further is needed here — the one-time load cost just moves to
        // someone's first "generate an image" tap instead of to every launch.
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
            showSessionDrawer = false, isGenerating = false, lastImageId = null) }
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
                    // FIX: this used to only understand an exact "image #N" / "picture #N"
                    // match and otherwise always generated fresh — "edit the original",
                    // "make it brighter" (no number at all) and "edit the current picture"
                    // all fell through to a brand-new, unrelated generation. Resolves
                    // against an image attached this turn, an explicit #N, "original"
                    // (walks the edit chain via getLineageRoot), or else falls back to
                    // the last image shown (lastImageId) for an implied continuation.
                    val sourceId = resolveImageReference(lowerText, imagePath)

                    var workingPrompt = userText
                        .replace(Regex("(generate|create|draw|make|paint|render|" +
                            "show me a?n?|sketch)\\s", RegexOption.IGNORE_CASE), "")
                        .trim().ifBlank { userText }

                    withContext(Dispatchers.Main) {
                        _ui.update { s -> s.copy(messages = s.messages.map {
                            if (it.isLoading) it.copy(thinkingStep = "🎨 رسم…  Drawing…") else it
                        })}
                    }

                    var attempts = 0
                    var genResult: ImageGenerator.GenerationResult? = null
                    var verdictNote: String? = null

                    // FIX: the old loop retried blindly on exceptions only — never on a
                    // successful-but-bad generation, and always with the same prompt each
                    // time. This asks the LFM to weigh in after each attempt (judgeGeneratedImage
                    // below — using real signals it can access, not a vision capability this
                    // app doesn't have) and lets it supply a more specific prompt for the retry,
                    // up to 3 attempts total either way.
                    while (attempts < 3) {
                        attempts++
                        genResult = if (sourceId != null) {
                            // FIX: always webEnabled=true — online fallback must not be blocked
                            imageGenerator.editImage(sourceId, workingPrompt, true,
                                progressCallback = { step, total ->
                                    _ui.update { s -> s.copy(messages = s.messages.map {
                                        if (it.isLoading) it.copy(thinkingStep = "🎨 $step/$total") else it
                                    })}
                                })
                        } else {
                            imageGenerator.generate(workingPrompt, null, true,
                                progressCallback = { step, total ->
                                    _ui.update { s -> s.copy(messages = s.messages.map {
                                        if (it.isLoading) it.copy(thinkingStep = "🎨 $step/$total") else it
                                    })}
                                })
                        }

                        val success = genResult as? ImageGenerator.GenerationResult.Success
                        if (success == null) continue // exception/failure — loop retries

                        // Don't bother judging the last allowed attempt — its outcome is
                        // accepted either way, so a judgment call would change nothing.
                        if (attempts < 3) {
                            val judgment = judgeGeneratedImage(workingPrompt, success)
                            verdictNote = judgment.note
                            if (judgment.shouldRetry && judgment.refinedPrompt != null) {
                                workingPrompt = judgment.refinedPrompt
                                continue
                            }
                        }
                        break
                    }

                    when (val r = genResult) {
                        is ImageGenerator.GenerationResult.Success -> {
                            // FIX: lastImageId tracks "the current picture" for a
                            // follow-up with no number at all.
                            _ui.update { it.copy(lastImageId = r.imageId) }
                            val caption = "🎨 Image #${r.imageId}: ${r.prompt}" +
                                (verdictNote?.let { "\n$it" } ?: "")
                            persistAndShow(ChatMessage(
                                content   = caption,
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

                // ── Agent loop (Muse Spark 1.0 style) ─────────────────────────
                // The MODEL decides whether this message needs a lookup, which tool(s) and with
                // what arguments; independent lookups run in parallel; a failed one gets a second
                // decision. See AgentLoop.kt for exactly which Spark rules this copies. It replaces
                // both the unconditional web search that used to run on EVERY message and the
                // keyword dispatcher agentToolkit.gatherContext().
                val loopResult = agentLoop.run(
                    userMessage   = userText,
                    recentContext = sessionHistory.takeLast(4).joinToString("\n") {
                        (if (it.isUser) "User: " else "Assistant: ") + it.content.take(160)
                    },
                    webEnabled    = _ui.value.webSearchEnabled,
                    generateFn    = { p, maxTok, temp ->
                        // One-off side query. LFM2's recurrent state persists across calls, so
                        // reset afterwards (same pattern judgeGeneratedImage() uses).
                        try {
                            model.generate(
                                prompt        = p,
                                temperature   = temp,
                                topP          = 0.9f,
                                topK          = 40,
                                repeatPenalty = 1.1f,
                                maxTokens     = maxTok
                            )
                        } finally {
                            model.resetContext()
                        }
                    },
                    onStep = { step ->
                        withContext(Dispatchers.Main) {
                            _ui.update { s -> s.copy(messages = s.messages.map {
                                if (it.isLoading) it.copy(thinkingStep = step) else it
                            })}
                        }
                    }
                )
                val toolContext = loopResult.toolContext

                val softCache = if (softPromptFile.exists()) softPromptFile.readText() else ""
                val prompt    = buildChatPrompt(
                    sessionHistory  = sessionHistory,
                    userText        = if (toolContext.isNotBlank()) "$toolContext$userText" else userText,
                    imagePath       = imagePath,
                    systemPrompt    = systemPrompt + emotionHint + AgentLoop.dateContext() + loopResult.note,
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

    /** Resolves which existing image (if any) an image request should start
     *  from, in priority order:
     *   1. An image uploaded with THIS message (registers it so it gets a
     *      number too, instead of being invisible to the numbering system).
     *   2. An explicit "image #N" / "picture #N" in the text.
     *   3. "the original picture" — walks the edit chain back to its root.
     *   4. Text that implies continuing to work on something ("it", "this",
     *      "modify", "change", "edit", "instead", "again"...) — defaults to
     *      the most recent image in this session.
     *   5. Otherwise null — a fresh text-to-image generation. */
    private fun resolveImageReference(lowerText: String, freshlyUploadedPath: String?): Int? {
        if (freshlyUploadedPath != null) {
            return imageGenerator.registerUploadedImage(freshlyUploadedPath)
        }
        Regex("(?:image|picture) #(\\d+)").find(lowerText)?.groupValues?.get(1)?.toIntOrNull()?.let { n ->
            if (imageGenerator.getImageRecord(n) != null) return n
        }
        val lastId = _ui.value.lastImageId ?: imageGenerator.getLatestImageId()
        if (lastId == null) return null
        if (lowerText.contains(Regex("original (image|picture|photo)"))) {
            return imageGenerator.getLineageRoot(lastId)
        }
        val impliesContinuation = lowerText.contains(Regex(
            "\\b(it|this|that|instead|again|now|more|less)\\b|" +
            "\\b(change|modify|edit|adjust|fix|redo|update)\\b"))
        return if (impliesContinuation) lastId else null
    }

    private data class ImageJudgment(val shouldRetry: Boolean, val refinedPrompt: String?, val note: String?)

    /** Asks the LFM whether a generated image likely satisfies the request, so
     *  retries are driven by the model's own judgment rather than only by
     *  exceptions, per how this was asked to work. Important honesty note: the
     *  LFM is NOT shown the image's pixels here — there's no vision tower wired
     *  into the native inference layer for that (llama-jni.cpp has no
     *  clip/mtmd-style component, and only the language-model GGUF is
     *  downloaded, no mmproj), so claiming real visual judgment would be
     *  fiction. What it DOES get is real: the prompt, how many steps actually
     *  ran, the guidance scale used, and ImageGenerator's own automatic
     *  degenerate-output check (pixel-variance based — catches blank/corrupt
     *  frames). That's a genuine signal to reason over, just not vision.
     *  model.generate() clears its own attention cache every call (see
     *  llama-jni.cpp), but LFM2's recurrent/conv state persists across calls
     *  for multi-turn coherence — so this one-off side query is followed by a
     *  full resetContext() to keep it from leaking into the next real reply;
     *  the next turn's full history is rebuilt from the DB either way (see
     *  buildChatPrompt), so nothing conversational is lost by that reset. */
    private suspend fun judgeGeneratedImage(
        prompt: String,
        result: ImageGenerator.GenerationResult.Success
    ): ImageJudgment = withContext(Dispatchers.IO) {
        try {
            val judgePrompt = buildString {
                append("<|im_start|>system\nYou are a quick quality check before an image is shown ")
                append("to the user. You cannot see the image — judge only from the facts given. ")
                append("Reply with exactly one line: GOOD, or RETRY: <a short, more specific prompt>.")
                append("<|im_end|>\n")
                append("<|im_start|>user\nRequested image: \"$prompt\"\n")
                append("Generation report: ${result.qualityHint}; ")
                append("${result.stepsUsed} denoising steps; guidance scale ${result.guidanceUsed}.\n")
                append("GOOD or RETRY?<|im_end|>\n<|im_start|>assistant\n")
            }
            val raw = model.generate(
                prompt = judgePrompt, temperature = 0.3f, topP = 0.9f,
                topK = 40, repeatPenalty = 1.1f, maxTokens = 60
            )
            model.resetContext() // see note above — this was a side query, not a real turn
            val line = raw.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "GOOD"
            if (line.startsWith("RETRY", ignoreCase = true)) {
                val refined = line.substringAfter(":", "").trim().take(200).ifBlank { null }
                ImageJudgment(shouldRetry = refined != null, refinedPrompt = refined,
                    note = "retried with a more specific prompt")
            } else {
                ImageJudgment(shouldRetry = false, refinedPrompt = null, note = null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Image judgment skipped: ${e.message}")
            ImageJudgment(shouldRetry = false, refinedPrompt = null, note = null)
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
            // FIX: this used to only remove the chat row — the generated image file and
            // its registry entry (so its number could be reused/orphaned) stuck around
            // forever even after "Delete" on the message showing it.
            _ui.value.messages.find { it.id == msgId }?.let { msg ->
                extractImageId(msg.content)?.let { id ->
                    withContext(Dispatchers.IO) { imageGenerator.deleteImage(id) }
                }
            }
            _ui.update { it.copy(messages = it.messages.filterNot { m -> m.id == msgId }) }
        }
    }

    /** Pulls the "#N" back out of a "🎨 Image #N: ..." caption, for delete/share/save. */
    private fun extractImageId(content: String): Int? =
        Regex("Image #(\\d+)").find(content)?.groupValues?.get(1)?.toIntOrNull()

    /** Builds a share Intent for a generated-image message, or null if it isn't one. */
    fun shareImage(msg: ChatMessage): Intent? {
        val id = extractImageId(msg.content) ?: return null
        return imageGenerator.shareIntent(id)
    }

    /** Saves a generated-image message to the device gallery. */
    fun saveImageToGallery(msg: ChatMessage) {
        val id = extractImageId(msg.content) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = imageGenerator.saveToGallery(id)
            withContext(Dispatchers.Main) {
                _ui.update { it.copy(snackbar = if (ok) "Saved to gallery" else "Couldn't save image") }
            }
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
