package com.singxie.openclawmanager.data.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.singxie.openclawmanager.data.gateway.ConnectionState
import com.singxie.openclawmanager.data.gateway.DeviceIdentityManager
import com.singxie.openclawmanager.data.gateway.OpenClawGatewayClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Repository for Gateway data. Holds client, connection state, and cached
 * status / health / presence / skills / models from gateway methods.
 */
class GatewayRepository {

    companion object {
        private const val TAG_CONFIG = "OpenClawConfig"
        /**
         * 自定义/代理 provider 在 openclaw.json 里需要完整的 models.providers.<id>（baseUrl + api），
         * 见 https://docs.openclaw.ai/gateway/configuration-reference#custom-providers-and-base-urls
         * 内置 provider（openai、anthropic、google 等）只需 apiKey，无需此处配置。
         */
        private val PROVIDER_BASE_URL_AND_API: Map<String, Pair<String, String>> = mapOf(
            "moonshot" to ("https://api.moonshot.ai/v1" to "openai-completions"),
            "synthetic" to ("https://api.synthetic.new/anthropic" to "anthropic-messages"),
            "minimax" to ("https://api.minimax.io/anthropic" to "anthropic-messages"),
            "cerebras" to ("https://api.cerebras.ai/v1" to "openai-completions"),
            "volcengine" to ("https://open.volcengineapi.com" to "openai-completions"),
            "byteplus" to ("https://api.byteplusapi.com" to "openai-completions"),
            "vllm" to ("http://127.0.0.1:8000/v1" to "openai-completions"),
            "sglang" to ("http://127.0.0.1:30000/v1" to "openai-completions"),
            "kilocode" to ("https://api.kilo.ai/api/gateway/" to "openai-completions"),
        )
    }

    private val gson = Gson()
    private var client: OpenClawGatewayClient? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _health = MutableStateFlow<Map<String, Any?>>(emptyMap())
    val health: StateFlow<Map<String, Any?>> = _health.asStateFlow()

    private val _status = MutableStateFlow<Map<String, Any?>>(emptyMap())
    val status: StateFlow<Map<String, Any?>> = _status.asStateFlow()

    private val _presence = MutableStateFlow<List<Map<String, Any?>>>(emptyList())
    val presence: StateFlow<List<Map<String, Any?>>> = _presence.asStateFlow()

    private val _models = MutableStateFlow<List<Map<String, Any?>>>(emptyList())
    val models: StateFlow<List<Map<String, Any?>>> = _models.asStateFlow()

    /** Current default model (agents.defaults.model.primary), e.g. "openai/gpt-5.4". */
    private val _currentPrimaryModel = MutableStateFlow<String?>(null)
    val currentPrimaryModel: StateFlow<String?> = _currentPrimaryModel.asStateFlow()

    private val _configSetError = MutableStateFlow<String?>(null)
    val configSetError: StateFlow<String?> = _configSetError.asStateFlow()

    /** Key status for current model: 已配置（已脱敏）/ 已配置 / 未配置. Gateway redacts keys in config.get. */
    private val _currentModelKeyStatus = MutableStateFlow<String?>(null)
    val currentModelKeyStatus: StateFlow<String?> = _currentModelKeyStatus.asStateFlow()

    private val _skills = MutableStateFlow<List<Map<String, Any?>>>(emptyList())
    val skills: StateFlow<List<Map<String, Any?>>> = _skills.asStateFlow()

    /** skills.entries from config (skill id -> entry with enabled, apiKey, env...). Used for enable/disable state. */
    private val _skillsEntries = MutableStateFlow<Map<String, Map<String, Any?>>>(emptyMap())
    val skillsEntries: StateFlow<Map<String, Map<String, Any?>>> = _skillsEntries.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _deviceId = MutableStateFlow<String?>(null)
    val deviceId: StateFlow<String?> = _deviceId.asStateFlow()

    private val _pairingRequestId = MutableStateFlow<String?>(null)
    val pairingRequestId: StateFlow<String?> = _pairingRequestId.asStateFlow()

    private val _lastConnectErrorCode = MutableStateFlow<String?>(null)
    val lastConnectErrorCode: StateFlow<String?> = _lastConnectErrorCode.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    /** 切换 Tab 时先清空列表再 dispose，避免 Composer 栈越界；切回对话 Tab 时恢复。 */
    private var cachedChatMessages: List<ChatMessage> = emptyList()
    fun cacheAndClearChatMessages() {
        cachedChatMessages = _chatMessages.value
        _chatMessages.value = emptyList()
        android.util.Log.d("OpenClawTab", "cacheAndClearChatMessages: cached ${cachedChatMessages.size} messages")
    }
    fun restoreChatMessages() {
        if (cachedChatMessages.isNotEmpty()) {
            _chatMessages.value = cachedChatMessages
            android.util.Log.d("OpenClawTab", "restoreChatMessages: restored ${cachedChatMessages.size} messages")
            cachedChatMessages = emptyList()
        }
    }

