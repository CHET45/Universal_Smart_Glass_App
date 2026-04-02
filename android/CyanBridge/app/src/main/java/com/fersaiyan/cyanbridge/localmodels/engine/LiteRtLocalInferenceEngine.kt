package com.fersaiyan.cyanbridge.localmodels.engine

import android.content.Context
import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend
import com.fersaiyan.cyanbridge.ui.MyApplication
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LiteRtLocalInferenceEngine(private val context: Context = MyApplication.CONTEXT) : LocalInferenceEngine {
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var activeConversation: Conversation? = null
    private var modelPath: String? = null
    private var activeLoadConfig: EngineLoadConfig? = null
    private var activeLoadResult: EngineLoadResult? = null

    override suspend fun loadModel(modelPath: String, config: EngineLoadConfig): EngineLoadResult {
        val current = mutex.withLock {
            if (
                this.modelPath == modelPath &&
                engine != null &&
                activeLoadConfig == config
            ) {
                return@withLock activeLoadResult ?: EngineLoadResult(
                    activeBackend = config.computeBackend,
                    activeGpuLayers = 0,
                )
            }
            null
        }
        if (current != null) return current

        val loadOutcome = withContext(Dispatchers.IO) {
            if (config.computeBackend == LocalComputeBackend.GPU_EXPERIMENTAL) {
                runCatching {
                    createInitializedEngine(modelPath, Backend.GPU()) to EngineLoadResult(
                        activeBackend = LocalComputeBackend.GPU_EXPERIMENTAL,
                        activeGpuLayers = config.gpuLayers.coerceAtLeast(0),
                    )
                }.recoverCatching { gpuErr ->
                    createInitializedEngine(modelPath, Backend.CPU(config.cpuThreads)) to EngineLoadResult(
                        activeBackend = LocalComputeBackend.CPU,
                        activeGpuLayers = 0,
                        fallbackReason = "LiteRT GPU backend unavailable (${compactError(gpuErr)}). Fell back to CPU.",
                    )
                }.getOrThrow()
            } else {
                createInitializedEngine(modelPath, Backend.CPU(config.cpuThreads)) to EngineLoadResult(
                    activeBackend = LocalComputeBackend.CPU,
                    activeGpuLayers = 0,
                )
            }
        }

        return mutex.withLock {
            closeConversationLocked()
            closeEngineLocked()

            this.engine = loadOutcome.first
            this.modelPath = modelPath
            this.activeLoadConfig = config
            this.activeLoadResult = loadOutcome.second
            loadOutcome.second
        }
    }

    private fun createInitializedEngine(modelPath: String, backend: Backend): Engine {
        val config = EngineConfig(
            modelPath = modelPath,
            backend = backend,
            visionBackend = null,
            audioBackend = null,
            maxNumTokens = null,
            cacheDir = context.cacheDir.path,
        )
        return Engine(config).also { it.initialize() }
    }

    override suspend fun unloadModel() {
        mutex.withLock {
            closeConversationLocked()
            closeEngineLocked()
            modelPath = null
            activeLoadConfig = null
            activeLoadResult = null
        }
    }

    override suspend fun generate(config: GenerationConfig, onToken: (String) -> Unit): GenerationResult {
        val llm = mutex.withLock {
            engine ?: throw IllegalStateException("LiteRT engine is not initialized")
        }

        val conversation = withContext(Dispatchers.IO) {
            llm.createConversation(buildConversationConfig(config))
        }

        mutex.withLock {
            closeConversationLocked()
            activeConversation = conversation
        }

        return try {
            val text = withContext(Dispatchers.IO) {
                var lastText = ""
                var finalText = ""

                val stream = conversation.sendMessageAsync(config.prompt, emptyMap<String, Any>())
                stream.collect { message ->
                    val current = extractText(message)
                    if (current.isBlank()) return@collect
                    val delta = incrementalDelta(previous = lastText, current = current)
                    if (delta.isNotEmpty()) {
                        onToken(delta)
                    }
                    lastText = current
                    finalText = current
                }

                if (finalText.isBlank()) {
                    val fallback = extractText(conversation.sendMessage(config.prompt, emptyMap<String, Any>()))
                    if (fallback.isNotBlank()) {
                        onToken(fallback)
                    }
                    fallback
                } else {
                    finalText
                }
            }

            GenerationResult(
                text = text,
                tokenCount = tokenizeEstimate(text),
            )
        } finally {
            mutex.withLock {
                if (activeConversation === conversation) {
                    closeConversationLocked()
                }
            }
        }
    }

    override suspend fun cancelGeneration() {
        val conv = mutex.withLock { activeConversation } ?: return
        withContext(Dispatchers.IO) {
            runCatching { conv.cancelProcess() }
        }
    }

    override suspend fun tokenizeCount(text: String): Int {
        return tokenizeEstimate(text)
    }

    override fun isModelLoaded(): Boolean = engine != null

    override fun loadedModelPath(): String? = modelPath

    private fun buildConversationConfig(config: GenerationConfig): ConversationConfig {
        val sampler = SamplerConfig(
            topK = config.topK.coerceIn(1, 200),
            topP = config.topP.coerceIn(0.0, 1.0),
            temperature = config.temperature.coerceIn(0.0, 2.0),
            seed = config.seed,
        )
        val systemInstruction = Contents.Companion.of("")
        val initialMessages = emptyList<Message>()
        val tools = emptyList<ToolProvider>()
        return ConversationConfig(
            systemInstruction = systemInstruction,
            initialMessages = initialMessages,
            tools = tools,
            samplerConfig = sampler,
            automaticToolCalling = true,
        )
    }

    private fun extractText(message: Message): String {
        val chunks = message.contents.contents
            .mapNotNull { content ->
                when (content) {
                    is Content.Text -> content.text
                    else -> null
                }
            }
        return if (chunks.isNotEmpty()) chunks.joinToString(separator = "") else message.toString()
    }

    private fun closeConversationLocked() {
        runCatching { activeConversation?.close() }
        activeConversation = null
    }

    private fun incrementalDelta(previous: String, current: String): String {
        if (previous.isBlank()) return current
        if (current.startsWith(previous)) {
            return current.substring(previous.length)
        }
        if (previous.startsWith(current)) {
            return ""
        }

        val maxPrefix = previous.length.coerceAtMost(current.length)
        var overlap = 0
        var i = maxPrefix
        while (i > 0) {
            if (previous.endsWith(current.substring(0, i))) {
                overlap = i
                break
            }
            i--
        }
        return current.substring(overlap)
    }

    private fun closeEngineLocked() {
        runCatching { engine?.close() }
        engine = null
    }

    private fun compactError(err: Throwable?): String {
        if (err == null) return "unknown error"
        val msg = err.message
            ?.replace('\n', ' ')
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
        return if (msg.isNullOrEmpty()) err::class.java.simpleName else msg
    }

    private fun tokenizeEstimate(text: String): Int {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size
        return (words * 1.5).toInt().coerceAtLeast(1)
    }
}
