package ai.closepaw.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ai.closepaw.llm.gguf.GgufLlmEngine
import ai.closepaw.llm.gguf.GgufSettingsStore

@Composable
fun GgufDiagnosticEntry(modifier: Modifier = Modifier) {
    var showDiagnostics by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val settingsStore = remember { GgufSettingsStore(context) }
    val engine = remember { GgufLlmEngine(context) }

    if (showDiagnostics) {
        GgufDiagnosticPage(
            settingsStore = settingsStore,
            engine = engine,
            onNavigateBack = { showDiagnostics = false },
            modifier = modifier
        )
        return
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "LOCAL_GGUF (llama.cpp) Diagnostics",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Configure an offline GGUF model via SAF and test local llama.cpp inference.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { showDiagnostics = true }) {
                Text("Open GGUF Diagnostic Panel")
            }
        }
    }
}