    private val _chatSendError = MutableStateFlow<String?>(null)
    val chatSendError: StateFlow<String?> = _chatSendError.asStateFlow()

    /** True after chat.send returns "started" until first assistant content or completion event. */
    private val _agentRunInProgress = MutableStateFlow(false)
    val agentRunInProgress: StateFlow<Boolean> = _agentRunInProgress.asStateFlow()

    private var agentRunTimeoutJob: Job? = null

    private fun clearAgentRunInProgress() {
        agentRunTimeoutJob?.cancel()
        agentRunTimeoutJob = null
        _agentRunInProgress.value = false
    }

    /**
     * Session key for chat. Use the canonical key "agent:main:main" so messages
     * from the app appear in the same session as the browser Control UI (http://127.0.0.1:18789/).
     * Plain "main" can be resolved per-device by the Gateway, so browser and phone would not share history.
     */
    var chatSessionKey: String = "agent:main:main"

    /** Load device id from persistent identity (safe to call on any API level). */
    fun loadDeviceId(context: Context) {
        val identity = try {
            DeviceIdentityManager(context).getOrCreateIdentity()
        } catch (e: Exception) {
            return
        }
        _deviceId.value = identity.deviceId
    }

    fun connect(context: Context, gatewayUrl: String, authToken: String? = null) {
        client?.disconnect()
        _errorMessage.value = null
        _pairingRequestId.value = null
        _lastConnectErrorCode.value = null
        val identityManager = DeviceIdentityManager(context)
        val identity = identityManager.getOrCreateIdentity()
        _deviceId.value = identity.deviceId
        client = OpenClawGatewayClient(gatewayUrl, authToken, identity, identityManager).apply {
            setEventListener { event, payload ->
                when {
                    event == "presence" -> parseList(payload)?.let { _presence.value = it }
                    event == "tick" -> scope.launch { refreshStatus() }
                    event == "chat" || event.startsWith("chat.") ||
                            event == "agent" || event.startsWith("agent.") ||
                            event.startsWith("response.") -> payload?.let {
                        appendChatFromPayload(event, it)
                        if (event.contains("done", true) || event.contains("completed", true)) {
                            clearAgentRunInProgress()
                        }
                    }
                }
            }
            setOnConnectFailedListener { _, details ->
                _lastConnectErrorCode.value = details?.get("code")?.toString()
                val id = details?.let { d ->
                    (d["requestId"] as? String)?.takeIf { it.isNotBlank() }
                        ?: (d["pairingRequestId"] as? String)?.takeIf { it.isNotBlank() }
                        ?: (d["id"] as? String)?.takeIf { it.isNotBlank() }
                        ?: (d["requestId"]?.toString())?.takeIf { it.isNotBlank() }
                }
                if (id != null) _pairingRequestId.value = id
            }
            connect()
        }
        scope.launch {
            client?.connectionState?.collect { state ->
                _connectionState.value = state
                if (state is ConnectionState.Connected) {
                    refreshAll()
                    scope.launch {
                        runCatching { refreshChatHistory() }
                        runCatching {
                            getClient()?.request("chat.subscribe", mapOf<String, Any?>("sessionKey" to chatSessionKey))
                        }
                    }
                }
            }
        }
    }

    private fun getClient(): OpenClawGatewayClient? = client

    fun disconnect() {
        client?.disconnect()
        client = null
        _connectionState.value = ConnectionState.Disconnected
        _pairingRequestId.value = null
        _health.value = emptyMap()
        _status.value = emptyMap()
        _presence.value = emptyList()
        _models.value = emptyList()
        _currentPrimaryModel.value = null
        _currentModelKeyStatus.value = null
        _configSetError.value = null
        _skills.value = emptyList()
        _skillsEntries.value = emptyMap()
        _chatMessages.value = emptyList()
        _chatSendError.value = null
        clearAgentRunInProgress()
    }

    suspend fun refreshAll() {
        refreshHealth()
        refreshStatus()
        refreshPresence()
        refreshModels()
        refreshConfig()
        refreshSkills()
        refreshChatHistory()
    }

    suspend fun refreshHealth() = withContext(Dispatchers.IO) {
        runCatching {
            getClient()?.request("health", emptyMap())?.getOrNull()?.let { json ->
                parseMap(json)?.let { _health.value = it }
            }
        }.onFailure { _errorMessage.value = it.message }
    }

    suspend fun refreshStatus() = withContext(Dispatchers.IO) {
        runCatching {
            getClient()?.request("status", emptyMap())?.getOrNull()?.let { json ->
                parseMap(json)?.let { _status.value = it }
            }
        }.onFailure { _errorMessage.value = it.message }
    }

