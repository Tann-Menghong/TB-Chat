package com.tannmenghong.tbchat.inference.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.tannmenghong.tbchat.inference.api.AiModel
import com.tannmenghong.tbchat.inference.api.ChatEvent
import com.tannmenghong.tbchat.inference.api.ChatMessage
import com.tannmenghong.tbchat.inference.api.License
import com.tannmenghong.tbchat.inference.api.LicenseClass
import com.tannmenghong.tbchat.inference.api.LoadOptions
import com.tannmenghong.tbchat.inference.api.Modality
import com.tannmenghong.tbchat.inference.api.ModelFile
import com.tannmenghong.tbchat.inference.api.ModelFormat
import com.tannmenghong.tbchat.inference.api.Quantization
import com.tannmenghong.tbchat.inference.api.RuntimeId
import com.tannmenghong.tbchat.inference.api.SamplingParams
import com.tannmenghong.tbchat.inference.llamacpp.LlamaCppChatEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Runs in `:inference`, a process of its own.
 *
 * This is the crash-domain boundary described in the architecture notes. Native
 * inference does fail -- a malformed GGUF, a driver bug, a 6 GB allocation on a
 * 6 GB phone -- and in a single-process app each of those takes the UI, the
 * conversation being streamed, and any in-flight download with it.
 *
 * Isolating it buys three things:
 *   - a crash arrives in the UI as a recoverable service disconnect;
 *   - the low-memory killer picks the fat process first, leaving the UI alive to
 *     explain what happened and offer a smaller model;
 *   - the OS reclaims every native byte on process death, which is far more
 *     reliable than trusting a C++ library to unwind cleanly.
 *
 * Hilt is deliberately not used here. Injecting into a second process would drag
 * the whole application graph into it for the sake of one object.
 */
class InferenceService : Service() {

    private val engine = LlamaCppChatEngine()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var loadedModelId: String? = null

    @Volatile
    private var residentBytes: Long = 0L

    @Volatile
    private var generationJob: Job? = null

