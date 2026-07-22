package com.j0ker.sshmobile.data

import kotlinx.serialization.Serializable
import java.util.UUID

/** Port of `Models/ConnectionProfile.cs`. */
@Serializable
data class ConnectionProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    /** Stored encrypted at rest; see [SecretBox]. */
    val password: String = "",
    /** SAF tree/document URI on mobile, where the desktop held a filesystem path. */
    val privateKeyUri: String = "",
    val privateKeyPassphrase: String = "",
    val useKeyAuth: Boolean = false,
) {
    val label: String get() = if (name.isNotEmpty()) name else "$username@$host:$port"
}

/** Port of `Models/PeerProfile.cs`. */
@Serializable
data class PeerProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 9000,
) {
    val label: String get() = if (name.isNotEmpty()) name else "$host:$port"
}

/** Mobile-only preferences; the desktop client had no settings screen. */
@Serializable
data class AppSettings(
    val localUsername: String = "android",
    val chatListenPort: Int = 0,
    val terminalFontSize: Int = 13,
    val terminalColumns: Int = 100,
    val terminalRows: Int = 40,
    val scrollbackLines: Int = 2000,
)
