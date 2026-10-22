package com.v2ray.ang.ui.server

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.server.ProfileEditorResult.finishSaved

class ServerUuSniActivity : BaseComponentActivity() {
    private val viewModel: UuSniEditorViewModel by viewModels {
        viewModelFactory { initializer { UuSniEditorViewModel(createSavedStateHandle()) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.open(intent.getStringExtra("guid").orEmpty(), intent.getStringExtra("subscriptionId").orEmpty(),
            intent.getBooleanExtra("isRunning", false))
    }

    @Composable
    override fun ScreenContent() {
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        LaunchedEffect(state.savedGuid) {
            state.savedGuid?.let { finishSaved(it, state.restartService) }
        }
        Scaffold(topBar = {
            AppTopBar(title = stringResource(R.string.server_uusni), onBackClick = { finish() }, actions = {
                IconButton(onClick = { viewModel.onAction(UuSniEditorAction.Save) }, enabled = state.ready && !state.saving) {
                    Icon(painterResource(R.drawable.ic_fab_check), contentDescription = stringResource(R.string.acc_save))
                }
            })
        }) { innerPadding ->
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)
                .imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.loading || state.saving) CircularProgressIndicator()
                state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                Text(stringResource(R.string.uusni_hint))
                UuSniField.entries.forEach { field ->
                    key(field) {
                        OutlinedTextField(value = state.fields[field].orEmpty(), onValueChange = {
                            viewModel.onAction(UuSniEditorAction.Edit(field, it))
                        }, label = { Text(stringResource(field.label)) }, modifier = Modifier.fillMaxWidth(),
                            singleLine = true, enabled = state.ready && !state.saving,
                            keyboardOptions = KeyboardOptions(keyboardType = if (field.numeric) KeyboardType.Number else KeyboardType.Text))
                    }
                }
                Row(modifier = Modifier.fillMaxWidth().toggleable(value = state.sniCrypt, role = Role.Checkbox,
                    enabled = state.ready && !state.saving, onValueChange = { viewModel.onAction(UuSniEditorAction.Crypt(it)) })
                    .padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(checked = state.sniCrypt, onCheckedChange = null, enabled = state.ready && !state.saving)
                    Text(stringResource(R.string.uusni_crypt))
                }
            }
        }
    }
}
