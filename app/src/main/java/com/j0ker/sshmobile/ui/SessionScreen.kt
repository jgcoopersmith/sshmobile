package com.j0ker.sshmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.j0ker.sshmobile.ssh.SshState

/** Control chords a soft keyboard cannot produce; the desktop had real keys. */
private val ESC = Char(27).toString()

private val CONTROL_KEYS = listOf(
    "Ctrl-C" to Char(3).toString(),
    "Ctrl-D" to Char(4).toString(),
    "Ctrl-Z" to Char(26).toString(),
    "Ctrl-L" to Char(12).toString(),
    "Tab" to Char(9).toString(),
    "Esc" to ESC,
    "Up" to ESC + "[A",
    "Down" to ESC + "[B",
    "Left" to ESC + "[D",
    "Right" to ESC + "[C",
)

/**
 * Port of `MainForm`'s TabControl plus `TerminalPanel` and `ChatPanel`. The
 * desktop closed tabs from a right-click menu; here each tab carries an ✕.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(vm: SessionViewModel, onBack: () -> Unit) {
    val active = vm.activeTab

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(active?.title ?: "Sessions", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            if (vm.tabs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No open sessions.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Column
            }

            val selectedIndex = vm.tabs.indexOfFirst { it.id == vm.activeTabId }.coerceAtLeast(0)
            ScrollableTabRow(selectedTabIndex = selectedIndex, edgePadding = 0.dp) {
                vm.tabs.forEach { tab ->
                    Tab(
                        selected = tab.id == vm.activeTabId,
                        onClick = { vm.activeTabId = tab.id },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(tab.title, maxLines = 1)
                                IconButton(onClick = { vm.closeTab(tab) }) {
                                    Icon(Icons.Default.Close, contentDescription = "Close ${tab.title}")
                                }
                            }
                        },
                    )
                }
            }

            when (val tab = vm.activeTab) {
                is TerminalTab -> TerminalPane(vm, tab, Modifier.weight(1f))
                is ChatTab -> ChatPane(vm, tab, Modifier.weight(1f))
                null -> Box(Modifier.weight(1f))
            }
        }
    }

    vm.pendingHostKey?.let { (_, prompt) ->
        HostKeyDialog(prompt) { vm.answerHostKey(it) }
    }
}

/** Port of `Controls/TerminalPanel.cs`. */
@Composable
private fun TerminalPane(vm: SessionViewModel, tab: TerminalTab, modifier: Modifier) {
    var input by remember(tab.id) { mutableStateOf("") }

    Column(modifier.fillMaxWidth()) {
        Scrollback(
            tab = tab,
            modifier = Modifier.weight(1f).background(TerminalBackground),
            monospace = true,
            fontSize = vm.settings.terminalFontSize,
        )

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp, 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CONTROL_KEYS.forEach { (label, sequence) ->
                AssistChip(onClick = { vm.sendControl(tab, sequence) }, label = { Text(label) })
            }
        }

        InputBar(
            value = input,
            onValueChange = { input = it },
            enabled = tab.state == SshState.Connected,
            placeholder = when (tab.state) {
                SshState.Connecting -> "Connecting…"
                SshState.Connected -> "Command"
                SshState.Failed -> "Connection failed"
                SshState.Disconnected -> "Disconnected"
            },
            monospace = true,
            onSend = {
                vm.send(tab, input)
                input = ""
            },
        )
    }
}

/** Port of `Controls/ChatPanel.cs`. */
@Composable
private fun ChatPane(vm: SessionViewModel, tab: ChatTab, modifier: Modifier) {
    var input by remember(tab.id) { mutableStateOf("") }

    Column(modifier.fillMaxWidth()) {
        Text(
            "Chat with: ${tab.displayName}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp, 6.dp),
        )
        Scrollback(tab = tab, modifier = Modifier.weight(1f), monospace = false, fontSize = 14)
        InputBar(
            value = input,
            onValueChange = { input = it },
            enabled = true,
            placeholder = "Message",
            monospace = false,
            onSend = {
                vm.sendChat(tab, input)
                input = ""
            },
        )
    }
}

/**
 * The shared scrollback view. `AppendOutput` coloured each run as it went and
 * called ScrollToCaret; here the runs become one [AnnotatedString] and the
 * list auto-scrolls when new output arrives.
 */
@Composable
private fun Scrollback(tab: Tab, modifier: Modifier, monospace: Boolean, fontSize: Int) {
    // The terminal keeps its dark canvas in both themes, so it always takes the
    // dark palette; chat follows the system theme.
    val darkPalette = monospace || isSystemInDarkTheme()
    val scroll = rememberScrollState()

    val text: AnnotatedString = buildAnnotatedString {
        tab.segments.forEach { segment ->
            withStyle(SpanStyle(color = colorFor(segment.kind, darkPalette))) {
                append(segment.text)
            }
        }
    }

    // The desktop called ScrollToCaret on every append.
    LaunchedEffect(tab.segments.size) {
        scroll.animateScrollTo(scroll.maxValue)
    }

    Column(modifier.fillMaxWidth().verticalScroll(scroll)) {
        Text(
            text = text,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            fontSize = fontSize.sp,
            modifier = Modifier.fillMaxWidth().padding(8.dp),
        )
    }
}

/** Port of the desktop's input TextBox + Send button, Enter to submit. */
@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    placeholder: String,
    monospace: Boolean,
    onSend: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            placeholder = { Text(placeholder) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSend, enabled = enabled) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
        }
    }
}
