package ai.closepaw.llm.gguf

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Core engine for running GGUF LLMs via llama.cpp JNI.
 * Keeps the ParcelFileDescriptor alive for the entire model lifetime so
 * /proc/self/fd/<fd> remains valid while llama.cpp mmaps the model.
 */
class GgufLlmEngine(private val context: Context) {

    private var currentModelHandle: Long = 0L
    private var currentModelUri: Uri? = null
    private var currentModelPfd: ParcelFileDescriptor? = null

    companion object {
        private const val TAG = "GgufLlmEngine"
    }

    init {
        try {
            GgufNativeBridge.initBackend()
            Log.i(TAG, "GgufLlmEngine initialized successfully")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize GgufNativeBridge backend", e)
        }
    }

    fun getSystemInfo(): String = GgufNativeBridge.getLlamaVersion()

    suspend fun loadModel(
        uri: Uri,
        contextLength: Int = 2048,
        threads: Int = 4
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            unloadModel()

            val filePath: String
            if (uri.scheme == "file") {
                filePath = uri.path ?: return@withContext false
            } else {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: return@withContext false
                currentModelPfd = pfd
                filePath = "/proc/self/fd/${pfd.fd}"
            }

            Log.i(TAG, "Loading model from path: $filePath")
            val handle = GgufNativeBridge.nativeLoadModelFromFilePath(
                filePath = filePath,
                contextLength = contextLength,
                threads = threads
            )

            if (handle != 0L) {
                currentModelHandle = handle
                currentModelUri = uri
                Log.i(TAG, "Successfully loaded GGUF model handle: $handle")
                true
            } else {
                currentModelPfd?.close()
                currentModelPfd = null
                Log.e(TAG, "nativeLoadModelFromFilePath returned 0 handle")
                false
            }
        } catch (e: Exception) {
            try {
                currentModelPfd?.close()
            } catch (_: Exception) {
            }
            currentModelPfd = null
            Log.e(TAG, "Exception loading GGUF model", e)
            false
        }
    }

    suspend fun generate(prompt: String, maxTokens: Int = 256): String =
        withContext(Dispatchers.Default) {
            val handle = currentModelHandle
            if (handle == 0L) {
                return@withContext "[Error: Model not loaded in GgufLlmEngine]"
            }
            try {
                GgufNativeBridge.nativeGenerate(handle, prompt, maxTokens)
            } catch (e: Exception) {
                Log.e(TAG, "Exception during GGUF generate", e)
                "[Error during generation: ${e.message}]"
            }
        }

    fun stopGeneration() {
        val handle = currentModelHandle
        if (handle != 0L) {
            GgufNativeBridge.nativeStopGeneration(handle)
        }
    }

    fun unloadModel() {
        val handle = currentModelHandle
        if (handle != 0L) {
            GgufNativeBridge.nativeFreeModel(handle)
            currentModelHandle = 0L
            currentModelUri = null
            Log.i(TAG, "Unloaded GGUF model handle: $handle")
        }
        try {
            currentModelPfd?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close model ParcelFileDescriptor", e)
        } finally {
            currentModelPfd = null
        }
    }

    fun isModelLoaded(): Boolean = currentModelHandle != 0L
}
