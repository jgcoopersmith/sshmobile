package com.j0ker.sshmobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.j0ker.sshmobile.data.ConnectionProfile
import com.j0ker.sshmobile.data.LocalFiles
import com.j0ker.sshmobile.ssh.RemoteFile
import com.j0ker.sshmobile.ssh.SftpSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

private enum class Pane { Remote, Local }

/** One row, from either side. */
private data class Entry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long,
)

private fun RemoteFile.toEntry() = Entry(name, path, isDirectory, size, modified)
private fun File.toEntry() = Entry(name, absolutePath, isDirectory, length(), lastModified())

/**
 * Port of `Forms/SftpBrowserForm.cs`, with a local pane the desktop never had —
 * on Windows you drag files from Explorer, which a phone has no equivalent of.
 *
 * Both sides keep their own directory, so a transfer needs no file picker: the
 * destination is simply wherever the other pane is pointing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SftpScreen(profile: ConnectionProfile, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember(profile.id) { SftpSession(context, profile) }

    var pane by rememberSaveable { mutableStateOf(Pane.Remote) }
    var remotePath by rememberSaveable { mutableStateOf("/") }
    var localPath by rememberSaveable { mutableStateOf(LocalFiles.defaultRoot().absolutePath) }
    var remoteEntries by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var localEntries by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<Entry?>(null) }
    var hasLocalAccess by remember { mutableStateOf(LocalFiles.hasAccess(context)) }

    val hostKeyPrompt by session.hostKeyPrompter.pending.collectAsStateWithLifecycle()

    fun refreshLocal(target: String = localPath) {
        scope.launch {
            hasLocalAccess = LocalFiles.hasAccess(context)
            if (!hasLocalAccess) return@launch
            val listing = withContext(Dispatchers.IO) {
                runCatching { LocalFiles.list(File(target)).map { it.toEntry() } }
            }
            listing
                .onSuccess { localPath = target; localEntries = it }
                .onFailure { status = "Cannot read $target: ${it.message}" }
        }
    }

    fun refreshRemote(target: String = remotePath) {
        scope.launch {
            busy = true
            runCatching { session.list(target) }
                .onSuccess { remotePath = target; remoteEntries = it.map { f -> f.toEntry() }; status = null }
                .onFailure { status = "Error listing directory: ${it.message}" }
            busy = false
        }
    }

    // "All files access" is granted on a settings screen, so the answer only
    // arrives when the user comes back to the app.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !hasLocalAccess) {
                hasLocalAccess = LocalFiles.hasAccess(context)
                if (hasLocalAccess) refreshLocal()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(profile.id) {
        runCatching { session.connect() }
            .onSuccess { refreshRemote("/"); refreshLocal() }
            .onFailure { failure ->
                val mismatch = session.hostKeyMismatch
                status = when {
                    mismatch != null ->
                        "Host key for ${mismatch.hostname} changed — refusing to connect. " +
                            "Known ${mismatch.expected}, got ${mismatch.actual}."
                    session.hostKeyDeclined -> "Host key not accepted. Not connecting."
                    else -> "SFTP connection failed: ${failure.message}"
                }
                busy = false
            }
    }

    DisposableEffect(profile.id) { onDispose { session.disconnect() } }

    val entries = if (pane == Pane.Remote) remoteEntries else localEntries
    val currentPath = if (pane == Pane.Remote) remotePath else localPath
    val otherPath = if (pane == Pane.Remote) localPath else remotePath

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SFTP — ${profile.label}", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    LabelledAction(Icons.Default.ArrowUpward, "Up") {
                        if (pane == Pane.Remote) refreshRemote(SftpSession.parentOf(remotePath))
                        else refreshLocal(LocalFiles.parentOf(localPath))
                    }
                    LabelledAction(Icons.Default.Refresh, "Refresh") {
                        if (pane == Pane.Remote) refreshRemote() else refreshLocal()
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            SingleChoiceSegmentedButtonRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                SegmentedButton(
                    selected = pane == Pane.Remote,
                    onClick = { pane = Pane.Remote },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Remote") }
                SegmentedButton(
                    selected = pane == Pane.Local,
                    onClick = { pane = Pane.Local; refreshLocal() },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Local") }
            }

            Text(
                currentPath,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(16.dp, 2.dp),
            )
            // Transfers land in the other pane's directory, so it has to be visible.
            Text(
                if (pane == Pane.Remote) "Downloads to $otherPath" else "Uploads to $otherPath",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(16.dp, 0.dp, 16.dp, 6.dp),
            )
            status?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(16.dp, 0.dp, 16.dp, 8.dp),
                )
            }

            if (pane == Pane.Local && !hasLocalAccess) {
                LocalAccessPrompt { context.startActivity(LocalFiles.accessSettingsIntent(context)) }
                return@Column
            }

            if (busy && pane == Pane.Remote) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(entries, key = { it.path }) { entry ->
                    ListItem(
                        leadingContent = {
                            Icon(
                                if (entry.isDirectory) Icons.Default.Folder
                                else Icons.AutoMirrored.Filled.InsertDriveFile,
                                contentDescription = null,
                            )
                        },
                        headlineContent = { Text(entry.name) },
                        supportingContent = {
                            Text(
                                if (entry.isDirectory) "<DIR>"
                                else "${SftpSession.formatSize(entry.size)} · ${STAMP.format(Date(entry.modified))}",
                            )
                        },
                        trailingContent = {
                            Row {
                                if (!entry.isDirectory) {
                                    if (pane == Pane.Remote) {
                                        LabelledAction(Icons.Default.Download, "Get") {
                                            transfer(scope, { status = it }, { busy = it }) {
                                                session.download(
                                                    RemoteFile(
                                                        entry.name,
                                                        entry.path,
                                                        false,
                                                        entry.size,
                                                        entry.modified,
                                                    ),
                                                    File(localPath, entry.name),
                                                )
                                                "Downloaded ${entry.name}."
                                            }
                                        }
                                    } else {
                                        LabelledAction(Icons.Default.Upload, "Put") {
                                            transfer(scope, { status = it }, { busy = it }) {
                                                session.upload(File(entry.path), remotePath)
                                                refreshRemote()
                                                "Uploaded ${entry.name}."
                                            }
                                        }
                                    }
                                }
                                LabelledAction(Icons.Default.Delete, "Delete") { pendingDelete = entry }
                            }
                        },
                        modifier = Modifier.clickable {
                            if (!entry.isDirectory) return@clickable
                            if (pane == Pane.Remote) refreshRemote(entry.path) else refreshLocal(entry.path)
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    hostKeyPrompt?.let { prompt ->
        HostKeyDialog(prompt) { session.hostKeyPrompter.respond(it) }
    }

    pendingDelete?.let { entry ->
        ConfirmDialog(entry.name, { pendingDelete = null }) {
            pendingDelete = null
            scope.launch {
                val outcome = runCatching {
                    if (pane == Pane.Remote) {
                        session.delete(
                            RemoteFile(entry.name, entry.path, entry.isDirectory, entry.size, entry.modified),
                        )
                    } else {
                        // A non-empty directory fails rather than recursing; the
                        // phone's storage is not somewhere to delete blindly.
                        withContext(Dispatchers.IO) {
                            if (!File(entry.path).delete()) error("could not delete")
                        }
                    }
                }
                outcome
                    .onSuccess {
                        status = "Deleted ${entry.name}."
                        if (pane == Pane.Remote) refreshRemote() else refreshLocal()
                    }
                    .onFailure { status = "Delete failed: ${it.message}" }
            }
        }
    }
}

/** Runs a transfer, reporting its outcome into [setStatus]. */
private fun transfer(
    scope: kotlinx.coroutines.CoroutineScope,
    setStatus: (String) -> Unit,
    setBusy: (Boolean) -> Unit,
    block: suspend () -> String,
) {
    scope.launch {
        setBusy(true)
        runCatching { block() }
            .onSuccess { setStatus(it) }
            .onFailure { setStatus("Transfer failed: ${it.message}") }
        setBusy(false)
    }
}

@Composable
private fun LocalAccessPrompt(onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Browsing the phone's storage needs \"All files access\".",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Android grants this on its own settings screen rather than in a dialog. " +
                "This screen updates when you come back.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onGrant) { Text("Open settings") }
    }
}
