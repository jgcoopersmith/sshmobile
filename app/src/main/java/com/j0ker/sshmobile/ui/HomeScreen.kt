package com.j0ker.sshmobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.j0ker.sshmobile.R
import com.j0ker.sshmobile.data.ConnectionProfile
import com.j0ker.sshmobile.data.PeerProfile

private enum class HomeList { Ssh, Peers }

/**
 * Port of `MainForm`'s left panel. The desktop stacked the SSH connection list
 * above the chat peer list in a SplitContainer; a phone has no room for both,
 * so they become two tabs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: SessionViewModel,
    onOpenSessions: () -> Unit,
    onOpenSftp: (ConnectionProfile) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var list by remember { mutableStateOf(HomeList.Ssh) }
    var editingProfile by remember { mutableStateOf<ConnectionProfile?>(null) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var editingPeer by remember { mutableStateOf<PeerProfile?>(null) }
    var showPeerDialog by remember { mutableStateOf(false) }
    var deletingProfile by remember { mutableStateOf<ConnectionProfile?>(null) }
    var deletingPeer by remember { mutableStateOf<PeerProfile?>(null) }

    val listenPort by vm.chatListenPort.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    if (vm.tabs.isNotEmpty()) {
                        IconButton(onClick = onOpenSessions) {
                            Icon(Icons.Default.Terminal, contentDescription = "Open sessions")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = {
                        if (list == HomeList.Ssh) {
                            editingProfile = null; showProfileDialog = true
                        } else {
                            editingPeer = null; showPeerDialog = true
                        }
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "New")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = list.ordinal) {
                Tab(list == HomeList.Ssh, { list = HomeList.Ssh }, text = { Text("SSH Connections") })
                Tab(list == HomeList.Peers, { list = HomeList.Peers }, text = { Text("Chat Peers") })
            }

            when (list) {
                HomeList.Ssh -> LazyColumn(Modifier.fillMaxSize()) {
                    items(vm.profiles, key = { it.id }) { profile ->
                        ListItem(
                            headlineContent = { Text(profile.label) },
                            supportingContent = {
                                Text(if (profile.useKeyAuth) "key auth" else "password auth")
                            },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = { onOpenSftp(profile) }) {
                                        Icon(Icons.Default.Folder, contentDescription = "SFTP")
                                    }
                                    IconButton(onClick = {
                                        editingProfile = profile; showProfileDialog = true
                                    }) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit")
                                    }
                                    IconButton(onClick = { deletingProfile = profile }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                                    }
                                }
                            },
                            // Tap replaces the desktop's double-click-to-connect.
                            modifier = Modifier.clickable {
                                vm.openTerminal(profile)
                                onOpenSessions()
                            },
                        )
                        HorizontalDivider()
                    }
                    if (vm.profiles.isEmpty()) {
                        item { EmptyHint("No saved connections. Tap + to add one.") }
                    }
                }

                HomeList.Peers -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Text(
                            if (listenPort > 0) "Listening on port $listenPort" else "Starting…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp, 8.dp),
                        )
                    }
                    items(vm.peers, key = { it.id }) { peer ->
                        ListItem(
                            headlineContent = { Text(peer.label) },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = { editingPeer = peer; showPeerDialog = true }) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit")
                                    }
                                    IconButton(onClick = { deletingPeer = peer }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                                    }
                                }
                            },
                            modifier = Modifier.clickable {
                                vm.connectToPeer(peer)
                                onOpenSessions()
                            },
                        )
                        HorizontalDivider()
                    }
                    if (vm.peers.isEmpty()) {
                        item { EmptyHint("No saved peers. Tap + to add one.") }
                    }
                }
            }
        }
    }

    if (showProfileDialog) {
        ConnectionDialog(
            existing = editingProfile,
            onDismiss = { showProfileDialog = false },
            onSave = { vm.saveProfile(it); showProfileDialog = false },
        )
    }
    if (showPeerDialog) {
        PeerDialog(
            existing = editingPeer,
            onDismiss = { showPeerDialog = false },
            onSave = { vm.savePeer(it); showPeerDialog = false },
        )
    }
    deletingProfile?.let { profile ->
        ConfirmDialog(profile.label, { deletingProfile = null }) {
            vm.deleteProfile(profile); deletingProfile = null
        }
    }
    deletingPeer?.let { peer ->
        ConfirmDialog(peer.label, { deletingPeer = null }) {
            vm.deletePeer(peer); deletingPeer = null
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(24.dp),
    )
}
