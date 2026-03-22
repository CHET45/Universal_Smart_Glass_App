package com.fersaiyan.cyanbridge.localmodels.session

import android.content.Context
import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogEntry
import com.fersaiyan.cyanbridge.localmodels.device.DeviceCapabilityService
import com.fersaiyan.cyanbridge.localmodels.engine.EngineLoadConfig
import com.fersaiyan.cyanbridge.localmodels.engine.GenerationConfig
import com.fersaiyan.cyanbridge.localmodels.engine.GenerationResult
import com.fersaiyan.cyanbridge.localmodels.engine.LlamaCppLocalInferenceEngine
import com.fersaiyan.cyanbridge.localmodels.engine.LocalInferenceEngine
import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend
import com.fersaiyan.cyanbridge.localmodels.settings.LocalGenerationSettings
import com.fersaiyan.cyanbridge.localmodels.storage.InstalledLocalModel
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

sealed class LocalSessionState {
    data object Idle : LocalSessionState()
    data object ModelNotLoaded : LocalSessionState()
    data class Loading(val modelId: String) : LocalSessionState()
    data class Ready(val modelId: String) : LocalSessionState()
    data class Generating(val modelId: String, val requestId: String) : LocalSessionState()
    data class Error(val message: String) : LocalSessionState()
}

data class LocalSessionSnapshot(
    val state: LocalSessionState,
    val loadedModelId: String?,
    val loadedModelPath: String?,
    val activeRequestId: String?,
    val activeBackend: LocalComputeBackend?,
    val gpuFallbackMessage: String?,
)

data class LocalModelLoadDetails(
    val activeBackend: LocalComputeBackend,
    val activeGpuLayers: Int,
    val fallbackReason: String?,
)

data class LocalWarmupProbeResult(
    val promptTokens: Int,
    val generatedTokens: Int,
    val elapsedMs: Long,
    val backend: LocalComputeBackend,
    val fallbackReason: String?,
) {
    val totalTokens: Int get() = (promptTokens + generatedTokens).coerceAtLeast(1)
}

object LocalChatSessionManager {
    private val mutex = Mutex()
    private val generationMutex = Mutex()

    private var engine: LocalInferenceEngine? = null
    private var loadedModelId: String? = null
    private var loadedModelPath: String? = null
    private var loadedConfig: EngineLoadConfig? = null
    private var activeBackend: LocalComputeBackend? = null
    private var activeGpuLayers: Int = 0
    private var gpuFallbackMessage: String? = null
    private var activeRequestId: String? = null
    private var state: LocalSessionState = LocalSessionState.ModelNotLoaded

    suspend fun snapshot(): LocalSessionSnapshot = mutex.withLock {
        LocalSessionSnapshot(
            state = state,
            loadedModelId = loadedModelId,
            loadedModelPath = loadedModelPath,
            activeRequestId = activeRequestId,
            activeBackend = activeBackend,
            gpuFallbackMessage = gpuFallbackMessage,
        )
    }

    suspend fun ensureModelLoaded(
        context: Context,
        model: InstalledLocalModel,
        catalogEntry: LocalModelCatalogEntry?,
        settings: LocalGenerationSettings,
    ): LocalModelLoadDetails {
        return mutex.withLock {
            val file = File(model.absolutePath)
            if (!file.exists()) {
                LocalModelStorageRepository.removeInstalled(context, model.id)
                state = LocalSessionState.Error("Selected local model file is missing")
                throw IllegalStateException("Selected model file is missing. Re-import or download again.")
            }

            val capabilityEntry = catalogEntry ?: LocalModelCatalogEntry(
                id = model.id,
                displayName = model.displayName,
                family = "custom",
                sourceUrl = null,
                sourcePageUrl = null,
                expectedFilename = model.fileName,
                sha256 = model.sha256,
                sizeBytes = model.sizeBytes,
                quantization = model.quantization ?: "unknown",
                contextSizeDefault = settings.contextSize,
                promptTemplateId = model.promptTemplateId ?: "generic_chatml",
                minRamGb = 4.0,
                minStorageGb = 1.0,
                shortDescription = "Imported GGUF model",
                tags = listOf("offline"),
                gatedDownload = false,
                licenseTermsNote = model.licenseTermsNote ?: "Respect the source model license.",
                enabled = true,
            )

            val capability = DeviceCapabilityService.assess(
                snapshot = DeviceCapabilityService.snapshot(context),
                entry = capabilityEntry,
                requireDownloadHeadroom = false,
            )
            if (!capability.supported) {
                state = LocalSessionState.Error(capability.blockers.joinToString(" "))
                throw IllegalStateException(capability.blockers.joinToString(" "))
            }

            val loadConfig = EngineLoadConfig(
                contextSize = settings.contextSize.coerceIn(1024, 8192),
                cpuThreads = settings.cpuThreads.coerceIn(1, 16),
                computeBackend = settings.computeBackend,
                gpuLayers = settings.gpuLayers.coerceIn(-1, 999),
            )

            if (
                loadedModelId == model.id &&
                loadedModelPath == model.absolutePath &&
                loadedConfig == loadConfig
            ) {
                state = LocalSessionState.Ready(model.id)
                return@withLock LocalModelLoadDetails(
                    activeBackend = activeBackend ?: settings.computeBackend,
                    activeGpuLayers = if (activeBackend == LocalComputeBackend.GPU_EXPERIMENTAL) {
                        activeGpuLayers
                    } else {
                        0
                    },
                    fallbackReason = gpuFallbackMessage,
                )
            }

            state = LocalSessionState.Loading(model.id)
            val llm = engine ?: LlamaCppLocalInferenceEngine().also { engine = it }

            runCatching {
                llm.loadModel(
                    modelPath = model.absolutePath,
                    config = loadConfig,
                )
            }.onFailure {
                state = LocalSessionState.Error(it.message ?: "Failed to load local model")
                throw it
            }.onSuccess { loadResult ->
                activeBackend = loadResult.activeBackend
                activeGpuLayers = loadResult.activeGpuLayers
                gpuFallbackMessage = loadResult.fallbackReason
            }

            loadedModelId = model.id
            loadedModelPath = model.absolutePath
            loadedConfig = loadConfig
            state = LocalSessionState.Ready(model.id)

            return@withLock LocalModelLoadDetails(
                activeBackend = activeBackend ?: settings.computeBackend,
                activeGpuLayers = if (activeBackend == LocalComputeBackend.GPU_EXPERIMENTAL) {
                    activeGpuLayers
                } else {
                    0
                },
                fallbackReason = gpuFallbackMessage,
            )
        }
    }

