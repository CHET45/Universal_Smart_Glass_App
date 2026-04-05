package com.fersaiyan.cyanbridge.localmodels.provider

import android.content.Context
import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogRepository
import com.fersaiyan.cyanbridge.localmodels.session.LocalChatSessionManager
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelSettingsRepository
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelRuntime
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.fersaiyan.cyanbridge.localmodels.templates.PromptMessage
import com.fersaiyan.cyanbridge.localmodels.templates.PromptTemplateRegistry
import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class LocalModelRequestPriority {
    HIGH,
    LOW,
}

class LocalModelsProvider {
    companion object {
        const val STATUS_MAX_TOKENS_REACHED = "__MAX_TOKENS_REACHED__"
    }

    suspend fun streamChat(
        context: Context,
        messages: List<Map<String, String>>,
        onStatus: ((String) -> Unit)? = null,
        onToken: ((String) -> Unit)? = null,
        imagePaths: List<String> = emptyList(),
        audioPath: String? = null,
        requestPriority: LocalModelRequestPriority = LocalModelRequestPriority.HIGH,
        maxTokens: Int? = null,
    ): String {
        return withContext(Dispatchers.IO) {
            LocalModelStorageRepository.cleanupMissingModels(context)
            val selected = LocalModelStorageRepository.resolveSelectedModel(context)
                ?: throw IllegalStateException(
                    "No local model is installed. Open Configure Local Models to download or import a GGUF model first.",
                )

            val catalogEntry = LocalModelCatalogRepository.findById(selected.catalogId)
            val settings = LocalModelSettingsRepository.getForModel(context, selected.id)
            val hasMediaAttachments = imagePaths.isNotEmpty() || !audioPath.isNullOrBlank()
            if (hasMediaAttachments && settings.modelRuntime != LocalModelRuntime.LITERT) {
                throw IllegalStateException("Media attachments require Local Runtime = LiteRT for the selected model.")
            }
            val templateId = settings.templateOverrideId
                ?: selected.promptTemplateId
                ?: catalogEntry?.promptTemplateId
                ?: "generic_chatml"

            val systemPrompt = buildString {
                val settingsSystem = settings.systemPromptOverride.trim()
                if (settingsSystem.isNotBlank()) {
                    append(settingsSystem)
                }
            }

val chatMessages = messages
            .mapNotNull { m ->
                val role = m["role"]?.trim().orEmpty()
                val content = m["content"]?.trim().orEmpty()
                if (role.isBlank() || content.isBlank()) null else PromptMessage(role = role, content = content)
            }

        // For multimodal (image/audio) prompts, LiteRT's Conversation API handles turn formatting.
        // We should send the raw user message content, not the template-wrapped prompt.
        val effectivePrompt = if (hasMediaAttachments) {
            // Extract just the user's message content (last user message)
            chatMessages.lastOrNull { it.role.equals("user", ignoreCase = true) }?.content ?: chatMessages.lastOrNull()?.content ?: ""
        } else {
            // For text-only, use the full template-rendered prompt
            PromptTemplateRegistry.renderPrompt(
                templateId = templateId,
                systemPrompt = systemPrompt,
                messages = chatMessages,
            )
        }

        onStatus?.invoke("Loading ${selected.displayName}...")
            val loadDetails = LocalChatSessionManager.ensureModelLoaded(
                context = context,
                model = selected,
                catalogEntry = catalogEntry,
                settings = settings,
            )
onStatus?.invoke(generationStatus(loadDetails.activeBackend))
        if (!loadDetails.fallbackReason.isNullOrBlank()) {
            if (loadDetails.activeBackend == LocalComputeBackend.CPU) {
                onStatus?.invoke("GPU unavailable, using CPU")
            } else {
                onStatus?.invoke("GPU active (audio/vision backend disabled)")
            }
        }

val firstReply = LocalChatSessionManager.streamGenerate(
            settings = settings,
            prompt = effectivePrompt,
            onToken = { token -> onToken?.invoke(token) },
            imagePaths = imagePaths,
            audioPath = audioPath,
            requestPriority = requestPriority,
            maxTokensOverride = maxTokens,
        )

            val firstCapped = LocalChatSessionManager.consumeLastGenerationCappedFlag()
            if (firstCapped) {
                onStatus?.invoke(STATUS_MAX_TOKENS_REACHED)
            }

            if (firstReply.isNotBlank()) {
                return@withContext firstReply
            }

            onStatus?.invoke("Loading model and retrying...")
            runCatching { LocalChatSessionManager.unload() }

            onStatus?.invoke("Reloading ${selected.displayName}...")
            val reloadDetails = LocalChatSessionManager.ensureModelLoaded(
                context = context,
                model = selected,
                catalogEntry = catalogEntry,
                settings = settings,
            )
            onStatus?.invoke(generationStatus(reloadDetails.activeBackend))
            if (!reloadDetails.fallbackReason.isNullOrBlank()) {
                if (reloadDetails.activeBackend == LocalComputeBackend.CPU) {
                    onStatus?.invoke("GPU unavailable, using CPU")
                } else {
                    onStatus?.invoke("GPU active (audio/vision backend disabled)")
                }
            }

val retryReply = LocalChatSessionManager.streamGenerate(
            settings = settings,
            prompt = effectivePrompt,
            onToken = { token -> onToken?.invoke(token) },
            imagePaths = imagePaths,
            audioPath = audioPath,
            requestPriority = requestPriority,
            maxTokensOverride = maxTokens,
        )

            val retryCapped = LocalChatSessionManager.consumeLastGenerationCappedFlag()
            if (retryCapped) {
                onStatus?.invoke(STATUS_MAX_TOKENS_REACHED)
            }

            if (retryReply.isNotBlank()) {
                retryReply
            } else {
                "I couldn't generate a reply yet. The local model was reloaded, please try once more."
            }
        }
    }

    suspend fun cancelGeneration() {
        LocalChatSessionManager.cancelActiveGeneration()
    }

    private fun generationStatus(backend: LocalComputeBackend): String {
        return if (backend == LocalComputeBackend.GPU_EXPERIMENTAL) {
            "Generating (GPU)..."
        } else {
            "Generating (CPU)..."
        }
    }
}
