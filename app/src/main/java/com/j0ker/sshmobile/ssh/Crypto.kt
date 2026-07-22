package com.j0ker.sshmobile.ssh

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Replaces Android's cut-down BouncyCastle with the full one.
 *
 * Android preloads its own stripped BouncyCastle registered under the name
 * "BC". It omits X25519 and Ed25519, so sshj — which asks for algorithms from
 * the "BC" provider by name — fails the handshake with "no such algorithm:
 * X25519 for provider BC" against any server offering curve25519-sha256, which
 * is every current OpenSSH.
 *
 * Bundling bcprov is not enough on its own: the platform's registration wins
 * unless it is removed first. This must run before any SSH connection, so it is
 * called from [com.j0ker.sshmobile.SshMobileApp].
 *
 * The app's own secrets are unaffected — [com.j0ker.sshmobile.data.SecretBox]
 * uses the separate "AndroidKeyStore" provider.
 */
fun installFullBouncyCastle() {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) is BouncyCastleProvider) return
    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
    Security.insertProviderAt(BouncyCastleProvider(), 1)
}
