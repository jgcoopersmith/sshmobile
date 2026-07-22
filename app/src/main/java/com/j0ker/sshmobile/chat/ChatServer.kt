package com.j0ker.sshmobile.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The `MessageReceived` / `PeerConnected` / `PeerDisconnected` events, as one stream. */
sealed interface ChatEvent {
    data class Message(val peerId: String, val sender: String, val text: String) : ChatEvent
    data class PeerConnected(val peerId: String, val displayName: String) : ChatEvent
    data class PeerDisconnected(val peerId: String, val displayName: String) : ChatEvent
    data class Failed(val reason: String) : ChatEvent
}

/**
 * Peer-to-peer chat over a plain TCP socket. Line protocol unchanged from
 * `Services/ChatServer.cs` — `HELLO:<name>` handshake then `MSG:<text>` — so a
 * phone and a desktop SSHClient can talk to each other.
 *
 * The desktop spun a background [Thread] per socket; here each peer gets a
 * coroutine on [Dispatchers.IO].
 */
class ChatServer(val localUsername: String) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val peers = ConcurrentHashMap<String, PeerConnection>()
    private var listener: ServerSocket? = null

    private val _events = MutableSharedFlow<ChatEvent>(replay = 0, extraBufferCapacity = 128)
    val events: SharedFlow<ChatEvent> = _events.asSharedFlow()

    private val _listenPort = MutableStateFlow(0)
    val listenPort: StateFlow<Int> = _listenPort.asStateFlow()

    /** Port 0 lets the OS pick, as on the desktop. */
    fun start(port: Int = 0) {
        if (listener != null) return
        scope.launch {
            try {
                val socket = ServerSocket(port)
                listener = socket
                _listenPort.value = socket.localPort
                acceptLoop(socket)
            } catch (e: Exception) {
                _events.emit(ChatEvent.Failed("Could not listen: ${e.message}"))
            }
        }
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (scope.isActive) {
            val client = try {
                withContext(Dispatchers.IO) { socket.accept() }
            } catch (_: Exception) {
                break
            }
            PeerConnection(client, this).start()
        }
    }

    /** Port of `ConnectTo`. Failures surface as [ChatEvent.Failed]. */
    fun connectTo(host: String, port: Int) {
        scope.launch {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                PeerConnection(socket, this@ChatServer).start()
            } catch (e: Exception) {
                _events.emit(ChatEvent.Failed("Could not connect to $host:$port — ${e.message}"))
            }
        }
    }

    internal fun registerPeer(peer: PeerConnection) {
        peers[peer.peerId] = peer
        scope.launch { _events.emit(ChatEvent.PeerConnected(peer.peerId, peer.remoteUsername)) }
    }

    internal fun unregisterPeer(peer: PeerConnection) {
        peers.remove(peer.peerId)
        scope.launch { _events.emit(ChatEvent.PeerDisconnected(peer.peerId, peer.remoteUsername)) }
    }

    internal fun raiseMessage(peer: PeerConnection, text: String) {
        scope.launch { _events.emit(ChatEvent.Message(peer.peerId, peer.remoteUsername, text)) }
    }

    internal fun launchIo(block: suspend () -> Unit) = scope.launch { block() }

    fun broadcast(text: String) {
        peers.values.forEach { it.send(text) }
    }

    fun sendTo(peerId: String, text: String) {
        peers[peerId]?.send(text)
    }

    fun dispose() {
        runCatching { listener?.close() }
        listener = null
        peers.values.forEach { it.dispose() }
        peers.clear()
        scope.cancel()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
    }
}

private val BOM = Char(0xFEFF).toString()

/**
 * Cleans up a received protocol line.
 *
 * The desktop client writes with .NET's `Encoding.UTF8`, which emits a
 * byte-order mark ahead of its very first write — so the handshake arrives as
 * "﻿HELLO:name" and a naive startsWith check rejects it. It also writes
 * Windows line endings, though BufferedReader already handles those.
 */
internal fun normaliseLine(raw: String): String = raw.removePrefix(BOM).trimEnd('\r')

/** Port of `PeerConnection`. */
internal class PeerConnection(
    private val socket: Socket,
    private val server: ChatServer,
) {
    val peerId: String = UUID.randomUUID().toString()

    @Volatile
    var remoteUsername: String = "Unknown"
        private set

    @Volatile
    private var registered = false

    private var writer: BufferedWriter? = null

    fun start() {
        server.launchIo {
            try {
                val out = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                writer = out
                out.write("HELLO:${server.localUsername}\n")
                out.flush()
                readLoop(socket.getInputStream().bufferedReader(Charsets.UTF_8))
            } catch (_: Exception) {
                // Fall through to cleanup below.
            } finally {
                // Only announce a disconnect for a peer that was announced as
                // connected, or a failed dial would post a phantom departure.
                if (registered) server.unregisterPeer(this)
                runCatching { socket.close() }
            }
        }
    }

    private fun readLoop(reader: BufferedReader) {
        while (true) {
            val line = normaliseLine(reader.readLine() ?: break)
            when {
                line.startsWith("HELLO:") -> {
                    remoteUsername = line.substring(6)
                    register()
                }
                line.startsWith("MSG:") -> {
                    // A peer whose handshake never arrived still gets a window,
                    // rather than having its messages silently discarded because
                    // there was nowhere to put them.
                    register()
                    server.raiseMessage(this, line.substring(4))
                }
            }
        }
    }

    private fun register() {
        if (registered) return
        registered = true
        server.registerPeer(this)
    }

    fun send(text: String) {
        server.launchIo {
            runCatching {
                writer?.apply {
                    // The protocol is line-delimited, so a pasted newline would
                    // desynchronise the peer's reader.
                    write("MSG:" + text.replace("\n", " ") + "\n")
                    flush()
                }
            }
        }
    }

    fun dispose() {
        runCatching { writer?.close() }
        runCatching { socket.close() }
    }
}
