package com.v2ray.ang.ui.server

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.fmt.CmccSocksFmt
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.FormTextField

/** Editor for the CMCC-style SOCKS variants backed by the patched Xray core. */
class ServerPrivateSocksActivity : BaseServerActivity() {

    override val serverConfigType: EConfigType = EConfigType.PRIVATE_SOCKS

    @Composable
    override fun ScreenContent() {
        val uiState = rememberSaveable(saver = ServerUiState.Saver) {
            ServerUiState.from(initialConfig = initialConfig)
        }.apply {
            configType = serverConfigType
        }
        val protocolOptions = stringArrayResource(R.array.cmcc_protocols).toList()

        ServerEditorScaffold(
            title = stringResource(R.string.server_private_socks),
            onSaveClick = { saveServer(uiState) }
        ) {
            CommonBasicFields(uiState)
            FormTextField(
                stringResource(R.string.server_lab_cmcc_username),
                uiState.username,
                { uiState.username = it }
            )
            FormTextField(
                stringResource(R.string.server_lab_cmcc_password),
                uiState.password,
                { uiState.password = it }
            )
            FormDropdownField(
                stringResource(R.string.server_lab_cmcc_protocol),
                uiState.cmccProtocol,
                protocolOptions,
                { uiState.cmccProtocol = it }
            )
        }
    }

    override fun validateProtocolConfig(config: ProfileItem): Boolean {
        if (config.username.isNullOrBlank()) {
            toast(R.string.server_lab_cmcc_username)
            return false
        }
        if (config.password.isNullOrBlank()) {
            toast(R.string.server_lab_cmcc_password)
            return false
        }
        if (CmccSocksFmt.normalizeCmccProtocol(config.cmccProtocol) == null) {
            toast(R.string.server_lab_cmcc_protocol)
            return false
        }
        return true
    }
}
