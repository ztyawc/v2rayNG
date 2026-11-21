package com.v2ray.ang.ui.server

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.UuSniOptions
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.UuSniFmt
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

enum class UuSniField(val label: Int, val numeric: Boolean = false) {
    Remarks(R.string.server_lab_remarks), Address(R.string.server_lab_address),
    Port(R.string.server_lab_port, true), ObfsKey(R.string.uusni_obfs_key, true)
}

data class UuSniEditorState(
    val fields: Map<UuSniField, String> = UuSniField.entries.associateWith { "" },
    val sniCrypt: Boolean = true,
    val loading: Boolean = true,
    val ready: Boolean = false,
    val saving: Boolean = false,
    val error: Int? = null,
    val savedGuid: String? = null,
    val restartService: Boolean = false
)

sealed interface UuSniEditorAction {
    data class Edit(val field: UuSniField, val value: String) : UuSniEditorAction
    data class Crypt(val value: Boolean) : UuSniEditorAction
    data object Save : UuSniEditorAction
}

/** Owns this editor's form and asynchronous work; MMKV remains the profile store. */
class UuSniEditorViewModel(
    private val savedState: SavedStateHandle,
    private val load: (String) -> ProfileItem? = MmkvManager::decodeServerConfig,
    private val persist: (String, ProfileItem) -> String = MmkvManager::encodeServerConfig,
    private val selectedGuid: () -> String? = MmkvManager::getSelectServer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {
    private val mutableState = MutableStateFlow(UuSniEditorState(
        fields = UuSniField.entries.associateWith { savedState.get<String>("uusni_${it.name}").orEmpty() },
        sniCrypt = savedState["uusni_crypt"] ?: true
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
        val restore = savedState.get<String>("uusni_guid") == guid && savedState.get<Boolean>("uusni_ready") == true
        viewModelScope.launch {
            try {
                val loaded = withContext(ioDispatcher) {
                    if (guid.isEmpty()) ProfileItem.create(EConfigType.UUSNI).apply { this.subscriptionId = subscriptionId }
                    else load(guid) ?: error("UU SNI profile no longer exists")
                }
                require(loaded.configType == EConfigType.UUSNI) { "Unexpected profile type" }
                profile = loaded
                mutableState.value = if (restore) mutableState.value.copy(loading = false, ready = true)
                else UuSniEditorState(fields = mapOf(
                    UuSniField.Remarks to loaded.remarks, UuSniField.Address to loaded.server.orEmpty(),
                    UuSniField.Port to loaded.serverPort.orEmpty(), UuSniField.ObfsKey to loaded.uuSni?.obfsKey?.toString().orEmpty()
                ), sniCrypt = loaded.uuSni?.sniCrypt ?: true, loading = false, ready = true)
                rememberForm()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "UU SNI editor load profile=$guid", e)
                mutableState.value = mutableState.value.copy(loading = false, error = R.string.toast_failure)
            }
        }
    }

    fun onAction(action: UuSniEditorAction) {
        val state = mutableState.value
        if (!state.ready || state.saving || state.savedGuid != null) return
        when (action) {
            is UuSniEditorAction.Edit -> {
                mutableState.value = state.copy(fields = state.fields + (action.field to action.value), error = null)
                rememberForm()
            }
            is UuSniEditorAction.Crypt -> {
                mutableState.value = state.copy(sniCrypt = action.value, error = null)
                rememberForm()
            }
            UuSniEditorAction.Save -> save()
        }
    }

    private fun rememberForm() {
        savedState["uusni_guid"] = guid
        savedState["uusni_ready"] = true
        savedState["uusni_crypt"] = mutableState.value.sniCrypt
        mutableState.value.fields.forEach { (field, value) -> savedState["uusni_${field.name}"] = value }
    }

    private fun save() {
        val state = mutableState.value
        val initial = profile ?: return
        fun value(field: UuSniField) = state.fields[field].orEmpty().trim()
        val config = initial.copy(remarks = value(UuSniField.Remarks), server = value(UuSniField.Address),
            serverPort = value(UuSniField.Port), uuSni = UuSniOptions(value(UuSniField.ObfsKey).toIntOrNull(), state.sniCrypt))
        if (config.remarks.isBlank() || !UuSniFmt.isValid(config)) {
            mutableState.value = state.copy(error = R.string.uusni_invalid_config)
            return
        }
        mutableState.value = state.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                val (result, restart) = withContext(ioDispatcher) {
                    config.description = AngConfigManager.generateDescription(config)
                    val result = persist(guid, config)
                    check(result.isNotBlank()) { "UU SNI profile save returned an empty ID" }
                    result to (running && result == selectedGuid())
                }
                mutableState.value = mutableState.value.copy(saving = false, savedGuid = result, restartService = restart)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "UU SNI editor save profile=$guid", e)
                mutableState.value = mutableState.value.copy(saving = false, error = R.string.toast_failure)
            }
        }
    }
}
