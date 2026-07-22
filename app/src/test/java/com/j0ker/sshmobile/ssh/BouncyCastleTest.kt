package com.j0ker.sshmobile.ssh

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import javax.crypto.KeyAgreement

/**
 * Guards the bcprov dependency. Android's own "BC" provider omits these, which
 * is what breaks the handshake; if a future dependency bump ever drops them
 * from the bundled provider too, this fails at build time rather than on a
 * user's first connection.
 */
class BouncyCastleTest {

    private val provider = BouncyCastleProvider()

    @Test
    fun `provider supplies X25519 for curve25519-sha256 key exchange`() {
        KeyAgreement.getInstance("X25519", provider)
        KeyPairGenerator.getInstance("X25519", provider)
    }

    @Test
    fun `provider supplies Ed25519 for modern host and user keys`() {
        Signature.getInstance("Ed25519", provider)
        KeyPairGenerator.getInstance("Ed25519", provider)
    }
}
