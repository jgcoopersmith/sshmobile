package com.j0ker.sshmobile.ssh

import android.content.Context
import android.net.Uri
import com.j0ker.sshmobile.data.ConnectionProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.password.PasswordUtils
import net.schmizz.sshj.xfer.InMemorySourceFile
import java.io.InputStream

/** One row of the remote listing; the desktop's `ListViewItem` + `ISftpFile`. */
data class RemoteFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long,
)

/**
 * Port of `Forms/SftpBrowserForm.cs`'s data half. Every call suspends on
 * [Dispatchers.IO]; the desktop ran these inline on the UI thread, which is a
 * hard error on Android.
 */
class SftpSession(
    private val context: Context,
    private val profile: ConnectionProfile,
    private val onUnknownHostKey: (fingerprint: String) -> Boolean = { true },
) {

    private var client: SSHClient? = null
    private var sftp: SFTPClient? = null

    suspend fun connect() = withContext(Dispatchers.IO) {
        val ssh = SSHClient().apply {
            addHostKeyVerifier(TofuHostKeyVerifier(context, onUnknownHostKey))
            connectTimeout = 15_000
            connect(profile.host, profile.port)
        }
        if (profile.useKeyAuth) {
            ssh.auth(profile.username, net.schmizz.sshj.userauth.method.AuthPublickey(loadKey(ssh)))
        } else {
            ssh.authPassword(profile.username, profile.password.toCharArray())
        }
        client = ssh
        sftp = ssh.newSFTPClient()
    }

    private fun loadKey(ssh: SSHClient): KeyProvider {
        val pem = context.contentResolver.openInputStream(Uri.parse(profile.privateKeyUri))
            ?.bufferedReader()?.use { it.readText() }
            ?: error("Could not read private key")
        return if (profile.privateKeyPassphrase.isNotEmpty()) {
            ssh.loadKeys(pem, null, PasswordUtils.createOneOff(profile.privateKeyPassphrase.toCharArray()))
        } else {
            ssh.loadKeys(pem, null, null)
        }
    }

    /** Directories first, then case-insensitive by name — the desktop's sort. */
    suspend fun list(path: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        val client = sftp ?: error("Not connected")
        client.ls(path)
            .asSequence()
            .filter { it.name != "." && it.name != ".." }
            .map {
                RemoteFile(
                    name = it.name,
                    path = it.path,
                    isDirectory = it.attributes.type == FileMode.Type.DIRECTORY,
                    size = it.attributes.size,
                    modified = it.attributes.mtime * 1000L,
                )
            }
            .sortedWith(compareByDescending<RemoteFile> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .toList()
    }

    /** Downloads into a SAF destination rather than the desktop's folder picker. */
    suspend fun download(remote: RemoteFile, destination: Uri) = withContext(Dispatchers.IO) {
        val client = sftp ?: error("Not connected")
        context.contentResolver.openOutputStream(destination)?.use { out ->
            client.open(remote.path).use { handle ->
                handle.RemoteFileInputStream().use { input -> input.copyTo(out) }
            }
        } ?: error("Could not open destination")
    }

    suspend fun upload(source: Uri, name: String, remoteDir: String) = withContext(Dispatchers.IO) {
        val client = sftp ?: error("Not connected")
        val bytes = context.contentResolver.openInputStream(source)?.use(InputStream::readBytes)
            ?: error("Could not read source")
        val target = remoteDir.trimEnd('/') + "/" + name
        client.put(
            object : InMemorySourceFile() {
                override fun getName() = name
                override fun getLength() = bytes.size.toLong()
                override fun getInputStream(): InputStream = bytes.inputStream()
            },
            target,
        )
    }

    suspend fun delete(file: RemoteFile) = withContext(Dispatchers.IO) {
        val client = sftp ?: error("Not connected")
        if (file.isDirectory) client.rmdir(file.path) else client.rm(file.path)
    }

    suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        (sftp ?: error("Not connected")).mkdir(path)
    }

    suspend fun rename(from: String, to: String) = withContext(Dispatchers.IO) {
        (sftp ?: error("Not connected")).rename(from, to)
    }

    fun disconnect() {
        runCatching { sftp?.close() }
        runCatching { client?.disconnect() }
        sftp = null
        client = null
    }

    companion object {
        /** Port of the desktop's `FormatSize`. */
        fun formatSize(bytes: Long): String = when {
            bytes >= 1_073_741_824 -> String.format("%.1f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }

        /** Port of `NavigateUp`. */
        fun parentOf(path: String): String {
            val trimmed = path.trimEnd('/')
            if (trimmed.isEmpty()) return "/"
            val cut = trimmed.lastIndexOf('/')
            return if (cut <= 0) "/" else trimmed.substring(0, cut)
        }
    }
}