    suspend fun refreshPresence() = withContext(Dispatchers.IO) {
        runCatching {
            getClient()?.request("system-presence", emptyMap())?.getOrNull()?.let { json ->
                // system-presence may return object keyed by deviceId; normalize to list
                val map = parseMap(json)
                if (map != null) {
                    val list = map.entries.map { (k, v) ->
                        (v as? Map<*, *>)?.let { m -> (m + ("deviceId" to k)).mapKeys { it.key.toString() }.mapValues { it.value } }
                            ?: mapOf("deviceId" to k, "raw" to v)
                    }.filterNotNull()
                    _presence.value = list
                }
            }
        }.onFailure { _errorMessage.value = it.message }
    }

    suspend fun refreshModels() = withContext(Dispatchers.IO) {
        runCatching {
            getClient()?.request("models.status", emptyMap())?.getOrNull()?.let { json ->
                parseModelsResponse(json)?.let { _models.value = it }
            } ?: getClient()?.request("models.list", emptyMap())?.getOrNull()?.let { json ->
                parseModelsResponse(json)?.let { _models.value = it }
            }
        }.onFailure { _errorMessage.value = it.message }
    }

    /** Hash from last config.get (required for config.patch). See https://docs.openclaw.ai/gateway/configuration */
    private var lastConfigHash: String? = null

    /** Load current config and parse agents.defaults.model.primary. config.get returns { config?, hash? }. */
    suspend fun refreshConfig() = withContext(Dispatchers.IO) {
        Log.d(TAG_CONFIG, "refreshConfig start")
        _configSetError.value = null
        val client = getClient()
        if (client == null) {
            Log.w(TAG_CONFIG, "refreshConfig: client null, skip")
            return@withContext
        }
        runCatching {
            val result = client.request("config.get", emptyMap())
            Log.d(TAG_CONFIG, "config.get success=${result.isSuccess} error=${result.exceptionOrNull()?.message}")
            result.getOrNull()?.let { json ->
                Log.d(TAG_CONFIG, "config.get response length=${json.length} preview=${json.take(200)}")
                val payload = parseMap(json)
                if (payload == null) {
                    Log.w(TAG_CONFIG, "config.get parseMap(payload) null")
                    return@runCatching
                }
                Log.d(TAG_CONFIG, "payload keys=[${payload.keys.joinToString()}] hash=${payload["hash"]?.toString()?.take(20)}...")
                lastConfigHash = payload["hash"]?.toString()?.takeIf { it.isNotBlank() }
                var config: Map<*, *>? = payload["config"] as? Map<*, *>
                if (config == null && payload["config"] is String) {
                    config = parseMap(payload["config"] as String)
                    Log.d(TAG_CONFIG, "config parsed from payload.config string")
                }
                if (config == null) config = payload as? Map<*, *>
                if (config == null && payload["raw"] is String) {
                    config = parseMap(payload["raw"] as String)
                    Log.d(TAG_CONFIG, "config parsed from payload.raw")
                }
                Log.d(TAG_CONFIG, "config keys=[${config?.keys?.joinToString()?.take(120)}]")
                val parsed = config?.let { parsePrimaryModelFromConfig(it) }
                Log.d(TAG_CONFIG, "parsed primary=$parsed")
                _currentPrimaryModel.value = parsed
                _currentModelKeyStatus.value = if (config != null && parsed != null) parseKeyStatusFromConfig(config, parsed) else null
                config?.let { parseSkillsEntriesFromConfig(it) }?.let { _skillsEntries.value = it }
            } ?: Log.w(TAG_CONFIG, "config.get getOrNull empty")
        }.onFailure { e ->
            Log.e(TAG_CONFIG, "refreshConfig failure", e)
            _errorMessage.value = e.message
        }
    }

    private fun parsePrimaryModelFromConfig(config: Map<*, *>): String? {
        val agents = config["agents"] as? Map<*, *>
        if (agents == null) {
            Log.d(TAG_CONFIG, "parsePrimaryModel: no agents key")
            return null
        }
        val defaults = agents["defaults"] as? Map<*, *>
        if (defaults == null) {
            Log.d(TAG_CONFIG, "parsePrimaryModel: no agents.defaults")
            return null
        }
        val model = defaults["model"] ?: run {
            Log.d(TAG_CONFIG, "parsePrimaryModel: no agents.defaults.model")
            return null
        }
        val primary = when (model) {
            is String -> model
            is Map<*, *> -> model["primary"]?.toString()
            else -> {
                Log.d(TAG_CONFIG, "parsePrimaryModel: model type=${model?.javaClass?.simpleName}")
                null
            }
        }
        Log.d(TAG_CONFIG, "parsePrimaryModel: primary=$primary")
        return primary
    }

    /** Provider segment from model ref e.g. deepseek/deepseek-chat -> deepseek. */
    private fun providerFromModelRef(modelRef: String): String =
        modelRef.substringBefore('/').lowercase().ifEmpty { modelRef }

