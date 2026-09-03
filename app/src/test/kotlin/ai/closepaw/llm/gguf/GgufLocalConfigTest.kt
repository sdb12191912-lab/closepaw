package ai.closepaw.llm.gguf

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GgufLocalConfigTest {

    @Test
    fun defaultConfig_isNotConfigured() {
        val config = GgufLocalConfig()
        assertThat(config.isConfigured).isFalse()
        assertThat(config.contextLength).isEqualTo(2048)
        assertThat(config.threads).isEqualTo(4)
    }

    @Test
    fun configWithModelUri_isConfigured() {
        val config = GgufLocalConfig(
            modelUri = "content://com.android.providers.downloads.documents/document/raw%3A%2Fsdcard%2FDownload%2Fmodel.gguf",
            modelFileName = "model.gguf"
        )
        assertThat(config.isConfigured).isTrue()
        assertThat(config.modelFileName).isEqualTo("model.gguf")
    }

    @Test
    fun configWithBlankUri_isNotConfigured() {
        val config = GgufLocalConfig(modelUri = "   ")
        assertThat(config.isConfigured).isFalse()
    }
}
