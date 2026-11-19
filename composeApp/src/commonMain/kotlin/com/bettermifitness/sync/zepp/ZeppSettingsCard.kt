package com.bettermifitness.sync.zepp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.bettermifitness.sync.i18n.L10n
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ZeppSettingsCard(viewModel: ZeppSettingsViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()
    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("CN") }
    var expanded by remember { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(L10n.string(L10n.zeppTitle), style = MaterialTheme.typography.titleMedium)
            Text(L10n.string(L10n.zeppSourceHint), style = MaterialTheme.typography.bodySmall)
            if (!state.connected) {
                TextButton(onClick = { expanded = !expanded }, enabled = !state.busy) { Text(L10n.string(L10n.zeppConnect)) }
                if (expanded) {
                    OutlinedTextField(account, { account = it }, label = { Text(L10n.string(L10n.zeppAccount)) },
                        enabled = !state.busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(password, { password = it }, label = { Text(L10n.string(L10n.zeppPassword)) },
                        visualTransformation = PasswordVisualTransformation(), enabled = !state.busy,
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(country, { country = it }, label = { Text(L10n.string(L10n.zeppCountry)) },
                        enabled = !state.busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = {
                        val entered = password
                        password = ""
                        viewModel.connect(account, entered, country)
                    }, enabled = !state.busy && account.isNotBlank() && password.isNotEmpty()) {
                        Text(L10n.string(L10n.zeppConnect))
                    }
                    Text(L10n.string(L10n.zeppPasswordHint), style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text(L10n.string(L10n.zeppConnected))
                Text(L10n.string(L10n.zeppAutomaticWorkoutHint), style = MaterialTheme.typography.bodySmall)
                Text(L10n.string(L10n.zeppSyncHint), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = viewModel::disconnect, enabled = !state.busy) { Text(L10n.string(L10n.zeppDisconnect)) }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(L10n.string(L10n.zeppVitalityHint), style = MaterialTheme.typography.bodySmall)
        }
    }
}
