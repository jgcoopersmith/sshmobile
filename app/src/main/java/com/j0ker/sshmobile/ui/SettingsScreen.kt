package com.j0ker.sshmobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.j0ker.sshmobile.BuildConfig

/**
 * Mobile-only. The desktop took its chat identity from `Environment.UserName`
 * and always let the OS pick the listen port; on a phone both need to be set
 * explicitly so a peer can reach you.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SessionViewModel, onBack: () -> Unit) {
    var username by remember { mutableStateOf(vm.settings.localUsername) }
    var listenPort by remember { mutableStateOf(vm.settings.chatListenPort.toString()) }
    var fontSize by remember { mutableStateOf(vm.settings.terminalFontSize.toString()) }
    var columns by remember { mutableStateOf(vm.settings.terminalColumns.toString()) }
    var rows by remember { mutableStateOf(vm.settings.terminalRows.toString()) }

    val actualPort by vm.chatListenPort.collectAsStateWithLifecycle()

    fun persist() {
        vm.saveSettings(
            vm.settings.copy(
                localUsername = username.trim().ifEmpty { "android" },
                chatListenPort = listenPort.toIntOrNull()?.coerceIn(0, 65535) ?: 0,
                terminalFontSize = fontSize.toIntOrNull()?.coerceIn(8, 24) ?: 13,
                terminalColumns = columns.toIntOrNull()?.coerceIn(40, 400) ?: 100,
                terminalRows = rows.toIntOrNull()?.coerceIn(10, 200) ?: 40,
            ),
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { persist(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Chat", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                username,
                { username = it },
                label = { Text("Display name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            NumberField(listenPort, { listenPort = it }, "Listen port (0 = any)")
            Text(
                if (actualPort > 0) "Currently listening on $actualPort" else "Not listening",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Port changes take effect next launch.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("Terminal", style = MaterialTheme.typography.titleMedium)
            NumberField(fontSize, { fontSize = it }, "Font size")
            NumberField(columns, { columns = it }, "PTY columns")
            NumberField(rows, { rows = it }, "PTY rows")

            // Bumped automatically by .githooks/pre-commit, so the number below
            // identifies the exact commit a build came from.
            Spacer(Modifier.height(32.dp))
            Text(
                "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value,
        { onChange(it.filter(Char::isDigit)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}
