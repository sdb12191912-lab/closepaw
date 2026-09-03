package ai.closepaw.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ai.closepaw.app.AuthStoreHolder
import ai.closepaw.auth.AuthCredential
import ai.closepaw.auth.AuthStore
import ai.closepaw.llm.ApiType
import ai.closepaw.llm.AuthMode
import ai.closepaw.llm.LLMProvider
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.llm.ModelCatalogRepository
import ai.closepaw.llm.ModelCatalogRepositoryHolder
import ai.closepaw.llm.ModelIdValidator
import ai.closepaw.llm.OtherBaseUrlValidator
import ai.closepaw.llm.displayLabel
import ai.closepaw.protocol.LLMBackendType
import ai.closepaw.ui.theme.Fleuron
import ai.closepaw.ui.theme.PageMastheadDrillDown
import ai.closepaw.ui.theme.closePaw
import ai.closepaw.BuildConfig
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

enum class LlmAuthTab { SIGN_IN, API_KEY, LOCAL }

/**
 * Surface the Local tab in LLM & Authentication settings.
 *
 * Enabled on the GGUF test branch so the new local GGUF diagnostics are actually
 * reachable on-device. The existing LOCAL_LFM path remains intact; the GGUF entry
 * card is shown alongside it for diagnostics only.
 */
private const val LOCAL_TAB_ENABLED = true

private val VISIBLE_TABS: List<LlmAuthTab> =
    LlmAuthTab.entries.filter { LOCAL_TAB_ENABLED || it != LlmAuthTab.LOCAL }

private fun LlmAuthTab.visibleOrFallback(): LlmAuthTab =
    if (this in VISIBLE_TABS) this else LlmAuthTab.API_KEY

private val LlmAuthTab.label: String
    get() = when (this) {
        LlmAuthTab.SIGN_IN -> "Sign In"
        LlmAuthTab.API_KEY -> "API Key"
        LlmAuthTab.LOCAL -> "Local"
    }

private val LlmAuthTab.mode: AuthMode
    get() = when (this) {
        LlmAuthTab.SIGN_IN -> AuthMode.OAuth
        LlmAuthTab.API_KEY -> AuthMode.ApiKey
        LlmAuthTab.LOCAL -> AuthMode.Local
    }

private fun AuthMode.toTab(): LlmAuthTab = when (this) {
    AuthMode.OAuth -> LlmAuthTab.SIGN_IN
    AuthMode.ApiKey -> LlmAuthTab.API_KEY
    AuthMode.Local -> LlmAuthTab.LOCAL
}

/** Default provider per tab when the current selected model's mode doesn't match the tab. */
private val LlmAuthTab.defaultProvider: LLMProvider
    get() = when (this) {
        LlmAuthTab.SIGN_IN -> LLMProvider.OPENAI_CODEX
        LlmAuthTab.API_KEY -> LLMProvider.OPENAI_API
        LlmAuthTab.LOCAL -> LLMProvider.LOCAL_LFM
    }

/** Providers available in the API Key tab sub-selector. */
private val API_KEY_PROVIDERS = listOf(LLMProvider.OPENAI_API, LLMProvider.OPENROUTER, LLMProvider.OTHER)

