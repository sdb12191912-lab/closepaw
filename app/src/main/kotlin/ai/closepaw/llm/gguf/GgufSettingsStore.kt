package ai.closepaw.llm.gguf

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistence store for GGUF model configuration.
 */
class GgufSettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "closepaw_gguf_settings"
        private const val KEY_MODEL_URI = "gguf_model_uri"
        private const val KEY_MODEL_FILE_NAME = "gguf_model_file_name"
        private const val KEY_CONTEXT_LENGTH = "gguf_context_length"
        private const val KEY_THREADS = "gguf_threads"
        private const val KEY_TEMPERATURE = "gguf_temperature"
    }

    fun getConfig(): GgufLocalConfig {
        return GgufLocalConfig(
            modelUri = prefs.getString(KEY_MODEL_URI, null),
            modelFileName = prefs.getString(KEY_MODEL_FILE_NAME, null),
            contextLength = prefs.getInt(KEY_CONTEXT_LENGTH, GgufLocalConfig.DEFAULT_CONTEXT_LENGTH),
            threads = prefs.getInt(KEY_THREADS, GgufLocalConfig.DEFAULT_THREADS),
            temperature = prefs.getFloat(KEY_TEMPERATURE, GgufLocalConfig.DEFAULT_TEMPERATURE)
        )
    }

    fun saveConfig(config: GgufLocalConfig) {
        prefs.edit()
            .putString(KEY_MODEL_URI, config.modelUri)
            .putString(KEY_MODEL_FILE_NAME, config.modelFileName)
            .putInt(KEY_CONTEXT_LENGTH, config.contextLength)
            .putInt(KEY_THREADS, config.threads)
            .putFloat(KEY_TEMPERATURE, config.temperature)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