    private val binder = object : IInferenceService.Stub() {

        override fun isEngineAvailable(): Boolean = engine.isAvailable

        override fun loadedModelId(): String? = loadedModelId

        override fun residentBytes(): Long = residentBytes

        override fun load(
            modelId: String,
            modelPath: String,
            contextLength: Int,
            threads: Int,
            kvCacheBits: Int,
            flashAttention: Boolean,
            gpuLayers: Int,
            callback: IInferenceCallback
        ) {
            scope.launch {
                // Any resident model is dropped before the next one is opened.
                // Two large models are never in memory at the same time, and
                // this is the only place a model can be loaded, so that is
                // structural rather than a convention.
                if (loadedModelId != null) {
                    engine.unload()
                    loadedModelId = null
                    residentBytes = 0L
                }

                val options = LoadOptions(
                    threads = threads,
                    contextLength = contextLength,
                    kvCacheType = if (kvCacheBits == 8) Quantization.Q8_0 else Quantization.F16,
                    gpuLayers = gpuLayers,
                    flashAttention = flashAttention
                )

                val descriptor = descriptorFor(modelId, modelPath)
                engine.load(descriptor, modelPath, options)
                    .onSuccess {
                        loadedModelId = modelId
                        residentBytes = engine.estimateResidentBytes(descriptor, options)
                        callback.safely {
                            onLoaded(contextLength, residentBytes)
                        }
                    }
                    .onFailure { throwable ->
                        loadedModelId = null
                        Log.e(TAG, "load failed for $modelId", throwable)
                        callback.safely {
                            onError(ERROR_LOAD, throwable.message ?: "Could not load the model")
                        }
                    }
            }
        }

        override fun unload() {
            scope.launch {
                generationJob?.cancel()
                engine.unload()
                loadedModelId = null
                residentBytes = 0L
                stopForegroundCompat()
            }
        }

        override fun generate(
            roles: Array<String>,
            contents: Array<String>,
            temperature: Float,
            topP: Float,
            topK: Int,
            minP: Float,
            repeatPenalty: Float,
            maxTokens: Int,
            seed: Long,
            callback: IInferenceCallback
        ) {
            generationJob?.cancel()
            generationJob = scope.launch {
                startForegroundCompat()
                try {
                    val messages = roles.indices.map { i ->
                        ChatMessage(roleOf(roles[i]), contents[i])
                    }
                    val params = SamplingParams(
                        temperature = temperature,
                        topP = topP,
                        topK = topK,
                        minP = minP,
                        repeatPenalty = repeatPenalty,
                        maxTokens = maxTokens,
                        seed = seed
                    )

                    engine.generate(messages, params).collect { event ->
                        when (event) {
                            is ChatEvent.Token -> callback.safely { onToken(event.text) }
                            is ChatEvent.PromptProcessing ->
                                callback.safely { onPrefill(event.done, event.total) }

                            is ChatEvent.Done -> callback.safely {
                                onDone(
                                    longArrayOf(
                                        event.stats.promptTokens.toLong(),
                                        event.stats.generatedTokens.toLong(),
                                        // Rates travel scaled by 1000 so the
                                        // long[] keeps three decimal places.
                                        (event.stats.prefillTokensPerSec * 1000).toLong(),
                                        (event.stats.decodeTokensPerSec * 1000).toLong(),
                                        event.stats.firstTokenMs,
                                        event.stats.totalMs,
                                        event.stats.contextUsed.toLong(),
                                        event.stats.contextTotal.toLong(),
                                        event.stats.stopReason.ordinal.toLong()
                                    )
                                )
                            }

                            is ChatEvent.Error ->
                                callback.safely { onError(ERROR_GENERATE, event.error.userMessage) }
                        }
                    }
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    Log.e(TAG, "generation failed", t)
                    callback.safely { onError(ERROR_GENERATE, t.message ?: "Generation failed") }
                } finally {
                    stopForegroundCompat()
                }
            }
        }

        override fun cancel() {
            generationJob?.cancel()
        }

        override fun tokenCount(text: String): Int = runBlocking { engine.tokenCount(text) }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        runBlocking { engine.unload() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The system asking for memory back is the clearest possible signal that
        // holding several gigabytes of weights is no longer reasonable.
        if (level >= TRIM_MEMORY_RUNNING_LOW && generationJob?.isActive != true) {
            scope.launch {
                engine.unload()
                loadedModelId = null
                residentBytes = 0L
            }
        }
    }

    /**
     * A generation can run for minutes. Without a foreground service the process
     * is a background one and gets killed mid-answer, especially on OEM skins
     * that trim aggressively.
     */
    private fun startForegroundCompat() {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                manager.getNotificationChannel(CHANNEL_ID) == null
            ) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Running a model",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { setShowBadge(false) }
                )
            }

            val notification: Notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Generating a reply")
                .setContentText("Running on this device")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { Log.w(TAG, "could not enter the foreground", it) }
    }

    private fun stopForegroundCompat() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    private fun roleOf(wire: String): ChatMessage.Role = when (wire) {
        "system" -> ChatMessage.Role.SYSTEM
        "assistant" -> ChatMessage.Role.ASSISTANT
        else -> ChatMessage.Role.USER
    }

    /**
     * The service is a dumb GGUF runner: it never sees catalog metadata. The
     * engine still wants an AiModel, so we synthesise a minimal one from the
     * file itself. Only the size matters here, and that comes from the file.
     */
    private fun descriptorFor(modelId: String, modelPath: String): AiModel {
        val file = File(modelPath)
        return AiModel(
            id = modelId,
            displayName = file.nameWithoutExtension,
            publisher = "",
            modalities = setOf(Modality.CHAT),
            format = ModelFormat.GGUF,
            quantization = Quantization.fromFileName(file.name),
            // Null architecture makes the estimator fall back to a flat
            // multiplier on file size, which is what we want here: the real
            // architecture-aware check already ran in the UI process before this
            // load was ever requested.
            arch = null,
            files = listOf(
                ModelFile(
                    id = "$modelId-local",
                    repoId = "",
                    revision = "",
                    path = file.name,
                    sizeBytes = file.length()
                )
            ),
            license = License("unknown", "See model page", LicenseClass.PERMISSIVE, ""),
            requiredRuntime = RuntimeId.LLAMA_CPP,
            sourceUrl = ""
        )
    }

    /** A dead client must not take the inference process down with it. */
    private inline fun IInferenceCallback.safely(block: IInferenceCallback.() -> Unit) {
        runCatching { block() }.onFailure { Log.w(TAG, "callback failed; client is probably gone") }
    }

    companion object {
        private const val TAG = "InferenceService"
        private const val CHANNEL_ID = "inference"
        private const val NOTIFICATION_ID = 4201

        const val ERROR_LOAD = 1
        const val ERROR_GENERATE = 2
        const val ERROR_UNAVAILABLE = 3
    }
}
