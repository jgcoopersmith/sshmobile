package com.j0ker.sshmobile.ui

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.j0ker.sshmobile.chat.ChatEvent
import com.j0ker.sshmobile.chat.ChatServer
import com.j0ker.sshmobile.data.AppSettings
import com.j0ker.sshmobile.data.ConnectionProfile
import com.j0ker.sshmobile.data.PeerProfile
import com.j0ker.sshmobile.data.Store
import com.j0ker.sshmobile.service.SshService
import com.j0ker.sshmobile.ssh.SshSession
import com.j0ker.sshmobile.ssh.SshState
import com.j0ker.sshmobile.ssh.TerminalEvent
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** How a run of text is coloured. Replaces the desktop's per-append `Color`. */
enum class LineKind { Output, System, Error, Peer, Self }

data class Segment(val text: String, val kind: LineKind)

/** A tab in the desktop's `TabControl`. */
sealed class Tab {
    abstract val id: String
    abstract val title: String
    val segments = mutableStateListOf<Segment>()

    /** Caps scrollback the way the desktop relied on RichTextBox's own limit. */
    fun append(text: String, kind: LineKind, maxSegments: Int = MAX_SEGMENTS) {
        segments.add(Segment(text, kind))
        while (segments.size > maxSegments) segments.removeAt(0)
    }

    private companion object {
        const val MAX_SEGMENTS = 4000
    }
}

class TerminalTab(
    override val id: String,
    val profile: ConnectionProfile,
    val session: SshSession,
) : Tab() {
    override val title: String get() = profile.label
    var state by mutableStateOf(SshState.Disconnected)
}

class ChatTab(
    override val id: String,
    val peerId: String,
    val displayName: String,
) : Tab() {
    override val title: String get() = displayName
}

/**
 * Replaces `Forms/MainForm.cs`: it owns the profile and peer lists, the chat
 * server, and the open tabs, and it wires each session's event stream into the
 * scrollback the UI renders.
 */
class SessionViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Store(app)

    var profiles by mutableStateOf(store.loadConnections())
        private set
    var peers by mutableStateOf(store.loadPeers())
        private set
    var settings by mutableStateOf(store.loadSettings())
        private set

    val tabs = mutableStateListOf<Tab>()
    var activeTabId by mutableStateOf<String?>(null)

    val activeTab: Tab? get() = tabs.firstOrNull { it.id == activeTabId }

    private val chatServer = ChatServer(settings.localUsername).also { server ->
        server.start(settings.chatListenPort)
        viewModelScope.launch {
            server.events.collect { event -> onChatEvent(event) }
        }
    }

    val chatListenPort get() = chatServer.listenPort

    // ------------------------------------------------------------------ chat

    private fun onChatEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.PeerConnected -> {
                val tab = openChatTab(event.peerId, event.displayName)
                tab.append("*** Connected to ${event.displayName}.\n", LineKind.System)
            }
            is ChatEvent.PeerDisconnected ->
                chatTabFor(event.peerId)?.append(
                    "*** ${event.displayName} disconnected.\n",
                    LineKind.System,
                )
            is ChatEvent.Message ->
                chatTabFor(event.peerId)?.append(
                    "[${stamp()}] ${event.sender}: ${event.text}\n",
                    LineKind.Peer,
                )
            is ChatEvent.Failed ->
                (activeTab ?: tabs.firstOrNull())?.append("*** ${event.reason}\n", LineKind.Error)
        }
        refreshService()
    }

    private fun chatTabFor(peerId: String) =
        tabs.filterIsInstance<ChatTab>().firstOrNull { it.peerId == peerId }

    /** Port of `OpenChatTab` — reuses the tab if the peer already has one. */
    private fun openChatTab(peerId: String, displayName: String): ChatTab {
        chatTabFor(peerId)?.let { activeTabId = it.id; return it }
        val tab = ChatTab(UUID.randomUUID().toString(), peerId, displayName)
        tabs.add(tab)
        activeTabId = tab.id
        return tab
    }

    /** Port of `ConnectToPeer` / `DoConnect`; the tab opens when the peer answers. */
    fun connectToPeer(host: String, port: Int) = chatServer.connectTo(host, port)

    fun connectToPeer(peer: PeerProfile) = connectToPeer(peer.host, peer.port)

    fun sendChat(tab: ChatTab, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        chatServer.sendTo(tab.peerId, trimmed)
        tab.append("[${stamp()}] ${chatServer.localUsername}: $trimmed\n", LineKind.Self)
    }

    // ------------------------------------------------------------------- ssh

    /** Port of `OpenTerminal`. */
    fun openTerminal(profile: ConnectionProfile) {
        val session = SshSession(getApplication(), profile)
        val tab = TerminalTab(UUID.randomUUID().toString(), profile, session)
        tabs.add(tab)
        activeTabId = tab.id

        viewModelScope.launch {
            session.events.collect { event ->
                when (event) {
                    is TerminalEvent.Output -> tab.append(event.text, LineKind.Output)
                    is TerminalEvent.System -> tab.append("\n${event.text}\n", LineKind.System)
                    is TerminalEvent.Error -> tab.append("\n${event.text}\n", LineKind.Error)
                }
            }
        }
        viewModelScope.launch {
            session.state.collect { state ->
                tab.state = state
                refreshService()
            }
        }

        session.connect(settings.terminalColumns, settings.terminalRows)
    }

    fun send(tab: TerminalTab, line: String) {
        tab.session.send(line)
        // Echo locally so the input is visible even when the remote has echo off.
        tab.append("$line\n", LineKind.Self)
    }

    /** Ctrl-key and arrow chords a soft keyboard cannot produce. */
    fun sendControl(tab: TerminalTab, sequence: String) = tab.session.sendRaw(sequence)

    // ------------------------------------------------------------------ tabs

    /** Port of `CloseTab`. */
    fun closeTab(tab: Tab) {
        if (tab is TerminalTab) tab.session.dispose()
        tabs.remove(tab)
        if (activeTabId == tab.id) activeTabId = tabs.lastOrNull()?.id
        refreshService()
    }

    private fun refreshService() {
        val live = tabs.count { it is TerminalTab && it.state == SshState.Connected } +
            tabs.count { it is ChatTab }
        if (live > 0) {
            SshService.start(getApplication(), "$live session${if (live == 1) "" else "s"} open")
        } else {
            SshService.stop(getApplication())
        }
    }

    // -------------------------------------------------------- profile CRUD

    fun saveProfile(profile: ConnectionProfile) {
        profiles = profiles.filterNot { it.id == profile.id } + profile
        store.saveConnections(profiles)
    }

    fun deleteProfile(profile: ConnectionProfile) {
        profiles = profiles.filterNot { it.id == profile.id }
        store.saveConnections(profiles)
    }

    fun savePeer(peer: PeerProfile) {
        peers = peers.filterNot { it.id == peer.id } + peer
        store.savePeers(peers)
    }

    fun deletePeer(peer: PeerProfile) {
        peers = peers.filterNot { it.id == peer.id }
        store.savePeers(peers)
    }

    fun saveSettings(updated: AppSettings) {
        settings = updated
        store.saveSettings(updated)
    }

    override fun onCleared() {
        tabs.filterIsInstance<TerminalTab>().forEach { it.session.dispose() }
        chatServer.dispose()
        SshService.stop(getApplication())
        super.onCleared()
    }

    private fun stamp(): String = TIME_FORMAT.format(Date())

    private companion object {
        val TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.US)
    }
}
