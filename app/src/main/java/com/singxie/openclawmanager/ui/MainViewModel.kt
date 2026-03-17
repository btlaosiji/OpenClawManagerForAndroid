package com.singxie.openclawmanager.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.singxie.openclawmanager.data.gateway.ConnectionState
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.singxie.openclawmanager.data.remote.ClawHubSkillItem
import com.singxie.openclawmanager.data.remote.fetchTopClawHubDownloads
import com.singxie.openclawmanager.data.remote.searchClawHubSkills
import com.singxie.openclawmanager.data.repository.GatewayRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private const val PREFS_GATEWAY = "gateway_connection"
private const val KEY_GATEWAY_URL = "gateway_url" // legacy
private const val KEY_AUTH_TOKEN = "auth_token"   // legacy
private const val KEY_PROFILES_JSON = "gateway_profiles_json"
private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
private const val KEY_PRIVACY_ACCEPTED = "privacy_accepted"
private const val DEFAULT_GATEWAY_URL = "ws://你的龙虾本地电脑IP:18789"

class MainViewModel(
    application: Application
) : AndroidViewModel(application) {

    data class GatewayProfile(
        val id: String,
        val name: String,
        val url: String,
        val token: String
    )

    private val gson = Gson()

    private val repositories = ConcurrentHashMap<String, GatewayRepository>()

    private fun repoFor(profileId: String): GatewayRepository {
        return repositories.getOrPut(profileId) { GatewayRepository() }
    }

    private val _activeRepo = MutableStateFlow<GatewayRepository>(GatewayRepository())

    val connectionState: StateFlow<ConnectionState> =
        _activeRepo.flatMapLatest { it.connectionState }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionState.Disconnected)

    val pairingRequestId: StateFlow<String?> =
        _activeRepo.flatMapLatest { it.pairingRequestId }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val lastConnectErrorCode: StateFlow<String?> =
        _activeRepo.flatMapLatest { it.lastConnectErrorCode }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val health: StateFlow<Map<String, Any?>> =
        _activeRepo.flatMapLatest { it.health }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val status: StateFlow<Map<String, Any?>> =
        _activeRepo.flatMapLatest { it.status }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val presence: StateFlow<List<Map<String, Any?>>> =
        _activeRepo.flatMapLatest { it.presence }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val models: StateFlow<List<Map<String, Any?>>> =
        _activeRepo.flatMapLatest { it.models }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val currentPrimaryModel: StateFlow<String?> =
        _activeRepo.flatMapLatest { it.currentPrimaryModel }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val currentModelKeyStatus: StateFlow<String?> =
        _activeRepo.flatMapLatest { it.currentModelKeyStatus }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val configSetError: StateFlow<String?> =
        _activeRepo.flatMapLatest { it.configSetError }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _saveSuccessToast = MutableStateFlow<String?>(null)
    val saveSuccessToast: StateFlow<String?> = _saveSuccessToast.asStateFlow()

    val skills: StateFlow<List<Map<String, Any?>>> =
        _activeRepo.flatMapLatest { it.skills }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val skillsEntries: StateFlow<Map<String, Map<String, Any?>>> =
        _activeRepo.flatMapLatest { it.skillsEntries }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    private val _clawHubResults = MutableStateFlow<List<ClawHubSkillItem>>(emptyList())
    val clawHubResults: StateFlow<List<ClawHubSkillItem>> = _clawHubResults.asStateFlow()
    private val _clawHubLoading = MutableStateFlow(false)
    val clawHubLoading: StateFlow<Boolean> = _clawHubLoading.asStateFlow()
    private val _clawHubError = MutableStateFlow<String?>(null)
    val clawHubError: StateFlow<String?> = _clawHubError.asStateFlow()
    val errorMessage: StateFlow<String?> =
        _activeRepo.flatMapLatest { it.errorMessage }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val chatMessages: StateFlow<List<GatewayRepository.ChatMessage>> =
        _activeRepo.flatMapLatest { it.chatMessages }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val chatSendError: StateFlow<String?> =
        _activeRepo.flatMapLatest { it.chatSendError }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val agentRunInProgress: StateFlow<Boolean> =
        _activeRepo.flatMapLatest { it.agentRunInProgress }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val prefs = getApplication<Application>().getSharedPreferences(PREFS_GATEWAY, Context.MODE_PRIVATE)

    private val _profiles = MutableStateFlow(loadProfilesFromPrefs())
    val profiles: StateFlow<List<GatewayProfile>> = _profiles.asStateFlow()

    private val _activeProfileId = MutableStateFlow(loadActiveProfileIdFromPrefs(_profiles.value))
    val activeProfileId: StateFlow<String> = _activeProfileId.asStateFlow()

    private val _gatewayUrl = MutableStateFlow(currentProfileOrNull()?.url ?: DEFAULT_GATEWAY_URL)
    val gatewayUrl: StateFlow<String> = _gatewayUrl.asStateFlow()

    private val _authToken = MutableStateFlow(currentProfileOrNull()?.token ?: "")
    val authToken: StateFlow<String> = _authToken.asStateFlow()

    private val _privacyAccepted = MutableStateFlow(prefs.getBoolean(KEY_PRIVACY_ACCEPTED, false))
    val privacyAccepted: StateFlow<Boolean> = _privacyAccepted.asStateFlow()

    init {
        // Initialize active repo based on active profile
        _activeRepo.value = repoFor(_activeProfileId.value)
    }

    private fun currentProfileOrNull(): GatewayProfile? {
        return _profiles.value.firstOrNull { it.id == _activeProfileId.value }
            ?: _profiles.value.firstOrNull()
    }

    private fun saveProfilesPrefs() {
        val list = _profiles.value
        val json = gson.toJson(list)
        prefs.edit()
            .putString(KEY_PROFILES_JSON, json)
            .putString(KEY_ACTIVE_PROFILE_ID, _activeProfileId.value)
            // keep legacy keys in sync for backward compatibility / debugging
            .putString(KEY_GATEWAY_URL, _gatewayUrl.value)
            .putString(KEY_AUTH_TOKEN, _authToken.value)
            .apply()
    }

    private fun loadProfilesFromPrefs(): List<GatewayProfile> {
        val json = prefs.getString(KEY_PROFILES_JSON, null)
        if (!json.isNullOrBlank()) {
            return runCatching {
                val type = object : TypeToken<List<GatewayProfile>>() {}.type
                gson.fromJson<List<GatewayProfile>>(json, type)
            }.getOrNull()?.takeIf { it.isNotEmpty() } ?: defaultProfilesFromLegacy()
        }
        return defaultProfilesFromLegacy()
    }

    private fun defaultProfilesFromLegacy(): List<GatewayProfile> {
        val legacyUrl = prefs.getString(KEY_GATEWAY_URL, DEFAULT_GATEWAY_URL) ?: DEFAULT_GATEWAY_URL
        val legacyToken = prefs.getString(KEY_AUTH_TOKEN, "") ?: ""
        return listOf(
            GatewayProfile(
                id = "lobster-1",
                name = "龙虾1",
                url = legacyUrl,
                token = legacyToken
            )
        )
    }

    private fun loadActiveProfileIdFromPrefs(profiles: List<GatewayProfile>): String {
        val saved = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)
        return if (saved != null && profiles.any { it.id == saved }) saved else profiles.first().id
    }

    private fun updateActiveProfile(transform: (GatewayProfile) -> GatewayProfile) {
        val activeId = _activeProfileId.value
        val updated = _profiles.value.map { p -> if (p.id == activeId) transform(p) else p }
        _profiles.value = updated
        currentProfileOrNull()?.let { p ->
            _gatewayUrl.value = p.url
            _authToken.value = p.token
        }
        saveProfilesPrefs()
    }

    fun setGatewayUrl(url: String) {
        _gatewayUrl.value = url
        updateActiveProfile { it.copy(url = url) }
    }

    fun setAuthToken(token: String) {
        _authToken.value = token
        updateActiveProfile { it.copy(token = token) }
    }

    fun setActiveProfile(profileId: String) {
        if (_profiles.value.none { it.id == profileId }) return
        _activeProfileId.value = profileId
        val p = currentProfileOrNull()!!
        _gatewayUrl.value = p.url
        _authToken.value = p.token
        _activeRepo.value = repoFor(profileId)
        saveProfilesPrefs()
    }

    fun addProfile(name: String, url: String, token: String) {
        val trimmedName = name.trim().ifBlank { "龙虾${_profiles.value.size + 1}" }
        val id = "lobster-${System.currentTimeMillis()}"
        val p = GatewayProfile(id = id, name = trimmedName, url = url.trim().ifBlank { DEFAULT_GATEWAY_URL }, token = token.trim())
        _profiles.value = _profiles.value + p
        repoFor(id)
        setActiveProfile(id)
    }

    /** 直接新增一个龙虾配置（默认名称按顺序），不弹窗输入。 */
    fun addProfileQuick() {
        val nextIndex = _profiles.value.size + 1
        val id = "lobster-${System.currentTimeMillis()}"
        val p = GatewayProfile(
            id = id,
            name = "龙虾$nextIndex",
            url = DEFAULT_GATEWAY_URL,
            token = ""
        )
        _profiles.value = _profiles.value + p
        repoFor(id)
        setActiveProfile(id)
    }

    fun removeActiveProfile(): Boolean {
        val list = _profiles.value
        if (list.size <= 1) return false
        val activeId = _activeProfileId.value
        val idx = list.indexOfFirst { it.id == activeId }
        if (idx < 0) return false
        repositories[activeId]?.disconnect()
        repositories.remove(activeId)
        val newList = list.filterNot { it.id == activeId }
        _profiles.value = newList
        // choose nearest profile
        val newIdx = (idx - 1).coerceAtLeast(0).coerceAtMost(newList.lastIndex)
        val newActive = newList[newIdx]
        setActiveProfile(newActive.id)
        saveProfilesPrefs()
        return true
    }

    fun acceptPrivacy() {
        _privacyAccepted.value = true
        prefs.edit().putBoolean(KEY_PRIVACY_ACCEPTED, true).apply()
    }

    fun connect() {
        val repo = _activeRepo.value
        repo.clearError()
        repo.connect(getApplication(), _gatewayUrl.value, _authToken.value.takeIf { it.isNotBlank() })
    }

    fun disconnect() = _activeRepo.value.disconnect()

    fun refreshAll() {
        viewModelScope.launch { _activeRepo.value.refreshAll() }
    }

    fun refreshStatus() {
        viewModelScope.launch { _activeRepo.value.refreshStatus() }
    }

    fun refreshModels() {
        viewModelScope.launch { _activeRepo.value.refreshModels() }
    }

    fun refreshConfig() {
        viewModelScope.launch { _activeRepo.value.refreshConfig() }
    }

    fun setPrimaryModelAndApply(modelRef: String) {
        viewModelScope.launch {
            _activeRepo.value.setPrimaryModelAndApply(modelRef).onSuccess {
                _saveSuccessToast.value = "模型已保存"
            }
        }
    }

    fun setCurrentModelApiKey(apiKey: String) {
        viewModelScope.launch {
            _activeRepo.value.setCurrentModelApiKey(apiKey).onSuccess {
                _saveSuccessToast.value = "Key 已保存"
            }
        }
    }

    fun clearSaveSuccessToast() { _saveSuccessToast.value = null }

    /** 保存模型并可选写入 API Key（切换模型后若未配置 Key 可在此填入）。 */
    fun applyModelAndKey(modelRef: String, apiKey: String?) {
        viewModelScope.launch { _activeRepo.value.applyModelAndKey(modelRef, apiKey) }
    }

    fun clearConfigSetError() = _activeRepo.value.clearConfigSetError()

    fun refreshSkills() {
        viewModelScope.launch { _activeRepo.value.refreshSkills() }
    }

    /** 先拉取 config（含 skills.entries）再拉取 Skill 列表，保证回退列表能显示 openclaw.json 里配置的 Skill。 */
    fun refreshConfigAndThenSkills() {
        viewModelScope.launch {
            _activeRepo.value.refreshConfig()
            _activeRepo.value.refreshSkills()
        }
    }

    fun setSkillEnabled(skillId: String, enabled: Boolean) {
        viewModelScope.launch {
            _activeRepo.value.setSkillEnabled(skillId, enabled).onSuccess {
                _saveSuccessToast.value = if (enabled) "已启用" else "已禁用"
            }
        }
    }

    /** 搜索 ClawHub 上的 Skill（Top ClawHub Skills API）。 */
    fun searchClawHub(query: String) {
        viewModelScope.launch {
            _clawHubLoading.value = true
            _clawHubError.value = null
            withContext(Dispatchers.IO) { searchClawHubSkills(query, 20) }
                .onSuccess { _clawHubResults.value = it }
                .onFailure { e ->
                    _clawHubError.value = when {
                        e.message?.contains("timeout", ignoreCase = true) == true ->
                            "请求超时，请检查网络后重试"
                        e.message?.contains("Unable to resolve host", ignoreCase = true) == true ->
                            "无法解析服务器地址，请检查网络或 DNS"
                        else -> e.message ?: "搜索失败，请检查网络后重试"
                    }
                }
            _clawHubLoading.value = false
        }
    }

    /** 获取 ClawHub 热门下载 Skill 列表。 */
    fun loadTopClawHub() {
        viewModelScope.launch {
            _clawHubLoading.value = true
            _clawHubError.value = null
            withContext(Dispatchers.IO) { fetchTopClawHubDownloads(15) }
                .onSuccess { _clawHubResults.value = it }
                .onFailure { e ->
                    _clawHubError.value = when {
                        e.message?.contains("timeout", ignoreCase = true) == true ->
                            "请求超时，请检查网络后重试"
                        e.message?.contains("Unable to resolve host", ignoreCase = true) == true ->
                            "无法解析服务器地址，请检查网络或 DNS"
                        else -> e.message ?: "加载失败，请检查网络后重试"
                    }
                }
            _clawHubLoading.value = false
        }
    }

    fun clearError() = _activeRepo.value.clearError()
    fun clearChatSendError() = _activeRepo.value.clearChatSendError()

    /** 离开对话 Tab 前调用，先清空列表再切换，避免 dispose 时 Composer 栈越界。 */
    fun cacheAndClearChatMessages() = _activeRepo.value.cacheAndClearChatMessages()
    /** 切回对话 Tab 时调用，恢复缓存的对话列表。 */
    fun restoreChatMessages() = _activeRepo.value.restoreChatMessages()

    fun sendChatMessage(body: String) {
        viewModelScope.launch { _activeRepo.value.sendChatMessage(body) }
    }

    fun refreshChatHistory() {
        viewModelScope.launch { _activeRepo.value.refreshChatHistory() }
    }

    override fun onCleared() {
        repositories.values.forEach { it.disconnect() }
        repositories.clear()
    }
}