    /**
     * 构建 config.patch 的 raw 中 models.providers.<provider> 的 JSON 对象。
     * 若该 provider 需完整配置（见文档），则包含 baseUrl、api；否则仅 apiKey。
     */
    private fun buildProviderPatchBody(provider: String, apiKey: String?): String {
        val pair = PROVIDER_BASE_URL_AND_API[provider]
        return if (pair != null) {
            val (baseUrl, api) = pair
            val map = mutableMapOf<String, String>()
            map["baseUrl"] = baseUrl
            map["api"] = api
            if (!apiKey.isNullOrBlank()) map["apiKey"] = apiKey
            gson.toJson(map)
        } else {
            if (!apiKey.isNullOrBlank()) """{"apiKey":${gson.toJson(apiKey)}}"""
            else "{}"
        }
    }

    /** 用 Map 序列化为 JSON 作为 config.patch 的 raw，避免手拼字符串导致 JSON5 解析错误。 */
    private fun buildPatchRawForModelAndProvider(modelRef: String, provider: String, providerBody: String): String {
        val patch = mutableMapOf<String, Any?>(
            "agents" to mapOf(
                "defaults" to mapOf(
                    "model" to mapOf("primary" to modelRef)
                )
            )
        )
        if (providerBody != "{}") {
            @Suppress("UNCHECKED_CAST")
            val providerMap = gson.fromJson(providerBody, object : TypeToken<Map<String, Any?>>() {}.type) as Map<String, Any?>
            patch["models"] = mapOf("providers" to mapOf(provider to providerMap))
        }
        return gson.toJson(patch)
    }

    /** 用 Map 序列化为 JSON 作为 config.patch 的 raw（仅 models.providers.<provider>）。 */
    private fun buildPatchRawForProviderKey(provider: String, body: String): String {
        @Suppress("UNCHECKED_CAST")
        val providerMap = gson.fromJson(body, object : TypeToken<Map<String, Any?>>() {}.type) as Map<String, Any?>
        val patch = mapOf("models" to mapOf("providers" to mapOf(provider to providerMap)))
        return gson.toJson(patch)
    }

    /**
     * 内置 provider（openai、google、anthropic 等）通过 env.vars 写 Key，否则 Gateway 可能报 invalid config；
     * 自定义/代理 provider 通过 models.providers.<id>.apiKey。见 docs.openclaw.ai/concepts/model-providers。
     */
    private fun buildPatchRawForApiKey(provider: String, apiKey: String): String {
        return if (provider in PROVIDER_BASE_URL_AND_API) {
            val body = buildProviderPatchBody(provider, apiKey)
            buildPatchRawForProviderKey(provider, body)
        } else {
            val varName = envVarForProvider(provider)
            val patch = mapOf("env" to mapOf("vars" to mapOf(varName to apiKey)))
            gson.toJson(patch)
        }
    }

    /** Common env var name for provider (OpenClaw docs). */
    private fun envVarForProvider(provider: String): String = when (provider) {
        "openai" -> "OPENAI_API_KEY"
        "google" -> "GEMINI_API_KEY"
        "anthropic" -> "ANTHROPIC_API_KEY"
        "deepseek" -> "DEEPSEEK_API_KEY"
        "openrouter" -> "OPENROUTER_API_KEY"
        "opencode" -> "OPENCODE_API_KEY"
        "opencode-go" -> "OPENCODE_API_KEY"
        "groq" -> "GROQ_API_KEY"
        "mistral" -> "MISTRAL_API_KEY"
        "xai" -> "XAI_API_KEY"
        else -> "${provider.replace("-", "_").uppercase()}_API_KEY"
    }

    private val REDACTED = "__OPENCLAW_REDACTED__"

    /** Infer key status from config (env / env.vars / models.providers). Keys are redacted in config.get. */
    private fun parseKeyStatusFromConfig(config: Map<*, *>, modelRef: String): String {
        val provider = providerFromModelRef(modelRef)
        val varName = envVarForProvider(provider)
        fun valueOf(key: String): Any? {
            val env = config["env"] as? Map<*, *> ?: return null
            val v = env[key] ?: (env["vars"] as? Map<*, *>)?.get(key)
            return v
        }
        val inEnv = valueOf(varName)
        val inProviders = (config["models"] as? Map<*, *>)?.let { models ->
            (models["providers"] as? Map<*, *>)?.get(provider)?.let { p ->
                (p as? Map<*, *>)?.get("apiKey")
            }
        }
        val value = inEnv ?: inProviders
        return when {
            value == null -> "未配置"
            value.toString().isBlank() -> "未配置"
            value.toString().contains(REDACTED, ignoreCase = true) -> "已配置（已脱敏）"
            else -> "已配置"
        }
    }

