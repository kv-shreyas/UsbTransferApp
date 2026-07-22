package com.example.securequicktransferapp.data.usb

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream

private const val TAG = "SmartNavStorageResolver"
private const val SMARTNAV_AUTHORITY = "com.example.smartnav_v3.storageprovider"
private const val SMARTNAV_CONTENT_URI_BASE = "content://$SMARTNAV_AUTHORITY"

/**
 * Resolver helper that bridges USBCommandProcessor requests to Smartnav_V3's exported ContentProvider
 * (`SmartNavStorageProvider`). This enables full reading, writing, directory listing, zipping/unzipping,
 * renaming, and deleting of Smartnav_V3 scoped storage folders from USBDataTransfer app.
 */
object SmartNavStorageResolver {

    fun isSmartNavPath(path: String): Boolean {
        val clean = path.trim().removePrefix("/sdcard/").removePrefix("/storage/emulated/0/").trimStart('/')
        return clean == "SmartNavV3" ||
               clean.startsWith("SmartNavV3/") ||
               clean.startsWith("Android/data/com.example.smartnav_v3") ||
               clean.startsWith("content://$SMARTNAV_AUTHORITY") ||
               clean.startsWith("smartnav://") ||
               clean == "updateApp" ||
               clean.startsWith("updateApp/")
    }

    fun getUriForPath(path: String): Uri {
        if (path.startsWith("content://$SMARTNAV_AUTHORITY")) {
            return Uri.parse(path)
        }
        val clean = path.trim()
            .removePrefix("smartnav://")
            .removePrefix("/sdcard/")
            .removePrefix("/storage/emulated/0/")
            .trimStart('/')
        return Uri.parse("$SMARTNAV_CONTENT_URI_BASE/$clean")
    }

    fun getFileInfo(context: Context, path: String): FileInfo {
        try {
            val uri = Uri.parse("content://$SMARTNAV_AUTHORITY")
            val bundle = context.contentResolver.call(uri, "get_file_info", path, null)
            if (bundle != null) {
                return FileInfo(
                    exists = bundle.getBoolean("exists", false),
                    isDirectory = bundle.getBoolean("is_dir", false),
                    size = bundle.getLong("size", 0L),
                    absolutePath = bundle.getString("absolute_path") ?: path
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "getFileInfo via ContentProvider failed for '$path': ${e.message}")
        }
        return FileInfo(false, false, 0L, path)
    }

    data class FileInfo(
        val exists: Boolean,
        val isDirectory: Boolean,
        val size: Long,
        val absolutePath: String
    )

    fun listDirectory(context: Context, path: String): List<Triple<Boolean, Long, ByteArray>> {
        val uri = getUriForPath(path)
        Log.d(TAG, "listDirectory: Querying SmartNavProvider URI '$uri' for path '$path'")
        val items = ArrayList<Triple<Boolean, Long, ByteArray>>()
        try {
            val cursor = context.contentResolver.query(uri, null, path, null, null)
            if (cursor != null) {
                cursor.use { c ->
                    val nameIdx = c.getColumnIndex("name")
                    val isDirIdx = c.getColumnIndex("is_dir")
                    val sizeIdx = c.getColumnIndex("size")
                    while (c.moveToNext()) {
                        val name = if (nameIdx >= 0) c.getString(nameIdx) else continue
                        val isDir = if (isDirIdx >= 0) c.getInt(isDirIdx) == 1 else false
                        val size = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L
                        items.add(Triple(isDir, size, name.toByteArray(Charsets.UTF_8)))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "listDirectory: Exception querying SmartNavProvider for '$path'", e)
        }
        items.sortWith(compareBy<Triple<Boolean, Long, ByteArray>> { !it.first }.thenBy { String(it.third, Charsets.UTF_8).lowercase() })
        return items
    }

    fun openInputStream(context: Context, path: String): InputStream? {
        val uri = getUriForPath(path)
        Log.d(TAG, "openInputStream: Opening '$uri' for reading '$path'")
        return try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            Log.e(TAG, "openInputStream: Failed to open InputStream via provider for '$path'", e)
            null
        }
    }

    fun openOutputStream(context: Context, path: String): OutputStream? {
        val uri = getUriForPath(path)
        Log.d(TAG, "openOutputStream: Opening '$uri' for writing '$path'")
        return try {
            context.contentResolver.openOutputStream(uri, "wt")
        } catch (e: Exception) {
            Log.e(TAG, "openOutputStream: Failed to open OutputStream via provider for '$path'", e)
            null
        }
    }

    fun delete(context: Context, path: String): Boolean {
        val uri = getUriForPath(path)
        Log.d(TAG, "delete: Requesting delete on '$uri'")
        return try {
            context.contentResolver.delete(uri, path, null) > 0
        } catch (e: Exception) {
            Log.e(TAG, "delete: Error deleting '$path' via provider", e)
            false
        }
    }

    fun rename(context: Context, oldPath: String, newName: String): Boolean {
        val uri = getUriForPath(oldPath)
        Log.d(TAG, "rename: Requesting rename on '$uri' to '$newName'")
        return try {
            val values = ContentValues().apply { put("new_name", newName) }
            context.contentResolver.update(uri, values, oldPath, null) > 0
        } catch (e: Exception) {
            Log.e(TAG, "rename: Error renaming '$oldPath' via provider", e)
            false
        }
    }
    fun createDirectory(context: Context, path: String): Boolean {
        val uri = getUriForPath(path)
        Log.d(TAG, "createDirectory: Requesting create directory on '$uri'")
        return try {
            val values = ContentValues().apply { put("is_directory", true) }
            context.contentResolver.insert(uri, values) != null
        } catch (e: Exception) {
            Log.e(TAG, "createDirectory: Error creating directory '$path' via provider", e)
            false
        }
    }

    fun zipDirectory(context: Context, path: String): Pair<Long, String?> {
        Log.d(TAG, "zipDirectory: Requesting remote zip for '$path'")
        return try {
            val uri = Uri.parse("content://$SMARTNAV_AUTHORITY")
            val bundle = context.contentResolver.call(uri, "zip_directory", path, null)
            val size = bundle?.getLong("size", 0L) ?: 0L
            val zipPath = bundle?.getString("zip_path")
            Pair(size, zipPath)
        } catch (e: Exception) {
            Log.e(TAG, "zipDirectory: Error calling zip_directory for '$path'", e)
            Pair(0L, null)
        }
    }

    fun unzipDirectory(context: Context, zipFile: File, targetPath: String): Boolean {
        Log.d(TAG, "unzipDirectory: Requesting remote unzip of '${zipFile.absolutePath}' to '$targetPath'")
        return try {
            val uri = Uri.parse("content://$SMARTNAV_AUTHORITY")
            val bundle = context.contentResolver.call(
                uri,
                "unzip_directory",
                targetPath,
                Bundle().apply { putString("zip_path", zipFile.absolutePath) }
            )
            bundle?.getBoolean("success", false) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "unzipDirectory: Error calling unzip_directory for '$targetPath'", e)
            false
        }
    }

    fun cleanupTempFile(context: Context, tempPath: String?) {
        if (tempPath == null) return
        try {
            val uri = Uri.parse("content://$SMARTNAV_AUTHORITY")
            context.contentResolver.call(uri, "cleanup_temp_file", tempPath, null)
        } catch (e: Exception) {
            Log.w(TAG, "cleanupTempFile: Error cleaning up '$tempPath': ${e.message}")
        }
    }
}
