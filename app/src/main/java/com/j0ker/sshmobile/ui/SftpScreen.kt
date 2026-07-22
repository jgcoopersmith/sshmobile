package com.j0ker.sshmobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.j0ker.sshmobile.data.ConnectionProfile
import com.j0ker.sshmobile.ssh.RemoteFile
import com.j0ker.sshmobile.ssh.SftpSession
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

/**
 * Port of `Forms/SftpBrowserForm.cs`. The desktop's multi-select ListView with
 * a button bar becomes a single-select list — a phone has no modifier keys —
 * with the file picker replaced by SAF.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SftpScreen(profile: ConnectionProfile, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember(profile.id) { SftpSession(context, profile) }

    var path by remember { mutableStateOf("/") }
    var entries by remember { mutableStateOf<List<RemoteFile>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<RemoteFile?>(null) }
    var downloadTarget by remember { mutableStateOf<RemoteFile?>(null) }

    val hostKeyPrompt by session.hostKeyPrompter.pending.collectAsStateWithLifecycle()

    fun refresh(target: String = path) {
        scope.launch {
            busy = true
            runCatching { session.list(target) }
                .onSuccess { path = target; entries = it; status = null }
                .onFailure { status = "Error listing directory: ${it.message}" }
            busy = false
        }
    }

    val saveTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val file = downloadTarget
        downloadTarget = null
        if (uri == null || file == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            runCatching { session.download(file, uri) }
                .onSuccess { status = "Downloaded ${file.name}." }
                .onFailure { status = "Download failed: ${it.message}" }
            busy = false
        }
    }

    val pickUpload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "upload"
            runCatching { session.upload(uri, name, path) }
                .onSuccess { status = "Uploaded $name."; refresh() }
                .onFailure { status = "Upload failed: ${it.message}"; busy = false }
        }
    }

    LaunchedEffect(profile.id) {
        runCatching { session.connect() }
            .onSuccess { refresh("/") }
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

    DisposableEffect(profile.id) {
        onDispose { session.disconnect() }
    }

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
                    IconButton(onClick = { refresh(SftpSession.parentOf(path)) }) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "Up")
                    }
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { pickUpload.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.Upload, contentDescription = "Upload")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                path,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
            )
            status?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(16.dp, 0.dp, 16.dp, 8.dp),
                )
            }

            if (busy) {
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
                                if (entry.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
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
                                    IconButton(onClick = {
                                        downloadTarget = entry
                                        saveTo.launch(entry.name)
                                    }) {
                                        Icon(Icons.Default.Download, contentDescription = "Download")
                                    }
                                }
                                IconButton(onClick = { pendingDelete = entry }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                                }
                            }
                        },
                        modifier = Modifier.clickable {
                            if (entry.isDirectory) refresh(entry.path)
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
                busy = true
                runCatching { session.delete(entry) }
                    .onSuccess { refresh() }
                    .onFailure { status = "Delete failed: ${it.message}"; busy = false }
            }
        }
    }
}
