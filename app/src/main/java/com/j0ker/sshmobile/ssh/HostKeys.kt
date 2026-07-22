package com.j0ker.sshmobile.ssh

import android.content.Context
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.PublicKey

/**
 * Trust-on-first-use host key store.
 *
 * The desktop client accepted any host key silently. sshj requires a verifier,
 * and a phone hops between untrusted networks, so we pin on first sight and
 * surface a mismatch to the caller instead.
 */
class TofuHostKeyVerifier(
    context: Context,
    private val onUnknownHost: (fingerprint: String) -> Boolean,
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
            if (!onUnknownHost(fingerprint)) return false
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
