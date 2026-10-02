package com.v2ray.ang.ui.server

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import com.v2ray.ang.R
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.util.HttpHeaderParser

class ServerHttpActivity : BaseServerActivity() {

    override val serverConfigType: EConfigType = EConfigType.HTTP

    @Composable
    override fun ScreenContent() {
        val scope = rememberCoroutineScope()
        val uiState = rememberSaveable(saver = ServerUiState.Saver) {
            ServerUiState.from(
                initialConfig = initialConfig
            )
        }.apply {
            configType = serverConfigType
        }

        ServerEditorScaffold(
            title = serverConfigType.toString(),
            onSaveClick = { saveHttpServer(uiState) }
        ) {
            CommonBasicFields(uiState)
            HttpProtocolFields(uiState)

        }
    }

    @Composable
    private fun HttpProtocolFields(state: ServerUiState) {
        FormTextField(
            stringResource(R.string.server_lab_security4),
            state.username,
            { state.username = it }
        )
        FormTextField(
            stringResource(R.string.server_lab_id4),
            state.password,
            { state.password = it }
        )
        FormTextField(
            stringResource(R.string.server_lab_http_headers),
            state.httpHeadersText,
            { state.httpHeadersText = it },
            placeholder = stringResource(R.string.server_lab_http_headers_hint),
            maxLines = 8
        )
    }

    private fun saveHttpServer(state: ServerUiState) {
        val result = HttpHeaderParser.parse(state.httpHeadersText)
        val error = result.error
        if (error != null) {
            val errorLabel = when (error.type) {
                HttpHeaderParser.ErrorType.MISSING_COLON -> R.string.server_lab_http_headers_missing_colon
                HttpHeaderParser.ErrorType.INVALID_NAME -> R.string.server_lab_http_headers_invalid_name
                HttpHeaderParser.ErrorType.INVALID_VALUE -> R.string.server_lab_http_headers_invalid_value
                HttpHeaderParser.ErrorType.DUPLICATE_NAME -> R.string.server_lab_http_headers_duplicate_name
            }
            toast(
                getString(
                    R.string.server_lab_http_headers_invalid,
                    error.lineNumber,
                    getString(errorLabel)
                )
            )
            return
        }
        saveServer(state)
    }
}
