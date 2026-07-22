package com.j0ker.sshmobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.j0ker.sshmobile.data.ConnectionProfile
import com.j0ker.sshmobile.data.PeerProfile

/**
 * Port of `Forms/ConnectionDialog.cs`. The desktop's OpenFileDialog for the
 * private key becomes a SAF document picker, and the key URI is persisted
 * with read permission so it survives a restart.
 */
@Composable
fun ConnectionDialog(
    existing: ConnectionProfile?,
    onDismiss: () -> Unit,
    onSave: (ConnectionProfile) -> Unit,
) {
    val seed = existing ?: ConnectionProfile()
    var name by remember { mutableStateOf(seed.name) }
    var host by remember { mutableStateOf(seed.host) }
    var port by remember { mutableStateOf(seed.port.toString()) }
    var username by remember { mutableStateOf(seed.username) }
    var password by remember { mutableStateOf(seed.password) }
    var keyUri by remember { mutableStateOf(seed.privateKeyUri) }
    var passphrase by remember { mutableStateOf(seed.privateKeyPassphrase) }
    var useKeyAuth by remember { mutableStateOf(seed.useKeyAuth) }
    var error by remember { mutableStateOf<String?>(null) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val pickKey = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            keyUri = uri.toString()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New Connection" else "Edit Connection") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(host, { host = it }, label = { Text("Host") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    port,
                    { port = it.filter(Char::isDigit) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!useKeyAuth, { useKeyAuth = false }, { Text("Password") })
                    FilterChip(useKeyAuth, { useKeyAuth = true }, { Text("Private Key") })
                }

                if (useKeyAuth) {
                    TextButton(onClick = { pickKey.launch(arrayOf("*/*")) }) {
                        Text(if (keyUri.isEmpty()) "Choose key file…" else "Key selected — change")
                    }
                    OutlinedTextField(
                        passphrase,
                        { passphrase = it },
                        label = { Text("Key passphrase (optional)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        password,
                        { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Same validation as the desktop's BtnOk_Click.
                val portNum = port.toIntOrNull()
                when {
                    host.isBlank() -> error = "Host is required."
                    portNum == null || portNum !in 1..65535 -> error = "Port must be 1–65535."
                    useKeyAuth && keyUri.isEmpty() -> error = "Choose a private key file."
                    else -> onSave(
                        seed.copy(
                            name = name.trim(),
                            host = host.trim(),
                            port = portNum,
                            username = username.trim(),
                            password = if (useKeyAuth) "" else password,
                            privateKeyUri = if (useKeyAuth) keyUri else "",
                            privateKeyPassphrase = if (useKeyAuth) passphrase else "",
                            useKeyAuth = useKeyAuth,
                        ),
                    )
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Port of `Forms/PeerDialog.cs`. */
@Composable
fun PeerDialog(
    existing: PeerProfile?,
    onDismiss: () -> Unit,
    onSave: (PeerProfile) -> Unit,
) {
    val seed = existing ?: PeerProfile()
    var name by remember { mutableStateOf(seed.name) }
    var host by remember { mutableStateOf(seed.host) }
    var port by remember { mutableStateOf(seed.port.toString()) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New Peer" else "Edit Peer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(host, { host = it }, label = { Text("Host") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    port,
                    { port = it.filter(Char::isDigit) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val portNum = port.toIntOrNull()
                when {
                    host.isBlank() -> error = "Host is required."
                    portNum == null || portNum !in 1..65535 -> error = "Port must be 1–65535."
                    else -> onSave(seed.copy(name = name.trim(), host = host.trim(), port = portNum))
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Port of the confirm-before-delete MessageBox used by both lists. */
@Composable
fun ConfirmDialog(label: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm") },
        text = { Text("Delete '$label'?") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
