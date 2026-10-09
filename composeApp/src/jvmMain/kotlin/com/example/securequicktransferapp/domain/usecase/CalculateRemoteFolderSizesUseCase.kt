package com.example.securequicktransferapp.domain.usecase

import com.example.securequicktransferapp.data.usb.UsbSession
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * UseCase for recursively calculating the sizes of remote folders.
 * Follows the Single Responsibility Principle by extracting business logic out of the ViewModel.
 */
class CalculateRemoteFolderSizesUseCase {
    suspend operator fun invoke(session: UsbSession, rootPath: String): Map<String, Long> {
        // Fast-fail if a transfer is actively locking the USB channel
        if (session.transferQueue.isProcessing.value) {
            return mapOf("ROOT" to -1L) // Indicates busy
        }

        val result = ConcurrentHashMap<String, Long>()
        var totalSize = 0L

        suspend fun traverse(path: String, isRoot: Boolean): Long = kotlinx.coroutines.coroutineScope {
            var size = 0L
            try {
                val items = session.repository.listDirectory(path)
                val dirs = items.filter { it.isDirectory }
                val files = items.filter { !it.isDirectory }
                size += files.sumOf { it.size }

                val deferreds = dirs.map { dir ->
                    async(kotlinx.coroutines.Dispatchers.IO) {
                        val subSize = traverse(dir.path, false)
                        if (isRoot) {
                            result[dir.name] = subSize
                        }
                        subSize
                    }
                }
                size += kotlinx.coroutines.awaitAll(*deferreds.toTypedArray()).sum()
            } catch (e: Exception) {
                // Log or handle error
            }
            size
        }

        session.sessionMutex.withLock {
            totalSize = traverse(rootPath, true)
        }
        result["ROOT"] = totalSize
        return result
    }
}
