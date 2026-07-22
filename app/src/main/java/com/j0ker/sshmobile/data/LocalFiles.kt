package com.j0ker.sshmobile.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

/**
 * The phone's own filesystem, as the local half of the SFTP browser.
 *
 * Android 11 removed unrestricted File access, so browsing from the storage
 * root needs "All files access" — the same permission a file manager asks for.
 * It is granted from a system settings screen rather than a dialog, so the UI
 * has to send the user there and re-check on return.
 */
object LocalFiles {

    fun defaultRoot(): File = Environment.getExternalStorageDirectory()

    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    /** The settings screen that grants it; there is no in-app dialog for this. */
    fun accessSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )

    /** Directories first, then case-insensitive by name — the remote side's order. */
    fun list(dir: File): List<File> =
        (dir.listFiles() ?: emptyArray()).sortedWith(
            compareByDescending<File> { it.isDirectory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )

    fun parentOf(path: String): String = File(path).parentFile?.absolutePath ?: path
}