    /** Parse skills.entries from config for enable/disable state. */
    private fun parseSkillsEntriesFromConfig(config: Map<*, *>): Map<String, Map<String, Any?>> {
        val skillsRoot = config["skills"] as? Map<*, *> ?: return emptyMap()
        val entries = skillsRoot["entries"] as? Map<*, *> ?: return emptyMap()
        return entries.mapKeys { it.key.toString() }.mapValues { entry ->
            (entry.value as? Map<*, *>)?.mapKeys { it.key.toString() }?.mapValues { it.value } ?: emptyMap()
        }
    }

    /**
     * Set default model via config.patch (partial update, hot-applies; no full restart).
     * See https://docs.openclaw.ai/gateway/configuration — config.patch needs baseHash from config.get.
     * Fallback: if patch fails, try config.set(path, value) for Gateways that support it.
     */
    suspend fun setPrimaryModelAndApply(modelRef: String): Result<Unit> = withContext(Dispatchers.IO) {
        _configSetError.value = null
        val client = getClient() ?: return@withContext Result.failure(IllegalStateException("未连接"))
        val hash = lastConfigHash
        if (hash != null) {
            val provider = providerFromModelRef(modelRef)
            val providerBody = buildProviderPatchBody(provider, null)
            val raw = buildPatchRawForModelAndProvider(modelRef, provider, providerBody)
            val patchResult = client.request("config.patch", mapOf(
                "baseHash" to hash,
                "raw" to raw
            ))
            if (patchResult.isSuccess) {
                _currentPrimaryModel.value = modelRef
                scope.launch { runCatching { refreshConfig() } }
                return@withContext Result.success(Unit)
            }
            _configSetError.value = patchResult.exceptionOrNull()?.message ?: "config.patch 失败"
        } else {
            _configSetError.value = "请先点「刷新」获取配置后再保存"
        }
        val setResult = client.request("config.set", mapOf(
            "path" to "agents.defaults.model.primary",
            "value" to modelRef
        ))
        if (setResult.isSuccess) {
            _currentPrimaryModel.value = modelRef
            return@withContext Result.success(Unit)
        }
        if (_configSetError.value == null) _configSetError.value = setResult.exceptionOrNull()?.message ?: "config.set 失败"
        Result.failure(Exception(_configSetError.value))
    }

    /**
     * Apply model and optionally API key in one flow: patch model, refresh config (for new hash), then patch key if provided.
     */
    suspend fun applyModelAndKey(modelRef: String, apiKey: String?): Result<Unit> = withContext(Dispatchers.IO) {
        _configSetError.value = null
        val client = getClient() ?: return@withContext Result.failure(IllegalStateException("未连接"))
        var hash = lastConfigHash
        if (hash != null) {
            val provider = providerFromModelRef(modelRef)
            val providerBody = buildProviderPatchBody(provider, null)
            val raw = buildPatchRawForModelAndProvider(modelRef, provider, providerBody)
            val patchResult = client.request("config.patch", mapOf("baseHash" to hash, "raw" to raw))
            if (!patchResult.isSuccess) {
                _configSetError.value = patchResult.exceptionOrNull()?.message ?: "config.patch 失败"
                return@withContext Result.failure(Exception(_configSetError.value))
            }
            _currentPrimaryModel.value = modelRef
            runCatching { refreshConfig() }
            hash = lastConfigHash
        }
        val keyToApply = apiKey?.trim()?.takeIf { it.isNotBlank() }
        if (keyToApply != null && hash != null) {
            val modelRefForKey = _currentPrimaryModel.value ?: modelRef
            val provider = providerFromModelRef(modelRefForKey)
            val keyRaw = buildPatchRawForApiKey(provider, keyToApply)
            val keyResult = client.request("config.patch", mapOf("baseHash" to hash, "raw" to keyRaw))
            if (keyResult.isSuccess) {
                _currentModelKeyStatus.value = "已配置"
                scope.launch { runCatching { refreshConfig() } }
            } else {
                _configSetError.value = keyResult.exceptionOrNull()?.message ?: "Key 写入失败"
                return@withContext Result.failure(Exception(_configSetError.value))
            }
        }
        Result.success(Unit)
    }

    fun clearConfigSetError() { _configSetError.value = null }

    /**
     * Set API key for current model's provider via config.patch (models.providers.<provider>.apiKey).
     * Gateway uses this for model auth; env is separate (e.g. dev). Key is sent over the wire; use only on trusted network.
     */
    suspend fun setCurrentModelApiKey(apiKey: String): Result<Unit> = withContext(Dispatchers.IO) {
        _configSetError.value = null
        val client = getClient() ?: return@withContext Result.failure(IllegalStateException("未连接"))
        val modelRef = _currentPrimaryModel.value ?: return@withContext Result.failure(IllegalStateException("请先刷新获取当前模型"))
        val hash = lastConfigHash ?: run {
            _configSetError.value = "请先点「刷新」后再设置 Key"
            return@withContext Result.failure(IllegalStateException("缺少 baseHash"))
        }
        val provider = providerFromModelRef(modelRef)
        val raw = buildPatchRawForApiKey(provider, apiKey.trim())
        val patchResult = client.request("config.patch", mapOf("baseHash" to hash, "raw" to raw))
        if (patchResult.isSuccess) {
            _currentModelKeyStatus.value = "已配置"
            scope.launch { refreshConfig() }
            Result.success(Unit)
        } else {
            _configSetError.value = patchResult.exceptionOrNull()?.message ?: "config.patch 失败"
            Result.failure(Exception(_configSetError.value))
        }
    }

