package com.fersaiyan.cyanbridge.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import com.fersaiyan.cyanbridge.agent.LocalModelsConfigureActivity
import com.fersaiyan.cyanbridge.agent.ProSubscriptionAiPrefs
import com.fersaiyan.cyanbridge.agent.ProSubscriptionRelayClient
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderType
import com.fersaiyan.cyanbridge.ai.router.AiAssistantRouter as RelayAiAssistantRouter
import com.fersaiyan.cyanbridge.chat.ChatRole
import com.fersaiyan.cyanbridge.chat.ChatStore
import com.fersaiyan.cyanbridge.databinding.ActivityChatThreadBinding
import com.fersaiyan.cyanbridge.localagent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.localagent.context.LocalAgentContextBuilder
import com.fersaiyan.cyanbridge.localagent.dailyfacts.DailyFactsReviewProtocol
import com.fersaiyan.cyanbridge.localagent.dailyfacts.DailyFactsStorage
import com.fersaiyan.cyanbridge.localagent.userfacts.CandidateUserFactsStorage
import com.fersaiyan.cyanbridge.localagent.userfacts.UserFactsStorage
import com.fersaiyan.cyanbridge.localagent.userfacts.ChatMemoryAutoUpdater
import com.fersaiyan.cyanbridge.localagent.dailysummary.DailySummaryPrefs
import com.fersaiyan.cyanbridge.localagent.dailysummary.DailySummaryRegenerateWorker
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemorySearch
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.fersaiyan.cyanbridge.ui.chat.ChatAppearancePrefs
import com.fersaiyan.cyanbridge.ui.chat.ChatMessageAdapter
import com.fersaiyan.cyanbridge.ui.chat.ThinkingIndicatorController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatThreadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatThreadBinding
    private val adapter = ChatMessageAdapter()
    private var chatId: String? = null

    private var isDailyFactsReview: Boolean = false
    private var dailyFactsDate: String? = null
    private var thinkingIndicator: ThinkingIndicatorController? = null
    private var pendingAssistantRequests: Int = 0
    private var localGenerationRunning: Boolean = false
    private var localTitleGenerationInProgress: Boolean = false
    private var streamingAssistantDraft: String? = null
    private val queuedLocalPrompts = java.util.ArrayDeque<QueuedLocalPrompt>()

    private data class QueuedLocalPrompt(
        val chatId: String,
        val userPrompt: String,
    )

    private val pickWallpaperLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            ChatAppearancePrefs.setWallpaperUri(this, uri.toString())
            applyChatAppearance()
            android.widget.Toast.makeText(this, "Chat wallpaper updated", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatThreadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isDailyFactsReview = intent.getBooleanExtra(EXTRA_DAILY_FACTS_REVIEW, false)
        dailyFactsDate = intent.getStringExtra(EXTRA_DAILY_FACTS_DATE)

        chatId = intent.getStringExtra(EXTRA_CHAT_ID)

        // Allow creating a new thread when opening from notifications/intents.
        if (chatId == null) {
            val title = intent.getStringExtra(EXTRA_CREATE_THREAD_TITLE)
            if (!title.isNullOrBlank()) {
                chatId = ChatStore.createThread(title = title).id
            }
        }

        var thread = chatId?.let { ChatStore.getThread(it) }
        if (chatId != null && thread == null) {
            chatId = null
        }

        // Important: setSupportActionBar() installs its own navigation click listener.
        // If we want the hamburger to open the chat list, we must set our listener *after*.
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        binding.toolbar.title = "Chats list"
        binding.tvToolbarTitle.text = thread?.title ?: "New chat"

        binding.toolbar.setNavigationOnClickListener {
            // "Chats list" button should keep the user inside the Chats section.
            // Do NOT use NEW_TASK here; it can bounce the user back to the previous section/task.
            val i = android.content.Intent(this, ChatListActivity::class.java)
                .addFlags(
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            startActivity(i)
            finish()
        }
        binding.btnChatAppearance.setOnClickListener {
            showChatAppearanceMenu()
        }

        binding.recyclerMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.recyclerMessages.adapter = adapter
        applyChatAppearance()

        // Initialize thinking indicator
        thinkingIndicator = ThinkingIndicatorController(
            container = binding.thinkingIndicator,
            tvThinkingText = binding.tvThinkingText,
            dot1 = binding.dot1,
            dot2 = binding.dot2,
            dot3 = binding.dot3,
        )

        binding.btnSend.setOnClickListener {
            if (localGenerationRunning) {
                lifecycleScope.launch {
                    RelayAiAssistantRouter.cancelCurrentGeneration(this@ChatThreadActivity)
                }
                return@setOnClickListener
            }

            if (isLocalModelsProviderSelected() && !hasLocalModelAvailable()) {
                promptLocalModelSetup()
                return@setOnClickListener
            }

            val text = binding.inputMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                if (isLocalModelsProviderSelected() && localTitleGenerationInProgress && !isDailyFactsReview) {
                    enqueueLocalPrompt(text)
                } else {
                    sendMessage(text)
                }
                binding.inputMessage.text?.clear()
            }
        }

        // Optional intent-driven prefill.
        intent.getStringExtra(EXTRA_PREFILL_MESSAGE)
            ?.takeIf { it.isNotBlank() }
            ?.let { binding.inputMessage.setText(it) }

        setupBottomNavigation()
        refreshModelBadge("Ready")
        updateComposerForGenerationState()
        refreshMessages()

        // Auto-kickoff daily facts review.
        val cid = chatId
        if (isDailyFactsReview && cid != null && ChatStore.listMessages(cid).isEmpty()) {
            kickoffDailyFactsReview()
        }
    }

    override fun onResume() {
        super.onResume()
        // Ensure correct nav highlight when returning via CLEAR_TOP/SINGLE_TOP.
        binding.bottomNavigation.post {
            binding.bottomNavigation.menu.findItem(R.id.nav_chats).isChecked = true
        }
        applyChatAppearance()
        refreshModelBadge("Ready")
        updateComposerForGenerationState()
    }

    override fun onDestroy() {
        thinkingIndicator?.hide()
        if (localGenerationRunning) {
            lifecycleScope.launch {
                RelayAiAssistantRouter.cancelCurrentGeneration(this@ChatThreadActivity)
            }
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        chatId = intent?.getStringExtra(EXTRA_CHAT_ID)

        if (chatId == null) {
            val title = intent?.getStringExtra(EXTRA_CREATE_THREAD_TITLE)
            if (!title.isNullOrBlank()) {
                chatId = ChatStore.createThread(title = title).id
            }
        }

        val cid = chatId
        if (cid != null) {
            val thread = ChatStore.getThread(cid)
            if (thread != null) {
                binding.tvToolbarTitle.text = thread.title
            }

            intent?.getStringExtra(EXTRA_PREFILL_MESSAGE)
                ?.takeIf { it.isNotBlank() }
                ?.let { binding.inputMessage.setText(it) }

            refreshMessages()
        } else {
            binding.tvToolbarTitle.text = "New chat"
            adapter.submitList(emptyList())
        }
        applyChatAppearance()
    }

    private fun applyChatAppearance() {
        val userColor = ChatAppearancePrefs.getUserBubbleColor(this)
        val assistantColor = ChatAppearancePrefs.getAssistantBubbleColor(this)
        adapter.applyAppearance(userColor, assistantColor)

        val wallpaper = ChatAppearancePrefs.getWallpaperUri(this)
        if (wallpaper.isBlank()) {
            binding.ivChatWallpaper.setImageDrawable(null)
            binding.ivChatWallpaper.visibility = android.view.View.GONE
            return
        }

        val uri = android.net.Uri.parse(wallpaper)
        runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        }.onSuccess { bmp ->
            if (bmp != null) {
                binding.ivChatWallpaper.setImageBitmap(bmp)
                binding.ivChatWallpaper.visibility = android.view.View.VISIBLE
            } else {
                binding.ivChatWallpaper.visibility = android.view.View.GONE
            }
        }.onFailure {
            binding.ivChatWallpaper.visibility = android.view.View.GONE
        }
    }

    private fun showChatAppearanceMenu() {
        val providerModelOption = when {
            isLocalModelsProviderSelected() -> "Change local model"
            isRelayProviderSelected() -> "Change relay AI model"
            else -> "Change AI model"
        }
        val items = arrayOf(
            "Change user bubble color",
            "Change assistant bubble color",
            "Choose wallpaper from gallery",
            "Remove wallpaper",
            "Reset chat appearance",
            providerModelOption,
        )

        AlertDialog.Builder(this)
            .setTitle("Chat appearance")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showColorPicker(
                        title = "User bubble color",
                        current = ChatAppearancePrefs.getUserBubbleColor(this),
                    ) { selected ->
                        ChatAppearancePrefs.setUserBubbleColor(this, selected)
                        applyChatAppearance()
                    }

                    1 -> showColorPicker(
                        title = "Assistant bubble color",
                        current = ChatAppearancePrefs.getAssistantBubbleColor(this),
                    ) { selected ->
                        ChatAppearancePrefs.setAssistantBubbleColor(this, selected)
                        applyChatAppearance()
                    }

                    2 -> pickWallpaperLauncher.launch(arrayOf("image/*"))
                    3 -> {
                        ChatAppearancePrefs.clearWallpaper(this)
                        applyChatAppearance()
                        android.widget.Toast.makeText(this, "Wallpaper removed", android.widget.Toast.LENGTH_SHORT).show()
                    }

                    4 -> {
                        ChatAppearancePrefs.reset(this)
                        applyChatAppearance()
                        android.widget.Toast.makeText(this, "Chat appearance reset", android.widget.Toast.LENGTH_SHORT).show()
                    }

                    5 -> showModelPickerForProvider()
                }
            }
            .show()
    }

    private fun showModelPickerForProvider() {
        when {
            isLocalModelsProviderSelected() -> showLocalModelPicker()
            isRelayProviderSelected() -> showRelayModelPicker()
            else -> android.widget.Toast.makeText(this, "Current provider does not support model selection", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun showLocalModelPicker() {
        val installed = LocalModelStorageRepository.listInstalled(this)
        if (installed.isEmpty()) {
            promptLocalModelSetup()
            return
        }

        val selectedId = LocalModelStorageRepository.getSelectedModelId(this)
        val labels = installed.map { model ->
            if (model.id == selectedId) {
                "${model.displayName} (Current)"
            } else {
                model.displayName
            }
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select local model")
            .setItems(labels) { _, which ->
                val picked = installed.getOrNull(which) ?: return@setItems
                LocalModelStorageRepository.setSelectedModelId(this, picked.id)
                refreshModelBadge("Ready")
                updateComposerForGenerationState()
                android.widget.Toast.makeText(this, "Using ${picked.displayName}", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRelayModelPicker() {
        val current = ProSubscriptionAiPrefs.getRequestsModel(this)
        lifecycleScope.launch {
            val options = withContext(Dispatchers.IO) {
                val fetched = ProSubscriptionRelayClient.fetchAvailableModels(this@ChatThreadActivity).getOrElse { emptyList() }
                val merged = linkedMapOf<String, String>()
                merged["auto"] = "auto"
                fetched.forEach { option ->
                    val id = option.id.trim()
                    if (id.isBlank()) return@forEach
                    val label = option.label.trim().ifBlank { id }
                    if (!merged.containsKey(id)) {
                        merged[id] = label
                    }
                }
                if (current.isNotBlank() && !merged.containsKey(current)) {
                    merged[current] = current
                }
                merged.entries.toList()
            }

            if (options.isEmpty()) {
                android.widget.Toast.makeText(
                    this@ChatThreadActivity,
                    "No relay models available",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }

            val labels = options.map { option ->
                val id = option.key
                val label = option.value
                if (id.equals(current, ignoreCase = true)) "$label (Current)" else label
            }.toTypedArray()

            AlertDialog.Builder(this@ChatThreadActivity)
                .setTitle("Select relay model")
                .setItems(labels) { _, which ->
                    val picked = options.getOrNull(which) ?: return@setItems
                    val pickedId = picked.key
                    val pickedLabel = picked.value
                    ProSubscriptionAiPrefs.setRequestsModel(this@ChatThreadActivity, pickedId)
                    refreshModelBadge("Ready")
                    android.widget.Toast.makeText(
                        this@ChatThreadActivity,
                        "Chat model set to $pickedLabel",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun showColorPicker(title: String, current: Int, onPick: (Int) -> Unit) {
        val options = listOf(
            "Cyan" to 0xFF00E5FF.toInt(),
            "Ocean" to 0xFF1F8AFA.toInt(),
            "Forest" to 0xFF2E7D32.toInt(),
            "Amber" to 0xFFFFB300.toInt(),
            "Coral" to 0xFFFF6F61.toInt(),
            "Slate" to 0xFF455A64.toInt(),
            "Rose" to 0xFFE91E63.toInt(),
            "Purple" to 0xFF7E57C2.toInt(),
        )

        val labels = options.map { (name, color) ->
            if (color == current) "$name (Current)" else name
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels) { _, which ->
                onPick(options[which].second)
            }
            .show()
    }

    private fun isLocalModelsProviderSelected(): Boolean {
        return AiProviderPrefs.getProvider(this) == AiProviderType.LOCAL_MODELS
    }

    private fun isRelayProviderSelected(): Boolean {
        return AiProviderPrefs.getProvider(this) == AiProviderType.CLI_RELAY
    }

    private fun refreshModelBadge(status: String) {
        when (AiProviderPrefs.getProvider(this)) {
            AiProviderType.LOCAL_MODELS -> {
                val selected = LocalModelStorageRepository.resolveSelectedModel(this)
                if (selected == null) {
                    binding.tvLocalModelBadge.text = "Local model: none installed"
                    binding.tvLocalModelBadge.visibility = android.view.View.VISIBLE
                    return
                }
                binding.tvLocalModelBadge.text = "Local model: ${selected.displayName} ($status)"
                binding.tvLocalModelBadge.visibility = android.view.View.VISIBLE
            }

            AiProviderType.CLI_RELAY -> {
                val selected = ProSubscriptionAiPrefs.getRequestsModel(this).ifBlank { "auto" }
                binding.tvLocalModelBadge.text = "Relay model: $selected"
                binding.tvLocalModelBadge.visibility = android.view.View.VISIBLE
            }

            else -> {
                binding.tvLocalModelBadge.visibility = android.view.View.GONE
            }
        }
    }

    private fun showRelayDownToastIfNeeded(message: String?) {
        val hint = ProSubscriptionRelayClient.relayUnavailableHintFromText(message.orEmpty()) ?: return
        android.widget.Toast.makeText(this, hint, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun hasLocalModelAvailable(): Boolean {
        if (!isLocalModelsProviderSelected()) return true
        return LocalModelStorageRepository.resolveSelectedModel(this) != null
    }

    private fun updateComposerForGenerationState() {
        if (localGenerationRunning) {
            binding.btnSend.setImageResource(android.R.drawable.ic_media_pause)
            binding.btnSend.contentDescription = "Stop generation"
            binding.inputMessage.isEnabled = false
            binding.btnSend.isEnabled = true
        } else {
            val localModelMissing = isLocalModelsProviderSelected() && !hasLocalModelAvailable()
            if (localModelMissing) {
                binding.btnSend.setImageResource(android.R.drawable.ic_menu_manage)
                binding.btnSend.contentDescription = "Configure local model"
                binding.inputMessage.isEnabled = true
                binding.inputLayout.hint = "Install/select a local model to start chatting"
                binding.btnSend.isEnabled = true
            } else {
                binding.btnSend.setImageResource(android.R.drawable.ic_menu_send)
                binding.btnSend.contentDescription = "Send"
                binding.inputMessage.isEnabled = true
                binding.inputLayout.hint = "Message"
                binding.btnSend.isEnabled = true
            }
        }
    }

    private fun promptLocalModelSetup() {
        AlertDialog.Builder(this)
            .setTitle("Local model required")
            .setMessage("Local Models is selected, but no local model is installed. Configure one to start chatting.")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Configure") { _, _ ->
                startActivity(Intent(this, LocalModelsConfigureActivity::class.java))
            }
            .show()
    }

    private fun sendMessage(text: String) {
        val cid = ensureChatThread()

        val shouldGenerateSmartTitle = ChatStore.listMessages(cid).isEmpty()
        ChatStore.addMessage(cid, ChatRole.USER, text)
        refreshMessages()

        startAssistantGeneration(
            chatId = cid,
            userPrompt = text,
            requestSmartTitleAfterReply = shouldGenerateSmartTitle,
        )
    }

    private fun enqueueLocalPrompt(text: String) {
        val cid = ensureChatThread()
        ChatStore.addMessage(cid, ChatRole.USER, text)
        refreshMessages()
        queuedLocalPrompts.addLast(
            QueuedLocalPrompt(
                chatId = cid,
                userPrompt = text,
            ),
        )
        refreshModelBadge("Queued")
        android.widget.Toast.makeText(
            this,
            "Queued. Sending right after title update...",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    private fun ensureChatThread(): String {
        val existing = chatId
        if (existing != null) return existing

        val created = ChatStore.createThread()
        chatId = created.id
        binding.tvToolbarTitle.text = created.title
        return created.id
    }

    private fun startAssistantGeneration(
        chatId: String,
        userPrompt: String,
        requestSmartTitleAfterReply: Boolean,
        onFinished: (() -> Unit)? = null,
    ) {
        lifecycleScope.launch {
            onAssistantRequestStarted()
            val useLocalStreaming = isLocalModelsProviderSelected() && !isDailyFactsReview
            if (useLocalStreaming) {
                localGenerationRunning = true
                streamingAssistantDraft = ""
                updateComposerForGenerationState()
                refreshModelBadge("Loading")
                refreshMessages()
            }
            try {
                val assistantReply = withContext(Dispatchers.IO) {
                    val messages = buildRelayMessages(chatId = chatId, lastUserPrompt = userPrompt)
                    RelayAiAssistantRouter.chatReplyStreaming(
                        context = this@ChatThreadActivity,
                        chatId = chatId,
                        userPrompt = userPrompt,
                        messages = messages,
                        callbacks = if (useLocalStreaming) {
                            object : RelayAiAssistantRouter.ChatStreamCallbacks {
                                override fun onStatus(status: String) {
                                    runOnUiThread {
                                        refreshModelBadge(status)
                                    }
                                }

                                override fun onToken(token: String) {
                                    runOnUiThread {
                                        val cur = streamingAssistantDraft.orEmpty()
                                        streamingAssistantDraft = cur + token
                                        refreshMessages()
                                    }
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
                showRelayDownToastIfNeeded(assistantReply)

                if (isDailyFactsReview) {
                    handleDailyFactsAiReply(chatId, assistantReply)
                } else {
                    val streamed = streamingAssistantDraft.orEmpty().trim()
                    val finalReply = when {
                        assistantReply.isNotBlank() -> assistantReply
                        streamed.isNotBlank() -> streamed
                        useLocalStreaming -> "I couldn't generate a reply yet. Loading the model and retrying can take a moment; please try again."
                        else -> "I could not generate a reply."
                    }
                    streamingAssistantDraft = null
                    ChatStore.addMessage(chatId, ChatRole.ASSISTANT, finalReply)

                    if (requestSmartTitleAfterReply) {
                        maybeGenerateSmartThreadTitle(chatId = chatId, firstUserPrompt = userPrompt)
                    }

                    // Proactive memory: extract candidate facts from normal chats.
                    lifecycleScope.launch {
                        val applied = withContext(Dispatchers.IO) {
                            ChatMemoryAutoUpdater.extractAndStore(
                                context = this@ChatThreadActivity,
                                userMessage = userPrompt,
                                assistantReply = finalReply,
                            )
                        }

                        if (applied.addedCandidateUserFacts > 0 || applied.addedDailyFacts > 0) {
                            val msg = buildString {
                                if (applied.addedDailyFacts > 0) append("Saved ${applied.addedDailyFacts} daily fact(s). ")
                                if (applied.addedCandidateUserFacts > 0) append("Saved ${applied.addedCandidateUserFacts} candidate user fact(s).")
                            }.trim()
                            if (msg.isNotBlank()) {
                                android.widget.Toast.makeText(this@ChatThreadActivity, msg, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }

                refreshMessages()
            } catch (_: kotlinx.coroutines.CancellationException) {
                val partial = streamingAssistantDraft.orEmpty().trim()
                if (partial.isNotBlank() && !isDailyFactsReview) {
                    streamingAssistantDraft = null
                    ChatStore.addMessage(chatId, ChatRole.ASSISTANT, partial)
                    refreshMessages()
                }
                android.widget.Toast.makeText(
                    this@ChatThreadActivity,
                    "Generation stopped",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            } catch (t: Throwable) {
                showRelayDownToastIfNeeded(t.message)
                android.widget.Toast.makeText(
                    this@ChatThreadActivity,
                    t.message ?: "Failed to get response",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } finally {
                localGenerationRunning = false
                streamingAssistantDraft = null
                updateComposerForGenerationState()
                refreshModelBadge("Ready")
                refreshMessages()
                onAssistantRequestFinished()
                onFinished?.invoke()
            }
        }
    }

    private fun kickoffDailyFactsReview() {
        val cid = chatId ?: return
        val date = dailyFactsDate ?: java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(System.currentTimeMillis()))

        lifecycleScope.launch {
            onAssistantRequestStarted()
            try {
                val assistantReply = withContext(Dispatchers.IO) {
                    val state = DailyFactsStorage.load(this@ChatThreadActivity, date)
                    LocalAgentMemoryStore.ensureSeedFiles(this@ChatThreadActivity)
                    val userFactsMd = LocalAgentMemoryStore.readText(LocalAgentMemoryStore.userFactsFile(this@ChatThreadActivity))
                    val candidateUserFacts = CandidateUserFactsStorage.load(this@ChatThreadActivity, date)
                    val system = DailyFactsReviewProtocol.buildSystemMessage(state, userFactsMd, candidateUserFacts)

                    val msgs = listOf(
                        mapOf("role" to "System", "content" to system),
                        mapOf(
                            "role" to "User",
                            "content" to "Start the daily facts review. Ask me about up to 3 facts at a time.",
                        ),
                    )

                    RelayAiAssistantRouter.chatReply(
                        context = this@ChatThreadActivity,
                        chatId = cid,
                        userPrompt = "Start daily facts review",
                        messages = msgs,
                    )
                }
                showRelayDownToastIfNeeded(assistantReply)

                handleDailyFactsAiReply(cid, assistantReply)
                refreshMessages()
            } catch (t: Throwable) {
                showRelayDownToastIfNeeded(t.message)
                android.widget.Toast.makeText(
                    this@ChatThreadActivity,
                    t.message ?: "Failed to start daily facts review",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } finally {
                onAssistantRequestFinished()
            }
        }
    }

    private fun onAssistantRequestStarted() {
        pendingAssistantRequests += 1
        if (pendingAssistantRequests == 1) {
            thinkingIndicator?.show()
        }
    }

    private fun onAssistantRequestFinished() {
        pendingAssistantRequests = (pendingAssistantRequests - 1).coerceAtLeast(0)
        if (pendingAssistantRequests == 0) {
            thinkingIndicator?.hide()
        }
    }

    private fun maybeGenerateSmartThreadTitle(chatId: String, firstUserPrompt: String) {
        val usesLocalModels = isLocalModelsProviderSelected()
        if (usesLocalModels) {
            localTitleGenerationInProgress = true
            refreshModelBadge("Choosing title...")
        }
        onAssistantRequestStarted()

        lifecycleScope.launch {
            try {
                val suggested = withContext(Dispatchers.IO) {
                    runCatching {
                        val namingPrompt = buildString {
                            appendLine("Generate a concise chat title from the user request.")
                            appendLine("Rules:")
                            appendLine("- Return only the title text")
                            appendLine("- 2 to 6 words")
                            appendLine("- No quotation marks")
                            appendLine("- No punctuation at the end")
                            appendLine()
                            appendLine("User request:")
                            appendLine(firstUserPrompt.trim())
                        }

                        RelayAiAssistantRouter.textReply(
                            context = this@ChatThreadActivity,
                            prompt = namingPrompt,
                        )
                    }.getOrNull()
                }

                val title = sanitizeThreadTitle(
                    candidate = suggested,
                    fallbackPrompt = firstUserPrompt,
                )

                if (ChatStore.updateThreadTitle(chatId, title)) {
                    if (this@ChatThreadActivity.chatId == chatId) {
                        binding.tvToolbarTitle.text = title
                    }
                    refreshMessages()
                }
            } finally {
                if (usesLocalModels) {
                    localTitleGenerationInProgress = false
                    refreshModelBadge("Ready")
                    drainQueuedLocalPrompts()
                }
                onAssistantRequestFinished()
            }
        }
    }

    private fun drainQueuedLocalPrompts() {
        if (localGenerationRunning || localTitleGenerationInProgress) return
        val next = queuedLocalPrompts.pollFirst() ?: return
        startAssistantGeneration(
            chatId = next.chatId,
            userPrompt = next.userPrompt,
            requestSmartTitleAfterReply = false,
            onFinished = { drainQueuedLocalPrompts() },
        )
    }

    private fun sanitizeThreadTitle(candidate: String?, fallbackPrompt: String): String {
        val raw = candidate
            ?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotBlank() }
            .orEmpty()
            .removePrefix("\"")
            .removeSuffix("\"")
            .removePrefix("'")
            .removeSuffix("'")
            .removePrefix("Title:")
            .removePrefix("title:")
            .trim()

        val normalized = raw
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[\\r\\n\\t]"), " ")
            .trim()

        val looksLikeDemo = normalized.startsWith("Demo mode reply:", ignoreCase = true)
        val clean = if (normalized.isBlank() || looksLikeDemo) {
            fallbackPrompt
        } else {
            normalized
        }

        val withoutTrailingPunctuation = clean.trim().trimEnd('.', '!', '?', ':', ';', ',')
        val words = withoutTrailingPunctuation
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .take(6)

        val compact = words.joinToString(" ").trim().ifBlank { "New chat" }
        return compact.take(48)
    }

    private fun maybeQueueDailySummaryIfDue(date: String) {
        runCatching {
            // Only queue if we have at least one capture for the day.
            val captureUpdatedAt = LocalAgentMemoryStore.screenCaptureLastUpdatedAtMs(this, date)
            if (captureUpdatedAt <= 0L) return

            val lastGenerated = DailySummaryPrefs.getLastGeneratedAtMs(this, date)
            val intervalHours = AutomationPrefs.getDailySummaryAutoRefreshHours(this)
            val intervalMs = intervalHours * 60L * 60L * 1000L
            val due = lastGenerated <= 0L ||
                (System.currentTimeMillis() - lastGenerated) >= intervalMs
            if (!due) return

            WorkManager.getInstance(this).enqueueUniqueWork(
                DailySummaryRegenerateWorker.uniqueWorkName(date),
                ExistingWorkPolicy.KEEP,
                DailySummaryRegenerateWorker.buildRequest(date),
            )
        }
    }

    private suspend fun buildRelayMessages(chatId: String, lastUserPrompt: String): List<Map<String, String>> {
        if (!isDailyFactsReview) {
            val history = ChatStore.listMessages(chatId).map { m ->
                mapOf(
                    "role" to if (m.role == ChatRole.USER) "User" else "Assistant",
                    "content" to m.content,
                )
            }

            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date(System.currentTimeMillis()))

            // Keep daily summary reasonably fresh without blocking chat response/title generation.
            maybeQueueDailySummaryIfDue(today)

            val relevantMemory = LocalAgentMemorySearch.buildRelevantMemoryBlock(
                context = this,
                queryText = lastUserPrompt,
                date = today,
            )

            val extra = if (relevantMemory.isBlank()) {
                emptyList()
            } else {
                listOf(
                    LocalAgentContextBuilder.Section(
                        title = "Relevant memory (search hits)",
                        content = relevantMemory,
                    )
                )
            }

            val ctx = LocalAgentContextBuilder().buildSystemMessageWithDebug(
                context = this,
                date = today,
                extraSections = extra,
            )

            LocalAgentPrefs.setLastContextInjectionDebug(
                context = this,
                debugText = ctx.debug.toMultilineString(),
            )

            val system = ctx.systemMessage
            if (system.isBlank()) return history

            return listOf(mapOf("role" to "System", "content" to system)) + history
        }

        val date = dailyFactsDate ?: java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(System.currentTimeMillis()))

        val state = DailyFactsStorage.load(this, date)
        LocalAgentMemoryStore.ensureSeedFiles(this)
        val userFactsMd = LocalAgentMemoryStore.readText(LocalAgentMemoryStore.userFactsFile(this))
        val candidateUserFacts = CandidateUserFactsStorage.load(this, date)
        val system = DailyFactsReviewProtocol.buildSystemMessage(state, userFactsMd, candidateUserFacts)

        val history = ChatStore.listMessages(chatId).map { m ->
            mapOf(
                "role" to if (m.role == ChatRole.USER) "User" else "Assistant",
                "content" to m.content,
            )
        }

        return listOf(mapOf("role" to "System", "content" to system)) + history
    }

    private fun handleDailyFactsAiReply(chatId: String, raw: String) {
        val date = dailyFactsDate ?: java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(System.currentTimeMillis()))

        try {
            val update = DailyFactsReviewProtocol.parseUpdate(raw)

            if (update.draftFacts != null) {
                DailyFactsStorage.writeDraft(this, date, update.draftFacts)
            }

            if (update.newFacts.isNotEmpty()) {
                val cur = DailyFactsStorage.load(this, date)
                DailyFactsStorage.writeDraft(this, date, cur.draft + update.newFacts)
            }

            val toRemove = update.confirmedFacts + update.rejectedFacts
            if (toRemove.isNotEmpty()) {
                DailyFactsStorage.removeFromDraft(this, date, toRemove)
            }

            if (update.confirmedFacts.isNotEmpty()) {
                DailyFactsStorage.appendConfirmed(this, date, update.confirmedFacts)
            }

            // USER FACTS review flow
            if (update.newUserFactsCandidates.isNotEmpty()) {
                CandidateUserFactsStorage.append(this, date, update.newUserFactsCandidates)
            }

            val resolvedUserFacts = update.confirmedUserFacts + update.rejectedUserFacts
            if (resolvedUserFacts.isNotEmpty()) {
                CandidateUserFactsStorage.remove(this, date, resolvedUserFacts)
            }

            if (update.confirmedUserFacts.isNotEmpty()) {
                UserFactsStorage.appendUniqueFacts(this, update.confirmedUserFacts)
            }

            ChatStore.addMessage(chatId, ChatRole.ASSISTANT, update.assistantMessage)
        } catch (_: Throwable) {
            // Fallback: show raw output
            ChatStore.addMessage(chatId, ChatRole.ASSISTANT, raw)
        }
    }

    private fun refreshMessages() {
        val cid = chatId
        if (cid == null) {
            adapter.submitList(emptyList())
            return
        }
        val stored = ChatStore.listMessages(cid)
        val draft = streamingAssistantDraft
        val msgs = if (draft != null) {
            stored + com.fersaiyan.cyanbridge.chat.ChatMessage(
                id = "streaming-${System.currentTimeMillis()}",
                chatId = cid,
                role = ChatRole.ASSISTANT,
                content = draft.ifBlank { "..." },
                createdAt = System.currentTimeMillis(),
            )
        } else {
            stored
        }
        adapter.submitList(msgs)
        if (msgs.isNotEmpty()) {
            binding.recyclerMessages.smoothScrollToPosition(msgs.size - 1)
        }

        // Update title if changed
        val thread = ChatStore.getThread(cid)
        if (thread != null && thread.title != binding.tvToolbarTitle.text) {
            binding.tvToolbarTitle.text = thread.title
        }
    }

    private fun setupBottomNavigation() {
        // This is a detail view, but keep bottom nav consistent.
        binding.bottomNavigation.selectedItemId = R.id.nav_chats
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_chats -> {
                    // If the user's last message is recent (<30 min), keep this chat open.
                    // Otherwise create a new chat thread.
                    val cid = chatId
                    val now = System.currentTimeMillis()
                    val lastUserAt = if (cid != null) {
                        ChatStore.listMessages(cid)
                            .lastOrNull { it.role == ChatRole.USER }
                            ?.createdAt
                    } else null

                    if (lastUserAt != null && (now - lastUserAt) < 30 * 60 * 1000) {
                        true
                    } else {
                        binding.bottomNavigation.post {
                            startActivity(android.content.Intent(this, ChatThreadActivity::class.java).apply {
                                addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            })
                        }
                        true
                    }
                }

                R.id.nav_glasses -> {
                    binding.bottomNavigation.post {
                        startActivity(android.content.Intent(this, MainActivity::class.java).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        })
                    }
                    true
                }

                R.id.nav_transcriptions_recordings -> {
                    binding.bottomNavigation.post {
                        startActivity(android.content.Intent(this, com.fersaiyan.cyanbridge.ui.recordings.RecordingsListActivity::class.java).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        })
                    }
                    true
                }

                R.id.nav_settings -> {
                    binding.bottomNavigation.post {
                        startActivity(android.content.Intent(this, SettingsActivity::class.java).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        })
                    }
                    true
                }

                R.id.nav_community_plugins -> {
                    binding.bottomNavigation.post {
                        startActivity(android.content.Intent(this, CommunityPluginsActivity::class.java).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        })
                    }
                    true
                }

                else -> false
            }
        }
    }

    companion object {
        const val EXTRA_CHAT_ID = "chat_id"

        // If EXTRA_CHAT_ID is missing, ChatThreadActivity can create a new thread.
        const val EXTRA_CREATE_THREAD_TITLE = "create_thread_title"

        // Optional: prefill the composer input box.
        const val EXTRA_PREFILL_MESSAGE = "prefill_message"

        // Daily facts review mode
        const val EXTRA_DAILY_FACTS_REVIEW = "daily_facts_review"
        const val EXTRA_DAILY_FACTS_DATE = "daily_facts_date"
    }
}
