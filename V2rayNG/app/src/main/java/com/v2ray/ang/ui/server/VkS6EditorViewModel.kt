package com.v2ray.ang.ui.server

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.VkS6Options
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.VkS6Fmt
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class VkS6Field(val label: Int, val numeric: Boolean = false) {
    Remarks(R.string.server_lab_remarks), Address(R.string.server_lab_address),
    Port(R.string.server_lab_port, true), PortEnd(R.string.vks6_port_end, true),
    Username(R.string.vks6_username), Password(R.string.vks6_password)
}

data class VkS6EditorState(
    val fields: Map<VkS6Field, String> = VkS6Field.entries.associateWith { "" },
    val loading: Boolean = true,
    val ready: Boolean = false,
    val saving: Boolean = false,
    val error: Int? = null,
    val savedGuid: String? = null,
    val restartService: Boolean = false
)

sealed interface VkS6EditorAction {
    data class Edit(val field: VkS6Field, val value: String) : VkS6EditorAction
    data object Save : VkS6EditorAction
}

/** Owns the S6 form and its jobs; transactional persistence stays in MmkvManager. */
class VkS6EditorViewModel(
    private val savedState: SavedStateHandle,
    private val load: (String) -> ProfileItem? = MmkvManager::decodeServerConfig,
    private val persist: (String, ProfileItem) -> String = MmkvManager::encodeServerConfig,
    private val selectedGuid: () -> String? = MmkvManager::getSelectServer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {
    private val mutableState = MutableStateFlow(VkS6EditorState(
        fields = VkS6Field.entries.associateWith { savedState.get<String>("vks6_${it.name}").orEmpty() }
    ))
    val uiState = mutableState.asStateFlow()
    private var opened = false
    private var profile: ProfileItem? = null
    private var guid = ""
    private var running = false

    fun open(guid: String, subscriptionId: String, running: Boolean) {
        if (opened) return
        opened = true
        this.guid = guid
        this.running = running
        val restore = savedState.get<String>("vks6_guid") == guid && savedState.get<Boolean>("vks6_ready") == true
        viewModelScope.launch {
            try {
                val loaded = withContext(ioDispatcher) {
                    if (guid.isEmpty()) ProfileItem.create(EConfigType.VKS6).apply { this.subscriptionId = subscriptionId }
                    else load(guid) ?: error("VK S6 profile no longer exists")
                }
                require(loaded.configType == EConfigType.VKS6) { "Unexpected profile type" }
                profile = loaded
                mutableState.value = if (restore) mutableState.value.copy(loading = false, ready = true)
                else VkS6EditorState(fields = mapOf(
                    VkS6Field.Remarks to loaded.remarks, VkS6Field.Address to loaded.server.orEmpty(),
                    VkS6Field.Port to loaded.serverPort.orEmpty(), VkS6Field.PortEnd to loaded.vkS6?.portEnd?.toString().orEmpty(),
                    VkS6Field.Username to loaded.username.orEmpty(), VkS6Field.Password to loaded.password.orEmpty()
                ), loading = false, ready = true)
                rememberForm()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "VK S6 editor load profile=$guid", e)
                mutableState.value = mutableState.value.copy(loading = false, error = R.string.toast_failure)
            }
        }
    }

    fun onAction(action: VkS6EditorAction) {
        val state = mutableState.value
        if (!state.ready || state.saving || state.savedGuid != null) return
        when (action) {
            is VkS6EditorAction.Edit -> {
                mutableState.value = state.copy(fields = state.fields + (action.field to action.value), error = null)
                rememberForm()
            }
            VkS6EditorAction.Save -> save()
        }
    }

    private fun rememberForm() {
        savedState["vks6_guid"] = guid
        savedState["vks6_ready"] = true
        mutableState.value.fields.forEach { (field, value) -> savedState["vks6_${field.name}"] = value }
    }

    private fun save() {
        val state = mutableState.value
        val initial = profile ?: return
        fun value(field: VkS6Field) = state.fields[field].orEmpty()
        val end = value(VkS6Field.PortEnd).trim()
        val config = initial.copy(
            remarks = value(VkS6Field.Remarks).trim(), server = value(VkS6Field.Address).trim(),
            serverPort = value(VkS6Field.Port).trim(),
            // Session credentials are byte strings: leading/trailing spaces are significant.
            username = value(VkS6Field.Username), password = value(VkS6Field.Password),
            vkS6 = VkS6Options(if (end.isEmpty()) null else end.toIntOrNull() ?: 0)
        )
        if (config.remarks.isBlank() || !VkS6Fmt.isValid(config)) {
            mutableState.value = state.copy(error = R.string.vks6_invalid_config)
            return
        }
        mutableState.value = state.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                val (result, restart) = withContext(ioDispatcher) {
                    config.description = AngConfigManager.generateDescription(config)
                    val result = persist(guid, config)
                    check(result.isNotBlank()) { "VK S6 profile save returned an empty ID" }
                    result to (running && result == selectedGuid())
                }
                mutableState.value = mutableState.value.copy(saving = false, savedGuid = result, restartService = restart)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "VK S6 editor save profile=$guid", e)
                mutableState.value = mutableState.value.copy(saving = false, error = R.string.toast_failure)
            }
        }
    }
}
