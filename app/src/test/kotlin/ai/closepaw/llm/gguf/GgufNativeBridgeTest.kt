package ai.closepaw.llm.gguf

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GgufNativeBridgeTest {

    @Test
    fun isNativeLibraryLoaded_handlesAbsenceGracefullyInUnitTestEnvironment() {
        val available = GgufNativeBridge.isNativeLibraryLoaded()
        assertThat(available).isFalse()
    }
}
