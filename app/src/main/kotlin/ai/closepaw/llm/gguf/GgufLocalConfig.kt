package ai.closepaw.llm.gguf

/**
 * Configuration state for local GGUF models.
 */
data class GgufLocalConfig(
    val modelUri: String? = null,
    val modelFileName: String? = null,
    val contextLength: Int = DEFAULT_CONTEXT_LENGTH,
    val threads: Int = DEFAULT_THREADS,
    val temperature: Float = DEFAULT_TEMPERATURE
) {
    companion object {
        const val DEFAULT_CONTEXT_LENGTH = 2048
        const val DEFAULT_THREADS = 4
        const val DEFAULT_TEMPERATURE = 0.7f
    }

    val isConfigured: Boolean get() = !modelUri.isNullOrBlank()
}
