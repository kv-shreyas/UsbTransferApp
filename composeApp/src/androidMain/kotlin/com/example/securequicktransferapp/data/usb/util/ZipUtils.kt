package com.example.securequicktransferapp.data.usb.util

import android.content.Context
import android.media.MediaScannerConnection
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Shared ZIP utility functions used by multiple USB command handlers.
 *
 * Shared across [FetchDirHandler] and [ReceiveDirHandler] (DRY / Single Responsibility).
 */
object ZipUtils {

    /**
     * Zips the entire contents of [sourceDir] recursively into [outputZipFile].
     * Directory entries are named with a trailing `/` per ZIP spec.
     * The [outputZipFile] itself is excluded from the archive if it falls inside [sourceDir].
     */
    fun zipDirectory(sourceDir: File, outputZipFile: File) {
        ZipOutputStream(outputZipFile.outputStream().buffered()).use { zos ->
            sourceDir.walkTopDown().forEach { file ->
                if (file.absolutePath == outputZipFile.absolutePath) return@forEach
                val entryName = file.absolutePath.removePrefix(sourceDir.absolutePath).removePrefix("/")
                if (entryName.isEmpty()) return@forEach
                zos.putNextEntry(ZipEntry(if (file.isDirectory) "$entryName/" else entryName))
                if (file.isFile) file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }

    /**
     * Extracts [zipFile] into [targetDirectory], creating subdirectories as needed.
     * Triggers [MediaScannerConnection] for each extracted file so they appear
     * immediately in the user's gallery and file manager.
     */
    fun unzipFile(zipFile: File, targetDirectory: File, context: Context) {
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val destFile = File(targetDirectory, entry.name)
                if (entry.isDirectory) {
                    destFile.mkdirs()
                } else {
                    destFile.parentFile?.mkdirs()
                    destFile.outputStream().use { zis.copyTo(it) }
                    MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), null, null)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
