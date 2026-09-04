package ai.closepaw.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ai.closepaw.llm.gguf.GgufLlmEngine
import ai.closepaw.llm.gguf.GgufLocalConfig
import ai.closepaw.llm.gguf.GgufNativeBridge
import ai.closepaw.llm.gguf.GgufSettingsStore
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun GgufDiagnosticPage(
    settingsStore: GgufSettingsStore,
    engine: GgufLlmEngine,
    onNavigateBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf(settingsStore.getConfig()) }
    var statusText by remember { mutableStateOf("Ready") }
    var isEngineLoaded by remember { mutableStateOf(engine.isModelLoaded()) }
    var isLoadingModel by remember { mutableStateOf(false) }
    var isGenerating by remember { mutableStateOf(false) }
    var testPrompt by remember { mutableStateOf("Answer with exactly: OK") }
    var testOutput by remember { mutableStateOf("") }
    var metrics by remember { mutableStateOf<GgufLlmEngine.InferenceMetrics?>(null) }

    val safPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            val fileName = uri.lastPathSegment ?: "model.gguf"
            val updatedConfig = config.copy(
                modelUri = uri.toString(),
                modelFileName = fileName
            )
            config = updatedConfig
            settingsStore.saveConfig(updatedConfig)
            statusText = "Selected model: $fileName"
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onNavigateBack) { Text("Back") }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "ClosePaw GGUF Diagnostic",
                style = MaterialTheme.typography.titleLarge
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("System / Native Status", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text("JNI Library Loaded: ${GgufNativeBridge.isNativeLibraryLoaded()}")
                Text("Llama Version: ${engine.getSystemInfo()}")
                Text("Engine Model Loaded: $isEngineLoaded")
                Text("Status: $statusText")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Model Selection (SAF)", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text("File: ${config.modelFileName ?: "No GGUF file selected"}")
                Text("URI: ${config.modelUri ?: "None"}")
                Spacer(modifier = Modifier.height(8.dp))

                Row {
                    Button(onClick = { safPickerLauncher.launch(arrayOf("*/*")) }) {
                        Text("Select GGUF File")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            config = GgufLocalConfig()
                            settingsStore.clear()
                            engine.unloadModel()
                            isEngineLoaded = false
                            metrics = null
                            statusText = "Cleared model selection"
                        }
                    ) {
                        Text("Clear")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Model Controls", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = !isLoadingModel && config.modelUri != null,
                        onClick = {
                            val uriStr = config.modelUri ?: return@Button
                            val uri = Uri.parse(uriStr)
                            isLoadingModel = true
                            statusText = "Loading model into memory..."
                            scope.launch {
                                val success = engine.loadModel(
                                    uri = uri,
                                    contextLength = config.contextLength,
                                    threads = config.threads
                                )
                                isLoadingModel = false
                                isEngineLoaded = engine.isModelLoaded()
                                statusText = if (success) "Model loaded successfully!" else "Failed to load model"
                            }
                        }
                    ) {
                        Text("Load Model")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        enabled = isEngineLoaded,
                        onClick = {
                            engine.unloadModel()
                            isEngineLoaded = engine.isModelLoaded()
                            metrics = null
                            statusText = "Model unloaded"
                        }
                    ) {
                        Text("Unload Model")
                    }
                    if (isLoadingModel) {
                        Spacer(modifier = Modifier.width(12.dp))
                        CircularProgressIndicator()
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Inference Test", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = testPrompt,
                    onValueChange = { testPrompt = it },
                    label = { Text("Prompt") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row {
                    Button(
                        enabled = isEngineLoaded && !isGenerating,
                        onClick = {
                            isGenerating = true
                            testOutput = "Generating..."
                            metrics = null
                            scope.launch {
                                val result = engine.generateWithMetrics(testPrompt, maxTokens = 16)
                                testOutput = result.text
                                metrics = result.metrics
                                isGenerating = false
                            }
                        }
                    ) {
                        Text("Generate")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    if (isGenerating) {
                        OutlinedButton(onClick = { engine.stopGeneration() }) {
                            Text("Stop")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("Output:", style = MaterialTheme.typography.labelLarge)
                Text(text = testOutput, style = MaterialTheme.typography.bodyMedium)

                metrics?.let { m ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Native Performance", style = MaterialTheme.typography.labelLarge)
                    Text("Model load: ${m.loadMs} ms")
                    Text("Prompt: ${m.promptTokens} tokens / ${m.promptMs} ms")
                    Text("Prompt speed: ${String.format(Locale.US, "%.2f", m.promptTokensPerSecond)} tok/s")
                    Text("TTFT: ${m.ttftMs} ms")
                    Text("Generated: ${m.generatedTokens} tokens")
                    Text("Generation speed: ${String.format(Locale.US, "%.2f", m.generationTokensPerSecond)} tok/s")
                    Text("Total native generation: ${m.totalMs} ms")
                    Text("GPU layers requested: ${m.gpuLayersRequested}")
                    Text("KQV offload requested: ${m.gpuOffloadRequested}")
                    Text("Flash Attention requested: ${m.flashAttentionRequested}")
                    if (m.backendNote.isNotBlank()) {
                        Text("Backend: ${m.backendNote}")
                    }
                }
            }
        }
    }
}