@Composable
internal fun LlmAuthSettingsPage(
    llmBackend: LLMBackendType,
    onBackendChange: (LLMBackendType) -> Unit,
    selectedModel: String,
    onModelChange: (String) -> Unit,
    modelCatalog: ModelCatalog,
    selectedLocalModel: String,
    onLocalModelChange: (LocalModelOption) -> Unit,
    modelLoadingStatus: ModelLoadingStatus,
    openAiAuthUiState: OpenAiAuthUiState,
    onStartOAuth: () -> Unit,
    onCancelOAuth: () -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    initialAuthTab: AuthMode? = null,
    initialProvider: LLMProvider? = null,
    otherBaseUrl: String = "",
    otherModelId: String = "",
    onOtherBaseUrlChange: (String) -> Unit = {},
    onOtherModelIdChange: (String) -> Unit = {},
) {
    val modelMode = modelCatalog.resolveOrNull(selectedModel)?.provider?.mode
    var selectedTab by rememberSaveable(initialAuthTab, modelMode, llmBackend) {
        val raw = when {
            initialAuthTab != null -> initialAuthTab.toTab()
            modelMode == AuthMode.OAuth -> LlmAuthTab.SIGN_IN
            llmBackend == LLMBackendType.LOCAL -> LlmAuthTab.LOCAL
            else -> LlmAuthTab.API_KEY
        }
        mutableStateOf(if (raw == LlmAuthTab.LOCAL && !LOCAL_TAB_ENABLED) LlmAuthTab.API_KEY else raw)
    }
    val activeTab = selectedTab.visibleOrFallback()
    LaunchedEffect(activeTab, selectedTab) {
        if (activeTab != selectedTab) selectedTab = activeTab
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authStore = remember(context) { AuthStoreHolder.get(context) }
    val pendingApiKeyPersist = remember { arrayOf<Job?>(null) }
    val apiKeyPersistMutex = remember { Mutex() }
    val pendingOtherBaseUrlPersist = remember { arrayOf<Job?>(null) }
    val pendingOtherModelIdPersist = remember { arrayOf<Job?>(null) }

    fun commitSignIn(action: () -> Unit) {
        onBackendChange(LLMBackendType.OPENAI)
        val target = resolveProviderForTab(LlmAuthTab.SIGN_IN, selectedModel, modelCatalog)
        canonicalizeMainModel(
            modelCatalog = modelCatalog,
            provider = target,
            api = null,
            selectedModel = selectedModel,
            onModelChange = onModelChange
        )
        action()
    }

    fun commitApiKey(action: () -> Unit) {
        onBackendChange(LLMBackendType.OPENAI)
        action()
    }

    fun commitLocal(action: () -> Unit) {
        onBackendChange(LLMBackendType.LOCAL)
        action()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        PageMastheadDrillDown(title = "LLM & Authentication", onBack = onBack, onClose = onClose)

        TabRow(selectedTabIndex = VISIBLE_TABS.indexOf(activeTab).coerceAtLeast(0)) {
            VISIBLE_TABS.forEach { tab ->
                Tab(
                    selected = activeTab == tab,
                    onClick = { selectedTab = tab },
                    text = { Text(tab.label) }
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = MaterialTheme.closePaw.spacing.lg, end = MaterialTheme.closePaw.spacing.lg, top = MaterialTheme.closePaw.spacing.lg)
        ) {
            when (activeTab) {
                LlmAuthTab.SIGN_IN -> SignInTabContent(
                    selectedModel = selectedModel,
                    onModelChange = { commitSignIn { onModelChange(it) } },
                    modelCatalog = modelCatalog,
                    openAiAuthUiState = openAiAuthUiState,
                    onStartOAuth = { commitSignIn { onStartOAuth() } },
                    onCancelOAuth = onCancelOAuth,
                    onSignOut = {
                        scope.launch { authStore.clear(LLMProvider.OPENAI_CODEX) }
                        onSignOut()
                    }
                )
                LlmAuthTab.API_KEY -> ApiKeyTabContent(
                    selectedModel = selectedModel,
                    onModelChange = { commitApiKey { onModelChange(it) } },
                    modelCatalog = modelCatalog,
                    authStore = authStore,
                    initialProvider = initialProvider,
                    otherBaseUrl = otherBaseUrl,
                    otherModelId = otherModelId,
                    onApiKeyPersist = { provider, key ->
                        commitApiKey { }
                        launchDebouncedApiKeyPersist(
                            scope = scope,
                            authStore = authStore,
                            mutex = apiKeyPersistMutex,
                            pending = pendingApiKeyPersist,
                            provider = provider,
                            key = key,
                        )
                    },
                    onOtherBaseUrlPersist = { url ->
                        launchDebouncedPersist(scope, pendingOtherBaseUrlPersist) {
                            onOtherBaseUrlChange(url)
                        }
                    },
                    onOtherModelIdPersist = { modelId ->
                        launchDebouncedPersist(scope, pendingOtherModelIdPersist) {
                            onOtherModelIdChange(modelId)
                        }
                    },
                )
                LlmAuthTab.LOCAL -> LocalTabContent(
                    selectedLocalModel = selectedLocalModel,
                    onLocalModelChange = { commitLocal { onLocalModelChange(it) } },
                    modelLoadingStatus = modelLoadingStatus
                )
            }
            Fleuron()
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

private fun resolveProviderForTab(
    tab: LlmAuthTab,
    selectedModel: String,
    modelCatalog: ModelCatalog,
): LLMProvider {
    val modelProvider = modelCatalog.resolveOrNull(selectedModel)?.provider
    return if (modelProvider != null && modelProvider.mode == tab.mode) modelProvider
    else tab.defaultProvider
}

private const val API_KEY_PERSIST_DEBOUNCE_MS = 300L

internal fun launchDebouncedApiKeyPersist(
    scope: CoroutineScope,
    authStore: AuthStore,
    mutex: Mutex,
    pending: Array<Job?>,
    provider: LLMProvider,
    key: String,
    debounceMs: Long = API_KEY_PERSIST_DEBOUNCE_MS,
    ioContext: CoroutineContext = Dispatchers.IO,
) {
    pending[0]?.cancel()
    pending[0] = scope.launch {
        delay(debounceMs)
        mutex.withLock {
            withContext(ioContext) {
                if (key.isBlank()) authStore.clear(provider)
                else authStore.set(provider, AuthCredential.ApiKey(key))
            }
        }
    }
}

internal fun launchDebouncedPersist(
    scope: CoroutineScope,
    pending: Array<Job?>,
    debounceMs: Long = API_KEY_PERSIST_DEBOUNCE_MS,
    action: suspend () -> Unit,
) {
    pending[0]?.cancel()
    pending[0] = scope.launch {
        delay(debounceMs)
        action()
    }
}

@Composable
private fun SignInTabContent(
    selectedModel: String,
    onModelChange: (String) -> Unit,
    modelCatalog: ModelCatalog,
    openAiAuthUiState: OpenAiAuthUiState,
    onStartOAuth: () -> Unit,
    onCancelOAuth: () -> Unit,
    onSignOut: () -> Unit
) {
    val provider = resolveProviderForTab(LlmAuthTab.SIGN_IN, selectedModel, modelCatalog)
    val modelOptions = catalogModelOptions(modelCatalog.modelsFor(provider))

    SettingsSection(title = "Cloud Model") {
        CloudModelDropdown(
            selectedModel = selectedModel,
            modelOptions = modelOptions,
            onModelChange = onModelChange
        )
    }
    Spacer(modifier = Modifier.height(20.dp))
    SettingsSection(title = "Authentication") {
        OpenAiAuthCard(
            state = openAiAuthUiState,
            onStartOAuth = onStartOAuth,
            onCancelOAuth = onCancelOAuth,
            onSignOut = onSignOut
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApiKeyTabContent(
    selectedModel: String,
    onModelChange: (String) -> Unit,
    modelCatalog: ModelCatalog,
    authStore: AuthStore,
    onApiKeyPersist: (LLMProvider, String) -> Unit,
    onOtherBaseUrlPersist: (String) -> Unit,
    onOtherModelIdPersist: (String) -> Unit,
    otherBaseUrl: String,
    otherModelId: String,
    initialProvider: LLMProvider? = null,
) {
    val derivedProvider = resolveProviderForTab(LlmAuthTab.API_KEY, selectedModel, modelCatalog)
    var selectedProvider by rememberSaveable(derivedProvider, initialProvider) {
        val start = initialProvider?.takeIf { it in API_KEY_PROVIDERS } ?: derivedProvider
        mutableStateOf(start)
    }

    val modelOptions = catalogModelOptions(modelCatalog.modelsFor(selectedProvider))
    var apiKeyText by remember(selectedProvider) { mutableStateOf("") }
    LaunchedEffect(selectedProvider) {
        val cred = authStore.get(selectedProvider)
        apiKeyText = (cred as? AuthCredential.ApiKey)?.key.orEmpty()
    }

    var otherBaseUrlText by remember(otherBaseUrl) { mutableStateOf(otherBaseUrl) }
    var otherModelIdText by remember(otherModelId) { mutableStateOf(otherModelId) }

    SettingsSection(title = "Provider") {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            API_KEY_PROVIDERS.forEachIndexed { index, provider ->
                SegmentedButton(
                    selected = selectedProvider == provider,
                    onClick = { selectedProvider = provider },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = API_KEY_PROVIDERS.size),
                    icon = {},
                ) {
                    Text(provider.displayLabel)
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(20.dp))

    if (selectedProvider == LLMProvider.OTHER) {
        SettingsSection(title = "Base URL") {
            OtherBaseUrlField(
                value = otherBaseUrlText,
                onValueChange = { value ->
                    otherBaseUrlText = value
                    onOtherBaseUrlPersist(value)
                },
            )
        }
        Spacer(modifier = Modifier.height(20.dp))

        SettingsSection(title = "Custom Model Id") {
            OtherModelIdField(
                value = otherModelIdText,
                onValueChange = { value ->
                    otherModelIdText = value
                    onOtherModelIdPersist(value)
                },
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
    }

    SettingsSection(title = "Cloud Model") {
        if (selectedProvider == LLMProvider.OPENROUTER || selectedProvider == LLMProvider.OTHER) {
            SearchableGroupedModelPicker(
                entries = modelCatalog.modelsFor(selectedProvider),
                selectedName = selectedModel,
                onSelect = onModelChange,
            )
            Spacer(modifier = Modifier.height(8.dp))
            val pickerContext = LocalContext.current
            val repo = remember(pickerContext) { ModelCatalogRepositoryHolder.get(pickerContext) }
            val discoveryState by repo.discoveryState.collectAsStateWithLifecycle()
            val refreshScope = rememberCoroutineScope()
            RefreshModelsRow(
                provider = selectedProvider,
                apiKey = apiKeyText,
                otherBaseUrl = otherBaseUrlText,
                discoveryState = discoveryState,
                allowDebugHttp = BuildConfig.DEBUG,
                onRefresh = {
                    val baseUrl = when (selectedProvider) {
                        LLMProvider.OPENROUTER -> LLMProvider.OPENROUTER.defaultBaseUrl.orEmpty()
                        LLMProvider.OTHER -> OtherBaseUrlValidator.validate(otherBaseUrlText).getOrNull().orEmpty()
                        else -> ""
                    }
                    if (baseUrl.isBlank()) return@RefreshModelsRow
                    refreshScope.launch { repo.refresh(selectedProvider, apiKeyText, baseUrl) }
                },
            )
        } else {
            CloudModelDropdown(
                selectedModel = selectedModel,
                modelOptions = modelOptions,
                onModelChange = onModelChange
            )
        }
    }
    Spacer(modifier = Modifier.height(20.dp))

    SettingsSection(title = "API Key") {
        val label = when (selectedProvider) {
            LLMProvider.OPENAI_API -> "OpenAI Key"
            LLMProvider.OPENROUTER -> "OpenRouter Key"
            LLMProvider.OTHER -> "API Key"
            LLMProvider.OPENAI_CODEX, LLMProvider.LOCAL_LFM -> null
        }
        if (label != null) {
            ApiKeyField(
                label = label,
                value = apiKeyText,
                onValueChange = { key ->
                    apiKeyText = key
                    onApiKeyPersist(selectedProvider, key)
                }
            )
        }
    }

    LaunchedEffect(selectedProvider, otherBaseUrlText, otherModelIdText, apiKeyText, modelCatalog, selectedModel) {
        if (shouldAutoFlipToOtherCustom(
                selectedProvider = selectedProvider,
                apiKeyText = apiKeyText,
                otherBaseUrlText = otherBaseUrlText,
                otherModelIdText = otherModelIdText,
                modelCatalog = modelCatalog,
                selectedModel = selectedModel,
            )
        ) {
            onModelChange(ModelCatalogRepository.OTHER_CUSTOM_NAME)
        }
    }
}

internal fun shouldAutoFlipToOtherCustom(
    selectedProvider: LLMProvider,
    apiKeyText: String,
    otherBaseUrlText: String,
    otherModelIdText: String,
    modelCatalog: ModelCatalog,
    selectedModel: String,
): Boolean {
    if (selectedProvider != LLMProvider.OTHER) return false
    if (apiKeyText.isBlank()) return false
    if (otherBaseUrlText.isBlank() || otherModelIdText.isBlank()) return false
    val normalizedUrl = OtherBaseUrlValidator.validate(otherBaseUrlText).getOrNull() ?: return false
    val trimmedModelId = ModelIdValidator.validate(otherModelIdText).getOrNull() ?: return false
    val entry = modelCatalog.resolveOrNull(ModelCatalogRepository.OTHER_CUSTOM_NAME) ?: return false
    if (entry.baseUrl != normalizedUrl || entry.modelId != trimmedModelId) return false
    if (selectedModel == ModelCatalogRepository.OTHER_CUSTOM_NAME) return false
    return true
}

@Composable
private fun LocalTabContent(
    selectedLocalModel: String,
    onLocalModelChange: (LocalModelOption) -> Unit,
    modelLoadingStatus: ModelLoadingStatus
) {
    GgufDiagnosticsEntryCard()
    Spacer(modifier = Modifier.height(20.dp))

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Text(
            text = "Experimental: local models are slow and underpowered, " +
                "and will not reliably drive the agent. Use a cloud model for real work.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(MaterialTheme.closePaw.spacing.md)
        )
    }
    Spacer(modifier = Modifier.height(16.dp))
    SettingsSection(title = "Local Model") {
        Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.closePaw.spacing.md)) {
            LocalModelDropdown(
                selectedModelId = selectedLocalModel,
                onModelChange = onLocalModelChange
            )
            ModelLoadingStatusIndicator(status = modelLoadingStatus)
        }
    }
}

private fun canonicalizeMainModel(
    modelCatalog: ModelCatalog,
    provider: LLMProvider,
    api: ApiType?,
    selectedModel: String,
    onModelChange: (String) -> Unit
) {
    val validModels = modelCatalog.modelsFor(provider, api)
    val validNames = validModels.map { it.name }.toSet()
    if (selectedModel !in validNames) {
        val preferred = validModels.firstOrNull()
        if (preferred != null) onModelChange(preferred.name)
    }
}

@Composable
private fun OtherBaseUrlField(value: String, onValueChange: (String) -> Unit) {
    val error = OtherBaseUrlValidator.validate(value).exceptionOrNull()?.message
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Base URL") },
        placeholder = { Text("https://api.example.com/v1") },
        isError = error != null,
        supportingText = if (error != null) ({ Text(error) }) else null,
        colors = OutlinedTextFieldDefaults.colors(),
        singleLine = true,
    )
}

@Composable
private fun OtherModelIdField(value: String, onValueChange: (String) -> Unit) {
    val error = ModelIdValidator.validate(value).exceptionOrNull()?.message
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Custom Model Id") },
        placeholder = { Text("model-name") },
        isError = error != null,
        supportingText = if (error != null) ({ Text(error) }) else null,
        colors = OutlinedTextFieldDefaults.colors(),
        singleLine = true,
    )
}