    private fun parseModelsResponse(json: String): List<Map<String, Any?>>? {
        val any = gson.fromJson(json, Any::class.java)
        return when (any) {
            is List<*> -> any.mapNotNull { (it as? Map<*, *>)?.mapKeys { k -> k.key.toString() }?.mapValues { it.value } }
            is Map<*, *> -> {
                val list = (any["models"] ?: any["candidates"] ?: any["list"]) as? List<*>
                list?.mapNotNull { (it as? Map<*, *>)?.mapKeys { k -> k.key.toString() }?.mapValues { it.value } }
                    ?: listOf(any.mapKeys { it.key.toString() }.mapValues { it.value })
            }
            else -> null
        }
    }

    /**
     * 拉取 Skill 列表：先试 skills.list / skills.bins（Gateway 可能未实现或仅 node 可调），
     * 若为空则用 config 中的 skills.entries 作为回退（至少显示 openclaw.json 里配置过的）。
     */
    suspend fun refreshSkills() = withContext(Dispatchers.IO) {
        runCatching {
            val client = getClient()
            if (client == null) {
                fillSkillsFromEntriesIfEmpty()
                return@withContext
            }
            val listFromRpc = client.request("skills.list", emptyMap()).getOrNull()?.let { json ->
                parseSkillsResponse(json)
            } ?: client.request("skills.bins", emptyMap()).getOrNull()?.let { json ->
                parseSkillsResponse(json)
            } ?: client.request("tools.catalog", emptyMap()).getOrNull()?.let { json ->
                parseSkillsResponseFromToolsCatalog(json)
            }
            if (!listFromRpc.isNullOrEmpty()) {
                _skills.value = listFromRpc
            } else {
                fillSkillsFromEntriesIfEmpty()
            }
        }.onFailure { _errorMessage.value = it.message }
    }

    /** 当 RPC 无数据时，用 skills.entries 拼出列表，至少显示 openclaw.json 里配置过的 Skill。 */
    private fun fillSkillsFromEntriesIfEmpty() {
        if (_skills.value.isNotEmpty()) return
        val entries = _skillsEntries.value
        if (entries.isEmpty()) return
        _skills.value = entries.map { (id, config) ->
            val map = mutableMapOf<String, Any?>(
                "id" to id,
                "name" to id,
                "description" to "来自 openclaw.json skills.entries"
            )
            (config as? Map<*, *>)?.forEach { (k, v) -> map[k.toString()] = v }
            map
        }
    }

    /** tools.catalog 可能返回含 skill 信息的结构，尝试解析出列表。 */
    private fun parseSkillsResponseFromToolsCatalog(json: String): List<Map<String, Any?>>? {
        val any = gson.fromJson(json, Any::class.java) ?: return null
        if (any is List<*>) return any.mapNotNull { (it as? Map<*, *>)?.mapKeys { k -> k.key.toString() }?.mapValues { it.value } }
        if (any is Map<*, *>) {
            val list = (any["tools"] ?: any["skills"] ?: any["groups"] ?: any["list"] ?: any["data"]) as? List<*>
            list?.mapNotNull { (it as? Map<*, *>)?.mapKeys { k -> k.key.toString() }?.mapValues { it.value } }?.let { return it }
            val entries = (any["entries"] as? Map<*, *>) ?: any
            if (entries is Map<*, *> && entries.keys.all { it is String }) {
                return entries.map { (k, v) ->
                    val m = (v as? Map<*, *>)?.mapKeys { it.key.toString() }?.mapValues { it.value }?.toMutableMap() ?: mutableMapOf<String, Any?>()
                    m["id"] = k.toString()
                    m["name"] = m["name"] ?: k.toString()
                    m
                }
            }
        }
        return null
    }

