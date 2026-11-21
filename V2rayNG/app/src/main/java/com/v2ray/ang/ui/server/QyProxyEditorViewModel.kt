package com.v2ray.ang.ui.server

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.QyProxyOptions
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.QyProxyFmt
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

enum class QyProxyField(val label: Int, val initial: String = "", val numeric: Boolean = false) {
    Remarks(R.string.server_lab_remarks), Address(R.string.server_lab_address),
    Port(R.string.server_lab_port, numeric = true), Username(R.string.server_lab_cmcc_username),
    Password(R.string.server_lab_cmcc_password), SN(R.string.qyproxy_sn),
    GameId(R.string.qyproxy_game_id, numeric = true), ServerId(R.string.qyproxy_server_id, numeric = true),
    GameArea(R.string.qyproxy_game_area, "default"), Zone(R.string.qyproxy_zone),
    ClientType(R.string.qyproxy_client_type, "20", true), Product(R.string.qyproxy_product, "hy-android"),
    Version(R.string.qyproxy_version, "1.0.0")
}

data class QyProxyEditorState(
    val fields: Map<QyProxyField, String>,
    val role: String = QyProxyFmt.CN2,
    val loading: Boolean = true,
    val ready: Boolean = false,
    val saving: Boolean = false,
    val error: Int? = null,
    val savedGuid: String? = null,
    val restartService: Boolean = false
)

sealed interface QyProxyEditorAction {
    data class Edit(val field: QyProxyField, val value: String) : QyProxyEditorAction
    data class Role(val value: String) : QyProxyEditorAction
    data object Save : QyProxyEditorAction
}

/** Owns the negotiation form's lifetime, validation and asynchronous profile writes.
 * MMKV retains persistence ownership; the stock Compose-local ServerUiState is
 * intentionally not used for this form's durable negotiation state.
 */
class QyProxyEditorViewModel(
    private val savedState: SavedStateHandle,
    private val load: (String) -> ProfileItem? = MmkvManager::decodeServerConfig,
    private val persist: (String, ProfileItem) -> String = MmkvManager::encodeServerConfig,
    private val selectedGuid: () -> String? = MmkvManager::getSelectServer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {
    private val mutableState = MutableStateFlow(QyProxyEditorState(
        fields = QyProxyField.entries.associateWith { savedState.get<String>("qy_${it.name}") ?: it.initial },
        role = savedState["qy_role"] ?: QyProxyFmt.CN2
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
        val restore = savedState.get<String>("qy_guid") == guid && savedState.get<Boolean>("qy_ready") == true
        viewModelScope.launch {
            try {
                val loaded = withContext(ioDispatcher) {
                    if (guid.isEmpty()) ProfileItem.create(EConfigType.QYPROXY).apply { this.subscriptionId = subscriptionId }
                    else load(guid) ?: error("QyProxy profile no longer exists")
                }
                require(loaded.configType == EConfigType.QYPROXY) { "Unexpected profile type" }
                profile = loaded
                val options = loaded.qyProxy ?: QyProxyOptions()
                if (!restore) {
                    mutableState.value = QyProxyEditorState(fields = fieldsFrom(loaded, options), role = options.role, loading = false, ready = true)
                } else {
                    mutableState.value = mutableState.value.copy(loading = false, ready = true)
                }
                rememberForm()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "QyProxy editor load profile=$guid", e)
                mutableState.value = mutableState.value.copy(loading = false, error = R.string.toast_failure)
            }
        }
    }

    fun onAction(action: QyProxyEditorAction) {
        val state = mutableState.value
        if (state.loading || state.saving || profile == null || state.savedGuid != null) return
        when (action) {
            is QyProxyEditorAction.Edit -> {
                mutableState.value = state.copy(fields = state.fields + (action.field to action.value), error = null)
                rememberForm()
            }
            is QyProxyEditorAction.Role -> {
                mutableState.value = state.copy(role = action.value, error = null)
                rememberForm()
            }
            QyProxyEditorAction.Save -> save()
        }
    }

    private fun rememberForm() {
        savedState["qy_guid"] = guid
        savedState["qy_ready"] = true
        savedState["qy_role"] = mutableState.value.role
        mutableState.value.fields.forEach { (key, value) -> savedState["qy_${key.name}"] = value }
    }

    private fun save() {
        val state = mutableState.value
        val config = buildProfile(state, profile ?: return)
        if (config.remarks.isBlank() || !QyProxyFmt.isValid(config)) {
            mutableState.value = state.copy(error = R.string.qyproxy_invalid_config)
            return
        }
        mutableState.value = state.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                val (result, restart) = withContext(ioDispatcher) {
                    config.description = AngConfigManager.generateDescription(config)
                    val result = persist(guid, config)
                    check(result.isNotBlank()) { "QyProxy profile save returned an empty ID" }
                    result to (running && result == selectedGuid())
                }
                mutableState.value = mutableState.value.copy(saving = false, savedGuid = result, restartService = restart)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "QyProxy editor save role=${state.role} profile=$guid", e)
                mutableState.value = mutableState.value.copy(saving = false, error = R.string.toast_failure)
            }
        }
    }

    private fun fieldsFrom(p: ProfileItem, o: QyProxyOptions) = mapOf(
        QyProxyField.Remarks to p.remarks, QyProxyField.Address to p.server.orEmpty(), QyProxyField.Port to p.serverPort.orEmpty(),
        QyProxyField.Username to p.username.orEmpty(), QyProxyField.Password to p.password.orEmpty(), QyProxyField.SN to o.sn,
        QyProxyField.GameId to o.gameId.takeIf { it != 0L }?.toString().orEmpty(),
        QyProxyField.ServerId to o.serverId.takeIf { it != 0L }?.toString().orEmpty(),
        QyProxyField.GameArea to o.gameArea, QyProxyField.Zone to o.zone, QyProxyField.ClientType to o.clientType.toString(),
        QyProxyField.Product to o.product, QyProxyField.Version to o.version
    )

    private fun buildProfile(state: QyProxyEditorState, initial: ProfileItem): ProfileItem {
        fun value(field: QyProxyField) = state.fields[field].orEmpty()
        return initial.copy(
            remarks = value(QyProxyField.Remarks).trim(), server = value(QyProxyField.Address).trim(), serverPort = value(QyProxyField.Port).trim(),
            username = value(QyProxyField.Username), password = value(QyProxyField.Password),
            qyProxy = QyProxyOptions(state.role, value(QyProxyField.SN),
                value(QyProxyField.GameId).toLongOrNull() ?: 0, value(QyProxyField.ServerId).toLongOrNull() ?: 0,
                value(QyProxyField.ClientType).toLongOrNull() ?: 0, value(QyProxyField.GameArea), value(QyProxyField.Zone),
                value(QyProxyField.Product), value(QyProxyField.Version))
        )
    }
}
