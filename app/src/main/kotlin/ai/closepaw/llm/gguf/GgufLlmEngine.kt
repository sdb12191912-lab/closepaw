package ai.closepaw.llm.gguf

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Core engine for running GGUF LLMs via llama.cpp JNI.
 *
 * For SAF/content:// URIs we stage the selected GGUF into app-private storage
 * before handing it to llama.cpp. Loading through /proc/self/fd/<fd> is not
 * reliable on all Android storage providers because llama_model_load_from_file()
 * expects a normal seekable file path that it can mmap.
 */
class GgufLlmEngine(private val context: Context) {

    private var currentModelHandle: Long = 0L
    private var currentModelUri: Uri? = null
    private var stagedModelFile: File? = null

    companion object {
        private const val TAG = "GgufLlmEngine"
        private const val STAGED_MODEL_NAME = "selected-model.gguf"
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

            val filePath = if (uri.scheme == "file") {
                uri.path ?: return@withContext false
            } else {
                val stagedFile = stageContentUriToPrivateFile(uri) ?: return@withContext false
                stagedModelFile = stagedFile
                stagedFile.absolutePath
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
                Log.e(TAG, "nativeLoadModelFromFilePath returned 0 handle")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception loading GGUF model", e)
            false
        }
    }

    private fun stageContentUriToPrivateFile(uri: Uri): File? {
        val modelDir = File(context.filesDir, "gguf")
        if (!modelDir.exists() && !modelDir.mkdirs()) {
            Log.e(TAG, "Failed to create GGUF staging directory: ${modelDir.absolutePath}")
            return null
        }

        val destination = File(modelDir, STAGED_MODEL_NAME)
        val temp = File(modelDir, "$STAGED_MODEL_NAME.tmp")

        return try {
            Log.i(TAG, "Copying SAF model into app-private storage: ${destination.absolutePath}")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output ->
                    input.copyTo(output, bufferSize = 1024 * 1024)
                    output.fd.sync()
                }
            } ?: run {
                Log.e(TAG, "ContentResolver.openInputStream returned null for $uri")
                temp.delete()
                return null
            }

            if (destination.exists() && !destination.delete()) {
                Log.w(TAG, "Could not delete previous staged model; attempting overwrite by rename")
            }
            if (!temp.renameTo(destination)) {
                Log.e(TAG, "Failed to move staged GGUF into final path")
                temp.delete()
                return null
            }

            Log.i(TAG, "Finished staging GGUF (${destination.length()} bytes)")
            destination
        } catch (e: Exception) {
            temp.delete()
            Log.e(TAG, "Failed to stage SAF GGUF into private storage", e)
            null
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
    }

    fun isModelLoaded(): Boolean = currentModelHandle != 0L
}
