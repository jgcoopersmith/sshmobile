package com.j0ker.sshmobile.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

/**
 * Wraps secrets with an AES key held in the Android keystore. The desktop
 * client stored SSH passwords as cleartext in connections.json; on a phone
 * that is not defensible, so everything sensitive goes through here.
 */
internal object SecretBox {

    private const val KEY_ALIAS = "sshmobile_secret_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    /** Returns "iv:ciphertext", both base64. Empty input stays empty. */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val bytes = cipher.doFinal(plain.toByteArray())
            b64(cipher.iv) + ":" + b64(bytes)
        }.getOrDefault("")
    }

    fun decrypt(stored: String): String {
        if (stored.isEmpty() || !stored.contains(':')) return ""
        return runCatching {
            val (ivPart, dataPart) = stored.split(':', limit = 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, unb64(ivPart)))
            String(cipher.doFinal(unb64(dataPart)))
        }.getOrDefault("")
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(text: String) = Base64.decode(text, Base64.NO_WRAP)
}

/**
 * Persistence for connections, chat peers and settings. Replaces
 * `ConnectionManager` and `PeerManager`, which wrote JSON under %APPDATA%.
 */
class Store(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("sshmobile", Context.MODE_PRIVATE)

    // ------------------------------------------------------------ connections

    fun loadConnections(): List<ConnectionProfile> {
        val raw = prefs.getString(KEY_CONNECTIONS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<ConnectionProfile>>(raw) }
            .getOrDefault(emptyList())
            .map {
                it.copy(
                    password = SecretBox.decrypt(it.password),
                    privateKeyPassphrase = SecretBox.decrypt(it.privateKeyPassphrase),
                )
            }
    }

    fun saveConnections(profiles: List<ConnectionProfile>) {
        val protectedList = profiles.map {
            it.copy(
                password = SecretBox.encrypt(it.password),
                privateKeyPassphrase = SecretBox.encrypt(it.privateKeyPassphrase),
            )
        }
        prefs.edit().putString(KEY_CONNECTIONS, json.encodeToString(protectedList)).apply()
    }

    // ------------------------------------------------------------------ peers

    fun loadPeers(): List<PeerProfile> {
        val raw = prefs.getString(KEY_PEERS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<PeerProfile>>(raw) }.getOrDefault(emptyList())
    }

    fun savePeers(peers: List<PeerProfile>) {
        prefs.edit().putString(KEY_PEERS, json.encodeToString(peers)).apply()
    }

    // --------------------------------------------------------------- settings

    fun loadSettings(): AppSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return AppSettings()
        return runCatching { json.decodeFromString<AppSettings>(raw) }
            .getOrDefault(AppSettings())
            // Earlier builds defaulted to 0, meaning "any free port", which left
            // a peer with no stable address to dial. Carry those forward.
            .let { if (it.chatListenPort <= 0) it.copy(chatListenPort = DEFAULT_CHAT_PORT) else it }
    }

    fun saveSettings(settings: AppSettings) {
        prefs.edit().putString(KEY_SETTINGS, json.encodeToString(settings)).apply()
    }

    private companion object {
        const val KEY_CONNECTIONS = "connections"
        const val KEY_PEERS = "peers"
        const val KEY_SETTINGS = "settings"
    }
}
