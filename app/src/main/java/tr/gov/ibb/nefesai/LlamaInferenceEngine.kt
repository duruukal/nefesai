package tr.gov.ibb.nefesai.internal

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.nehuatl.llamacpp.LlamaHelper
import tr.gov.ibb.nefesai.NefesConstants
import tr.gov.ibb.nefesai.DeviceRAMClass
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class LlamaInferenceEngine private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val contentResolver = appContext.contentResolver

    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val llmFlow = MutableSharedFlow<LlamaHelper.LLMEvent>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var llamaHelper: LlamaHelper? = null

    @Volatile private var isGenerating = false
    @Volatile private var isModelLoaded = false
    @Volatile private var currentModelPath: String? = null

    private val nativeMutex = Mutex()

    private val activeStopSequences: Set<String>
        get() = NefesConstants.currentTemplate.stopStrings.toSet()

    companion object {
        private const val TAG = "LlamaInferenceEngine"

        @Volatile private var instance: LlamaInferenceEngine? = null

        @JvmStatic
        fun getInstance(context: Context): LlamaInferenceEngine =
            instance ?: synchronized(this) {
                instance ?: LlamaInferenceEngine(context).also { instance = it }
            }

        @JvmStatic
        fun getInstance(): LlamaInferenceEngine =
            instance ?: throw IllegalStateException(
                "LlamaInferenceEngine henüz initialize edilmedi! Önce getInstance(context) çağrısı yapın."
            )
    }

    data class GenerationParams(
        val temperature: Float = 0.15f,
        val maxTokens: Int = 128,
        val topP: Float = 0.85f,
        val topK: Int = 20,
        val repeatPenalty: Float = 1.25f,
        val contextLength: Int = 2048,
        // numThreads artık yalnızca bilgi amaçlı — helper n_threads=0 ile llama.cpp'e bırakıyor
        val numThreads: Int = Runtime.getRuntime().availableProcessors().coerceIn(4, 8),
        val useMmap: Boolean = true,
        val flashAttention: Boolean = true,
        val batchSize: Int = 512,
        val gpuLayers: Int = -1
    ) {
        companion object {
            fun createForDevice(ramClass: DeviceRAMClass): GenerationParams {
                return when (ramClass) {
                    // ~1.45 GB model — LOW RAM cihazlarda context kısıtlı tut
                    DeviceRAMClass.LOW -> GenerationParams(
                        contextLength = 512,
                        batchSize = 16,
                        gpuLayers = 0,
                        maxTokens = 128
                    )
                    DeviceRAMClass.STANDARD -> GenerationParams(
                        contextLength = 512,
                        batchSize = 256,
                        gpuLayers = 0,   // helper n_gpu_layers=0 sabit, bu değer şu an etkisiz
                        maxTokens = 256
                    )
                    DeviceRAMClass.HIGH -> GenerationParams(
                        contextLength = 2048,
                        batchSize = 512,
                        gpuLayers = 0,
                        maxTokens = 512
                    )
                }
            }
        }
    }

    private var genParams = GenerationParams()

    suspend fun loadModel(modelPath: String, params: GenerationParams? = null): String =
        suspendCancellableCoroutine { continuation ->

            // Aynı model zaten yüklüyse tekrar yükleme
            if (isModelLoaded && currentModelPath == modelPath && llamaHelper != null) {
                //Log.i(TAG, "loadModel: Model zaten yüklü.")
                continuation.resume("ALREADY_LOADED")
                return@suspendCancellableCoroutine
            }

            val ramClass = DeviceRAMClass.getCurrent(appContext)
            genParams = params ?: GenerationParams.createForDevice(ramClass)

            isModelLoaded = false
            currentModelPath = modelPath

            llamaHelper = LlamaHelper(contentResolver, llmFlow)

            val fileUri = Uri.fromFile(File(modelPath)).toString()

            try {
                Log.i(TAG, "loadModel: Yükleme başlatıldı → $fileUri")
                llamaHelper?.load(
                    path = fileUri,
                    contextLength = genParams.contextLength,
                    batchSize = genParams.batchSize,
                    threads = if (Runtime.getRuntime().availableProcessors() > 4) 4 else 2
                ) { modelId ->
                    isModelLoaded = true
                    continuation.resume(modelId.toString())
                }
            } catch (e: Exception) {
                isModelLoaded = false
                currentModelPath = null
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            continuation.invokeOnCancellation {
                Log.w(TAG, "loadModel: Coroutine iptal edildi.")
                isModelLoaded = false
                currentModelPath = null
                llamaHelper = null
            }
    }

    fun sendUserPrompt(pureFormattedPrompt: String): Flow<String> = callbackFlow {
        val helper = llamaHelper ?: run {
            close(IllegalStateException("Model henüz yüklenmedi!"))
            return@callbackFlow
        }

        var tokenBuffer = ""

        // UI veya başka bir katmandan gelen mükerrer istekleri engellemek için Mutex kullanımı
        val isLockAcquired = nativeMutex.tryLock()
        if (!isLockAcquired) {
            close(IllegalStateException("Engine şu an başka bir işlem yürütüyor!"))
            return@callbackFlow
        }

        // Collect işlemini yönetecek lokal job
        val collectJob = engineScope.launch {
            try {
                llmFlow.collect { event ->
                    when (event) {
                        is LlamaHelper.LLMEvent.Ongoing -> {
                            tokenBuffer += event.word

                            val stopFound = activeStopSequences.any { tokenBuffer.contains(it) }
                            if (stopFound) {
                                val cutIndex = activeStopSequences
                                    .mapNotNull { seq ->
                                        val idx = tokenBuffer.indexOf(seq)
                                        if (idx >= 0) idx else null
                                    }.minOrNull() ?: tokenBuffer.length

                                val clean = tokenBuffer.substring(0, cutIndex).trim()
                                if (clean.isNotEmpty()) trySend(clean).isSuccess

                                isGenerating = false
                                close() // Akışı başarılı kapat
                            }

                            // Son token parçalanması ihtimaline karşı safe-buffer aralığı
                            val safeLength = (tokenBuffer.length - 20).coerceAtLeast(0)
                            if (safeLength > 0 && !stopFound) {
                                val flush = tokenBuffer.substring(0, safeLength)
                                tokenBuffer = tokenBuffer.substring(safeLength)
                                trySend(flush).isSuccess
                            }
                        }

                        is LlamaHelper.LLMEvent.Done -> {
                            val remaining = cleanToken(tokenBuffer)
                            if (remaining.isNotEmpty()) trySend(remaining).isSuccess
                            isGenerating = false
                            close()
                        }

                        is LlamaHelper.LLMEvent.Error -> {
                            isGenerating = false
                            close(RuntimeException("LLM Engine Hatası: ${event.message}"))
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                close(e)
            }
        }

        isGenerating = true
        helper.predict(pureFormattedPrompt, partialCompletion = true)

        // Flow kapandığında (close() çağrıldığında veya iptal edildiğinde) temizlik
        awaitClose {
            collectJob.cancel()
            if (isGenerating) {
                helper.stopPrediction()
                isGenerating = false
            }
            if (nativeMutex.isLocked) {
                nativeMutex.unlock()
            }
        }
    }

    suspend fun resetConversation() {
        val path = currentModelPath ?: run {
            Log.w(TAG, "resetConversation: Model yüklü değil, atlanıyor.")
            return
        }
        nativeMutex.withLock {
            Log.i(TAG, "resetConversation: Context sıfırlanıyor...")
            llamaHelper?.release()
            llamaHelper = null
            isModelLoaded = false
            try {
                loadModel(path)
                Log.i(TAG, "resetConversation: Yeni context hazır.")
            } catch (e: Exception) {
                Log.e(TAG, "resetConversation: Yeniden yükleme başarısız.", e)
            }
        }
    }

    fun cleanUp() {
        llamaHelper?.release()
        llamaHelper = null
        isGenerating = false
        isModelLoaded = false
        currentModelPath = null
    }

    private fun cleanToken(raw: String): String {
        var result = raw
        activeStopSequences.forEach { result = result.replace(it, "") }
        return result.trim()
    }
}