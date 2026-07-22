package com.j0ker.sshmobile.ssh

import android.content.Context
import com.j0ker.sshmobile.data.ConnectionProfile
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
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.InputStream
import java.io.OutputStream

/** What the terminal UI renders. Mirrors the colour coding of `AppendOutput`. */
sealed interface TerminalEvent {
    data class Output(val text: String) : TerminalEvent
    data class System(val text: String) : TerminalEvent
    data class Error(val text: String) : TerminalEvent
}

enum class SshState { Disconnected, Connecting, Connected, Failed }

/**
 * One interactive shell over one SSH connection.
 *
 * Replaces `Controls/TerminalPanel.cs`: SSH.NET's `ShellStream` and its
 * `DataReceived` event become an sshj `Session.Shell` drained on
 * [Dispatchers.IO] and published as a [SharedFlow], which the UI collects on
 * the main thread — the coroutine equivalent of the desktop's `BeginInvoke`.
 */
class SshSession(
    private val context: Context,
    val profile: ConnectionProfile,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var client: SSHClient? = null
    private var session: Session? = null
    private var shell: Session.Shell? = null
    private var stdin: OutputStream? = null

    private val _events = MutableSharedFlow<TerminalEvent>(
        replay = 0,
        extraBufferCapacity = 256,
    )
    val events: SharedFlow<TerminalEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow(SshState.Disconnected)
    val state: StateFlow<SshState> = _state.asStateFlow()

    /** Drives the first-use confirmation dialog; see [HostKeyPrompter]. */
    val hostKeyPrompter = HostKeyPrompter()

    private val verifier = TofuHostKeyVerifier(context, hostKeyPrompter::ask)

    /** Fingerprint mismatch from the last failed connect, if that is why it failed. */
    val hostKeyMismatch: TofuHostKeyVerifier.Mismatch? get() = verifier.lastMismatch

    fun connect(columns: Int = 100, rows: Int = 40) {
        if (_state.value == SshState.Connecting || _state.value == SshState.Connected) return
        _state.value = SshState.Connecting

        scope.launch {
            try {
                val ssh = SSHClient().apply {
                    addHostKeyVerifier(verifier)
                    connectTimeout = CONNECT_TIMEOUT_MS
                    timeout = SOCKET_TIMEOUT_MS
                    connect(profile.host, profile.port)
                }
                client = ssh

                authenticate(ssh)

                val sess = ssh.startSession().apply {
                    allocatePTY("xterm", columns, rows, 0, 0, emptyMap())
                }
                session = sess

                val sh = sess.startShell()
                shell = sh
                stdin = sh.outputStream

                _state.value = SshState.Connected
                _events.emit(TerminalEvent.System("[Connected to ${profile.host}:${profile.port}]"))

                // Two pumps, matching ShellStream's DataReceived / ErrorOccurred pair.
                launch { pump(sh.inputStream) { TerminalEvent.Output(it) } }
                launch { pump(sh.errorStream) { TerminalEvent.Error(it) } }
            } catch (e: Exception) {
                _state.value = SshState.Failed
                val mismatch = verifier.lastMismatch
                if (mismatch != null) {
                    _events.emit(
                        TerminalEvent.Error(
                            "[Host key for ${mismatch.hostname} changed]\n" +
                                "  known:  ${mismatch.expected}\n" +
                                "  actual: ${mismatch.actual}\n" +
                                "[Refusing to connect. Forget the saved key to accept it.]",
                        ),
                    )
                } else if (hostKeyPrompter.declined) {
                    _events.emit(TerminalEvent.Error("[Host key not accepted. Not connecting.]"))
                } else {
                    _events.emit(TerminalEvent.Error("[Connection failed: ${e.message}]"))
                }
                closeQuietly()
            }
        }
    }

    private fun authenticate(ssh: SSHClient) {
        if (profile.useKeyAuth) {
            ssh.auth(profile.username, net.schmizz.sshj.userauth.method.AuthPublickey(loadKey(ssh)))
        } else {
            ssh.authPassword(profile.username, profile.password.toCharArray())
        }
    }

    /**
     * The desktop read a key off disk by path; Android hands us a SAF content
     * URI, so the key is read through the resolver and parsed from memory.
     */
    private fun loadKey(ssh: SSHClient): KeyProvider {
        val pem = context.contentResolver.openInputStream(android.net.Uri.parse(profile.privateKeyUri))
            ?.bufferedReader()?.use { it.readText() }
            ?: error("Could not read private key")

        return if (profile.privateKeyPassphrase.isNotEmpty()) {
            ssh.loadKeys(pem, null, PasswordUtils.createOneOff(profile.privateKeyPassphrase.toCharArray()))
        } else {
            ssh.loadKeys(pem, null, null)
        }
    }

    private suspend fun pump(stream: InputStream, wrap: (String) -> TerminalEvent) {
        val buffer = ByteArray(8192)
        try {
            while (scope.isActive) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                val text = stripAnsi(String(buffer, 0, read, Charsets.UTF_8))
                if (text.isNotEmpty()) _events.emit(wrap(text))
            }
        } catch (_: Exception) {
            // Stream closed underneath us — disconnect() reports the reason.
        }
        if (_state.value == SshState.Connected) {
            _state.value = SshState.Disconnected
            _events.emit(TerminalEvent.System("[Disconnected]"))
        }
    }

    /** Port of `SendCommand`: the desktop appended "\n" and flushed. */
    fun send(line: String) {
        scope.launch { writeRaw(line + "\n") }
    }

    /** For control keys (Ctrl-C, Tab, arrows) that a soft keyboard cannot send. */
    fun sendRaw(text: String) {
        scope.launch { writeRaw(text) }
    }

    private suspend fun writeRaw(text: String) = withContext(Dispatchers.IO) {
        try {
            stdin?.apply {
                write(text.toByteArray(Charsets.UTF_8))
                flush()
            }
        } catch (e: Exception) {
            _events.emit(TerminalEvent.Error("[Write failed: ${e.message}]"))
        }
    }

    /** Keeps the remote PTY in step with the phone's screen after a rotation. */
    fun resize(columns: Int, rows: Int) {
        scope.launch {
            runCatching { shell?.changeWindowDimensions(columns, rows, 0, 0) }
        }
    }

    fun disconnect() {
        scope.launch {
            closeQuietly()
            if (_state.value != SshState.Failed) _state.value = SshState.Disconnected
        }
    }

    private fun closeQuietly() {
        // Releases the transport thread if it is parked on the host key dialog.
        hostKeyPrompter.cancel()
        runCatching { shell?.close() }
        runCatching { session?.close() }
        runCatching { client?.disconnect() }
        shell = null
        session = null
        stdin = null
        client = null
    }

    fun dispose() {
        closeQuietly()
        scope.cancel()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val SOCKET_TIMEOUT_MS = 0 // an idle shell must not time out
    }
}
