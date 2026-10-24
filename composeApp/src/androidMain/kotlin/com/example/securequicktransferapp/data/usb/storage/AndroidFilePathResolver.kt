package com.example.securequicktransferapp.data.usb.storage

import android.os.Environment
import java.io.File

/**
 * Resolves logical file path strings (as sent over the USB command protocol) to
 * concrete [java.io.File] references on the Android external storage filesystem.
 *
 * Extracted to eliminate code duplication across command handler classes (DRY / Single Responsibility).
 *
 * All handlers that operate on file paths should use this object rather than
 * duplicating the path normalization logic.
 */
object AndroidFilePathResolver {

    /**
     * Resolves a protocol path string to a [File] on external storage.
     *
     * Handles the following path formats sent by the Desktop host:
     * - `/` or empty string → external storage root
     * - `/sdcard` → external storage root
     * - `/sdcard/relative/path` → `<externalStorage>/relative/path`
     * - `/relative/path` → `<externalStorage>/relative/path`
     * - `relative/path` (no leading slash) → `<externalStorage>/relative/path`
     *
     * @param path The raw path string received in the command payload.
     * @return A [File] pointing to the resolved location on external storage.
     */
    fun resolve(path: String): File {
        val externalStorage = Environment.getExternalStorageDirectory()
        val cleanPath = path.trim()
        return when {
            cleanPath == "/" || cleanPath.isEmpty() || cleanPath == "/sdcard" ->
                externalStorage
            cleanPath.startsWith("/sdcard/") ->
                File(externalStorage, cleanPath.removePrefix("/sdcard/").trimStart('/'))
            cleanPath.startsWith("/") ->
                File(externalStorage, cleanPath.removePrefix("/").trimStart('/'))
            else ->
                File(externalStorage, cleanPath)
        }
    }
}
