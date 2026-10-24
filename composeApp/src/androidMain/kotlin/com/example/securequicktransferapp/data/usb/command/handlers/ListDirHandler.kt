package com.example.securequicktransferapp.data.usb.command.handlers

import android.content.Context
import com.example.secureqt.sdk.protocol.CommandPayloadParser
import com.example.securequicktransferapp.data.logging.UsbLogger
import com.example.securequicktransferapp.data.usb.command.CommandContext
import com.example.securequicktransferapp.data.usb.command.ICommandHandler
import com.example.securequicktransferapp.data.usb.storage.AndroidFilePathResolver
import com.example.securequicktransferapp.data.usb.storage.SmartNavStorageResolver
import com.example.securequicktransferapp.data.usb.storage.UsbDataSource
import com.example.securequicktransferapp.domain.command.AppCommandIds
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ListDirHandler"

/**
 * Handles [AppCommandIds.CMD_LIST_DIR]: enumerates directory contents on the Android
 * device and streams each entry back to the Desktop host.
 *
 * Wire format received: `[4: pathLen][pathLen bytes: path]`
 * Wire format sent:     `[4: count]` then for each entry: `[1: isDir][8: size][4: nameLen][name bytes]`
 */
@Singleton
class ListDirHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dataSource: UsbDataSource,
    private val usbLogger: UsbLogger
) : ICommandHandler {

    override val commandId: Byte = AppCommandIds.CMD_LIST_DIR

    override suspend fun handle(payload: ByteBuffer, cmdCtx: CommandContext): Boolean {
        val path = CommandPayloadParser.readString(payload)

        usbLogger.d(TAG, "Requested path = '$path'")

        val items = if (SmartNavStorageResolver.isSmartNavPath(path)) {
            val providerItems = SmartNavStorageResolver.listDirectory(context, path)
            if (providerItems.isNotEmpty()) {
                usbLogger.d(TAG, "SmartNav provider returned ${providerItems.size} items")
                providerItems
            } else {
                usbLogger.d(TAG, "SmartNav provider empty — falling back to direct filesystem")
                listDirectFilesystem(AndroidFilePathResolver.resolve(path))
            }
        } else {
            listDirectFilesystem(AndroidFilePathResolver.resolve(path))
        }

        // Send item count first
        dataSource.sendSecure(ByteBuffer.allocate(4).putInt(items.size).array())
        delay(50) // brief yield to let the count packet land before items stream begins

        usbLogger.i(TAG, "Sending ${items.size} items for '$path'")
        for ((isDir, size, nameBytes) in items) {
            val itemPacket = ByteBuffer.allocate(1 + 8 + 4 + nameBytes.size)
                .put(if (isDir) 1.toByte() else 0.toByte())
                .putLong(size)
                .putInt(nameBytes.size)
                .put(nameBytes)
            dataSource.sendSecure(itemPacket.array())
        }
        return true
    }

    private fun listDirectFilesystem(dir: File): ArrayList<Triple<Boolean, Long, ByteArray>> {
        usbLogger.d(TAG, "Listing filesystem at '${dir.absolutePath}' (exists=${dir.exists()}, isDir=${dir.isDirectory})")
        val names: Array<String> = try {
            if (dir.exists() && dir.isDirectory) dir.list() ?: emptyArray() else emptyArray()
        } catch (e: Exception) {
            usbLogger.e(TAG, "list() threw exception", e)
            emptyArray()
        }

        val items = ArrayList<Triple<Boolean, Long, ByteArray>>(names.size)
        for (name in names) {
            if (name.startsWith(".")) continue
            val child = File(dir, name)
            val isDir = try { child.isDirectory } catch (_: Exception) { false }
            val size = if (!isDir) try { child.length() } catch (_: Exception) { 0L } else 0L
            items.add(Triple(isDir, size, name.toByteArray(Charsets.UTF_8)))
        }

        // Android 11+ Scoped Storage Navigation Patch
        val pathStr = dir.absolutePath.trimEnd('/')
        val namesList = names.toList()
        when {
            pathStr == "/storage/emulated/0/Android" && "data" !in namesList ->
                items.add(Triple(true, 0L, "data".toByteArray(Charsets.UTF_8)))
            pathStr == "/storage/emulated/0/Android/data" && "com.example.smartnav_v3" !in namesList ->
                items.add(Triple(true, 0L, "com.example.smartnav_v3".toByteArray(Charsets.UTF_8)))
            pathStr == "/storage/emulated/0/Android/data/com.example.smartnav_v3" && "files" !in namesList ->
                items.add(Triple(true, 0L, "files".toByteArray(Charsets.UTF_8)))
            pathStr == "/storage/emulated/0/Android/data/com.example.smartnav_v3/files" && "SmartNavV3" !in namesList ->
                items.add(Triple(true, 0L, "SmartNavV3".toByteArray(Charsets.UTF_8)))
        }

        items.sortWith(compareBy<Triple<Boolean, Long, ByteArray>> { !it.first }
            .thenBy { String(it.third, Charsets.UTF_8).lowercase() })
        return items
    }
}