    /**
     * 启用或禁用某个 Skill（config.patch skills.entries.<id>.enabled）。
     * 见 https://docs.openclaw.ai/tools/skills-config
     */
    suspend fun setSkillEnabled(skillId: String, enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        _configSetError.value = null
        val client = getClient() ?: return@withContext Result.failure(IllegalStateException("未连接"))
        val hash = lastConfigHash ?: run {
            _configSetError.value = "请先在模型配置页刷新获取配置后再操作"
            return@withContext Result.failure(IllegalStateException("缺少 baseHash"))
        }
        val patch = mapOf("skills" to mapOf("entries" to mapOf(skillId to mapOf("enabled" to enabled))))
        val raw = gson.toJson(patch)
        val result = client.request("config.patch", mapOf("baseHash" to hash, "raw" to raw))
        if (result.isSuccess) {
            _skillsEntries.value = _skillsEntries.value.toMutableMap().apply {
                put(skillId, (get(skillId) ?: emptyMap<String, Any?>()).toMutableMap().apply { put("enabled", enabled) })
            }
            scope.launch { runCatching { refreshConfig() } }
            Result.success(Unit)
        } else {
            _configSetError.value = result.exceptionOrNull()?.message ?: "保存失败"
            Result.failure(Exception(_configSetError.value))
        }
    }

    private fun parseSkillsResponse(json: String): List<Map<String, Any?>>? {
        val any = gson.fromJson(json, Any::class.java) ?: return null
        when (any) {
            is List<*> -> return any.mapNotNull { (it as? Map<*, *>)?.mapKeys { k -> k.key.toString() }?.mapValues { it.value } }
            is Map<*, *> -> {
                val list = (any["skills"] ?: any["bins"] ?: any["list"] ?: any["data"] ?: any["items"] ?: any["result"]) as? List<*>
                list?.mapNotNull { (it as? Map<*, *>)?.mapKeys { k -> k.key.toString() }?.mapValues { it.value } }?.let { return it }
                // 若为 id -> config 的 entries 结构，转成列表
                if (any.keys.all { it is String }) {
                    return any.map { (k, v) ->
                        val m = (v as? Map<*, *>)?.mapKeys { it.key.toString() }?.mapValues { it.value }?.toMutableMap() ?: mutableMapOf<String, Any?>()
                        m["id"] = k.toString()
                        m["name"] = m["name"] ?: k.toString()
                        m
                    }
                }
                return listOf(any.mapKeys { it.key.toString() }.mapValues { it.value })
            }
            else -> return null
        }
    }

    fun clearError() { _errorMessage.value = null }
    fun clearChatSendError() { _chatSendError.value = null }

    data class ChatMessage(
        val role: String,
        val content: String,
        val id: String? = null
    )

    suspend fun refreshChatHistory() = withContext(Dispatchers.IO) {
        runCatching {
            val params = mapOf<String, Any?>("sessionKey" to chatSessionKey)
            getClient()?.request("chat.history", params)?.getOrNull()?.let { json ->
                parseChatHistory(json)?.let { _chatMessages.value = it }
            }
        }
    }

    private fun parseChatHistory(json: String): List<ChatMessage>? {
        val any = gson.fromJson(json, Any::class.java) ?: return null
        val list = when (any) {
            is List<*> -> any
            is Map<*, *> -> (any["messages"] ?: any["history"] ?: any["items"]) as? List<*>
            else -> null
        } ?: return null
        return list.mapNotNull { entry ->
            val m = entry as? Map<*, *> ?: return@mapNotNull null
            val role = (m["role"] ?: m["author"])?.toString() ?: "unknown"
            val content = extractDisplayText(m["content"] ?: m["text"] ?: m["body"])
            ChatMessage(role = role, content = content, id = m["id"]?.toString())
        }
    }

    private fun appendChatFromPayload(event: String, payload: Any) {
        when (payload) {
            is List<*> -> {
                val list = payload.mapNotNull { toChatMessage(it) }
                if (list.isNotEmpty()) _chatMessages.value = _chatMessages.value + list
            }
            is Map<*, *> -> {
                val m = payload
                // Only process if sessionKey matches (when present)
                m["sessionKey"]?.toString()?.takeIf { it.isNotBlank() }?.let { key ->
                    if (key != chatSessionKey) return
                }
                // Full message (role + content) — merge into last if both are assistant (streaming)
                toChatMessage(m)?.let { msg ->
                    appendOrMergeAssistant(msg)
                    if (msg.role.equals("assistant", ignoreCase = true)) clearAgentRunInProgress()
                    return
                }
                // Nested message — same merge for assistant
                (m["message"] as? Map<*, *>)?.let { toChatMessage(it)?.let { msg ->
                    appendOrMergeAssistant(msg)
                    if (msg.role.equals("assistant", ignoreCase = true)) clearAgentRunInProgress()
                    return
                } }
                // Delta / stream chunk: always merge into last assistant or add new (avoid duplicate/cumulative)
                extractDeltaContent(m)?.let { content ->
                    clearAgentRunInProgress()
                    appendOrMergeAssistant(ChatMessage(role = "assistant", content = content, id = m["id"]?.toString()))
                }
            }
            else -> toChatMessage(payload)?.let { _chatMessages.value = _chatMessages.value + it }
        }
    }