    suspend fun streamGenerate(
        settings: LocalGenerationSettings,
        prompt: String,
        onToken: (String) -> Unit,
    ): String {
        return generateInternal(settings = settings, prompt = prompt, onToken = onToken).text
    }

    private suspend fun generateInternal(
        settings: LocalGenerationSettings,
        prompt: String,
        onToken: (String) -> Unit,
    ): GenerationResult {
        val reqId = UUID.randomUUID().toString()
        val llm: LocalInferenceEngine
        val modelId: String

        mutex.withLock {
            llm = engine ?: throw IllegalStateException("Local engine not initialized")
            modelId = loadedModelId ?: throw IllegalStateException("No local model loaded")
        }

        return generationMutex.withLock {
            mutex.withLock {
                activeRequestId = reqId
                state = LocalSessionState.Generating(modelId, reqId)
            }

            val result = runCatching {
                withContext(Dispatchers.IO) {
                    llm.generate(
                        config = GenerationConfig(
                            prompt = prompt,
                            temperature = settings.temperature,
                            topP = settings.topP,
                            topK = settings.topK,
                            maxTokens = settings.maxTokens,
                            repetitionPenalty = settings.repetitionPenalty,
                            seed = settings.seed,
                            structuredJson = settings.experimentalStructuredJson,
                        ),
                        onToken = onToken,
                    )
                }
            }

            mutex.withLock {
                activeRequestId = null
                result.fold(
                    onSuccess = {
                        state = LocalSessionState.Ready(modelId)
                        it
                    },
                    onFailure = { err ->
                        state = LocalSessionState.Error(err.message ?: "Local generation failed")
                        throw err
                    },
                )
            }
        }
    }

    suspend fun cancelActiveGeneration() {
        val llm = mutex.withLock { engine } ?: return
        runCatching { llm.cancelGeneration() }
    }

    suspend fun unload() {
        mutex.withLock {
            runCatching { engine?.unloadModel() }
            loadedModelId = null
            loadedModelPath = null
            loadedConfig = null
            activeBackend = null
            activeGpuLayers = 0
            gpuFallbackMessage = null
            activeRequestId = null
            state = LocalSessionState.ModelNotLoaded
        }
    }

    suspend fun runWarmupProbe(
        settings: LocalGenerationSettings,
        onToken: (String) -> Unit,
    ): LocalWarmupProbeResult {
        val backend = mutex.withLock { activeBackend ?: settings.computeBackend }
        val benchmarkPrompt = if (backend == LocalComputeBackend.GPU_EXPERIMENTAL) {
            "Reply with only: OK"
        } else {
            "Count numbers from 1 to 20 separated by spaces on one line."
        }
        val warmupTokens = if (backend == LocalComputeBackend.GPU_EXPERIMENTAL) {
            settings.maxTokens.coerceIn(4, 12)
        } else {
            settings.maxTokens.coerceIn(16, 48)
        }

        val start = System.currentTimeMillis()
        val result = generateInternal(
            settings = settings.copy(
                maxTokens = warmupTokens,
                experimentalStructuredJson = false,
            ),
            prompt = benchmarkPrompt,
            onToken = onToken,
        )
        val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)

        val llm = mutex.withLock { engine }
        val promptTokens = try {
            llm?.tokenizeCount(benchmarkPrompt)?.coerceAtLeast(1) ?: 1
        } catch (_: Throwable) {
            1
        }
        val generatedTokens = try {
            llm?.tokenizeCount(result.text)?.coerceAtLeast(1)
        } catch (_: Throwable) {
            null
        }
            ?: result.tokenCount.coerceAtLeast(1)

        val fallback = mutex.withLock { gpuFallbackMessage }

        return LocalWarmupProbeResult(
            promptTokens = promptTokens,
            generatedTokens = generatedTokens,
            elapsedMs = elapsed,
            backend = backend,
            fallbackReason = fallback,
        )
    }
}
