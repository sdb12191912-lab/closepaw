package ai.closepaw.llm.gguf

import android.util.Log

/**
 * JNI bridge to the bundled llama.cpp runtime.
 */
object GgufNativeBridge {

    private const val TAG = "GgufNativeBridge"
    private var isLoaded = false

    init {
        try {
            System.loadLibrary("closepaw_gguf_jni")
            isLoaded = true
            Log.i(TAG, "Successfully loaded native library: closepaw_gguf_jni")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library closepaw_gguf_jni", e)
            isLoaded = false
        }
    }

    fun isNativeLibraryLoaded(): Boolean = isLoaded

    external fun getLlamaVersion(): String
    external fun initBackend()
    external fun freeBackend()

    external fun nativeLoadModelFromFilePath(
        filePath: String,
        contextLength: Int,
        threads: Int
    ): Long

    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int
    ): String

    external fun nativeStopGeneration(handle: Long)
    external fun nativeFreeModel(handle: Long)
}