    /** Append one message, or merge into last if both are assistant (so streamed reply stays one bubble). */
    private fun appendOrMergeAssistant(msg: ChatMessage) {
        val list = _chatMessages.value
        if (msg.role.equals("assistant", ignoreCase = true) && list.isNotEmpty() && list.last().role.equals("assistant", ignoreCase = true)) {
            val last = list.last()
            val newContent = when {
                msg.content == last.content -> last.content
                msg.content.length > last.content.length && msg.content.startsWith(last.content) -> msg.content
                else -> last.content + msg.content
            }
            _chatMessages.value = list.dropLast(1) + last.copy(content = newContent)
        } else {
            _chatMessages.value = list + msg
        }
    }

    private fun toChatMessage(entry: Any?): ChatMessage? {
        val m = entry as? Map<*, *> ?: return null
        val role = (m["role"] ?: m["author"])?.toString() ?: return null
        val content = extractDisplayText(m["content"] ?: m["text"] ?: m["body"])
        return ChatMessage(role = role, content = content, id = m["id"]?.toString())
    }

    /** Extract plain text for display. Handles List (e.g. [{type=text,text='...'}]), Map, String. */
    private fun extractDisplayText(any: Any?): String {
        if (any == null) return ""
        if (any is String) {
            if (any.trimStart().startsWith("[")) {
                runCatching {
                    val list = gson.fromJson<List<Any?>>(any, object : TypeToken<List<Any?>>() {}.type)
                    return list.joinToString("") { extractDisplayText(it) }
                }
            }
            return any
        }
        if (any is List<*>) return any.joinToString("") { extractDisplayText(it) }
        if (any is Map<*, *>) return (any["text"] ?: any["content"] ?: any["body"])?.toString() ?: ""
        return any.toString()
    }

    /** Extract content from a delta/stream payload (various Gateway shapes). Supports List e.g. [{type=text,text='...'}]. */
    private fun extractDeltaContent(m: Map<*, *>): String? {
        val raw = m["delta"] ?: m["text"] ?: m["content"] ?: m["body"] ?: m["outputText"] ?: m["output"]
        val content = when (raw) {
            is String -> raw
            is List<*> -> raw.joinToString("") { extractDisplayText(it) }
            is Map<*, *> -> (raw["text"] ?: raw["content"] ?: raw["body"])?.toString()
            else -> raw?.let { extractDisplayText(it) }
        } ?: run {
            val nested = m["message"] as? Map<*, *>
            (nested?.get("content") ?: nested?.get("text") ?: nested?.get("body"))?.let { extractDisplayText(it) }
        } ?: return null
        return content.takeIf { it.isNotBlank() }
    }

    /**
     * Sends a user message via Gateway WS. Uses [chat.send] per OpenClaw docs:
     * https://docs.openclaw.ai/web/control-ui — "Chat with the model via Gateway WS
     * (chat.history, chat.send, ...)". The Gateway acks with runId/started and streams
     * the assistant reply via chat events (we already handle those in chat.subscribe).
     */
    suspend fun sendChatMessage(body: String): Result<Unit> = withContext(Dispatchers.IO) {
        _chatSendError.value = null
        val client = getClient() ?: run {
            _chatSendError.value = "未连接"
            return@withContext Result.failure(IllegalStateException("Not connected"))
        }
        val idemKey = java.util.UUID.randomUUID().toString()
        _chatMessages.value = _chatMessages.value + ChatMessage(role = "user", content = body)
        val sendParams = mapOf<String, Any?>(
            "sessionKey" to chatSessionKey,
            "message" to body,
            "idempotencyKey" to idemKey
        )
        val sendResult = client.request("chat.send", sendParams)
        if (!sendResult.isSuccess) {
            _chatSendError.value = sendResult.exceptionOrNull()?.message ?: "chat.send 失败"
            return@withContext sendResult.map { }
        }
        sendResult.getOrNull()?.let { json ->
            parseMap(json)?.let { m ->
                val status = m["status"]?.toString()
                if (status == "started") {
                    _agentRunInProgress.value = true
                    agentRunTimeoutJob?.cancel()
                    agentRunTimeoutJob = scope.launch {
                        delay(90_000)
                        _agentRunInProgress.value = false
                        agentRunTimeoutJob = null
                    }
                }
            }
        }
        Result.success(Unit)
    }

    private fun parseMap(json: String): Map<String, Any?>? {
        return try {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(json, object : TypeToken<Map<String, Any?>>() {}.type) as Map<String, Any?>
        } catch (_: Exception) { null }
    }

    private fun parseList(payload: Any?): List<Map<String, Any?>>? {
        if (payload == null) return null
        val json = if (payload is String) payload else gson.toJson(payload)
        return try {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(json, object : TypeToken<List<Map<String, Any?>>>() {}.type) as List<Map<String, Any?>>
        } catch (_: Exception) {
            (payload as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.mapKeys { k -> k.key.toString() }?.mapValues { it.value } }
        }
    }
}
