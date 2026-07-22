package com.j0ker.sshmobile.ssh

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.PublicKey

/** An unknown host awaiting the user's decision. */
data class HostKeyPrompt(
    val hostname: String,
    val port: Int,
    val fingerprint: String,
) {
    val target: String get() = "$hostname:$port"
}

/**
 * Bridges sshj's synchronous host key check to an asynchronous dialog.
 *
 * sshj calls [HostKeyVerifier.verify] on its transport thread and needs a
 * boolean back before the handshake can continue, so [ask] parks that thread
 * until the UI answers — the same thing OpenSSH does when it prints
 * "Are you sure you want to continue connecting?".
 */
class HostKeyPrompter {

    private val _pending = MutableStateFlow<HostKeyPrompt?>(null)
    val pending: StateFlow<HostKeyPrompt?> = _pending.asStateFlow()

    private var answer: CompletableDeferred<Boolean>? = null

    /** True when the last prompt was refused, so the caller can say why it failed. */
    @Volatile
    var declined: Boolean = false
        private set

    /**
     * Blocks the calling thread until the user answers, or until
     * [PROMPT_TIMEOUT_MS] elapses. A timeout rejects: an unattended prompt must
     * not silently become trust.
     */
    fun ask(hostname: String, port: Int, fingerprint: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        answer = deferred
        declined = false
        _pending.value = HostKeyPrompt(hostname, port, fingerprint)
        val accepted = try {
            runBlocking { withTimeoutOrNull(PROMPT_TIMEOUT_MS) { deferred.await() } } ?: false
        } finally {
            _pending.value = null
            answer = null
        }
        declined = !accepted
        return accepted
    }

    /** Called from the dialog. */
    fun respond(accept: Boolean) {
        answer?.complete(accept)
    }

    /** Unblocks a waiting connect when the session is torn down mid-prompt. */
    fun cancel() {
        answer?.complete(false)
    }

    private companion object {
        const val PROMPT_TIMEOUT_MS = 120_000L
    }
}

/**
 * Trust-on-first-use host key store.
 *
 * The desktop client accepted any host key silently and never pinned one. Here
 * the first fingerprint is shown to the user and only pinned if they accept;
 * afterwards a mismatch aborts the connection, since that is what a
 * machine-in-the-middle looks like.
 */
class TofuHostKeyVerifier(
    context: Context,
    private val onUnknownHost: (hostname: String, port: Int, fingerprint: String) -> Boolean,
) : HostKeyVerifier {

    private val prefs = context.getSharedPreferences("sshmobile_hostkeys", Context.MODE_PRIVATE)

    /** Non-null after a failed verify, so the UI can explain what changed. */
    @Volatile
    var lastMismatch: Mismatch? = null
        private set

    data class Mismatch(val hostname: String, val expected: String, val actual: String)

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val id = "$hostname:$port"
        val fingerprint = SecurityUtils.getFingerprint(key)
        val known = prefs.getString(id, null)

        if (known == null) {
            if (!onUnknownHost(hostname, port, fingerprint)) return false
            prefs.edit().putString(id, fingerprint).apply()
            lastMismatch = null
            return true
        }

        if (known == fingerprint) {
            lastMismatch = null
            return true
        }

        lastMismatch = Mismatch(id, known, fingerprint)
        return false
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()

    /** Drops the pin so the next connect re-prompts. */
    fun forget(hostname: String, port: Int) {
        prefs.edit().remove("$hostname:$port").apply()
    }
}
