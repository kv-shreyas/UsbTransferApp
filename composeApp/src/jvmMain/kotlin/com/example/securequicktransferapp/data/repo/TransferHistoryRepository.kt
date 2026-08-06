package com.example.securequicktransferapp.data.repo

import com.example.securequicktransferapp.domain.model.TransferQueueItem
import com.example.securequicktransferapp.domain.model.TransferItemStatus
import com.example.securequicktransferapp.domain.model.TransferType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HistoryEntry(
    val timestamp: Long,
    val dateString: String,
    val deviceName: String,
    val fileName: String,
    val type: String, // SEND or FETCH
    val size: String,
    val status: String,
    val timeTaken: String,
    val destination: String
)

object TransferHistoryRepository {
    private val historyFile: File by lazy {
        val appDir = File(System.getProperty("user.home"), ".securequicktransfer")
        if (!appDir.exists()) appDir.mkdirs()
        File(appDir, "transfer_history.tsv")
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun addEntry(item: TransferQueueItem, deviceName: String) {
        try {
            val timestamp = System.currentTimeMillis()
            val dateStr = dateFormat.format(Date(timestamp))
            val typeStr = if (item.type == TransferType.SEND) "SEND" else "FETCH"
            val timeTaken = item.elapsed.ifBlank { "0s" }
            val statusStr = item.status.name
            
            val entryStr = "$timestamp\t$dateStr\t$deviceName\t${item.displayName}\t$typeStr\t${item.total}\t$statusStr\t$timeTaken\t${item.destinationPath}\n"
            
            historyFile.appendText(entryStr)
        } catch (e: Exception) {
            println("Error appending to history: ${e.message}")
        }
    }

    fun getHistory(): List<HistoryEntry> {
        if (!historyFile.exists()) return emptyList()
        val entries = mutableListOf<HistoryEntry>()
        try {
            historyFile.useLines { lines ->
                lines.forEach { line ->
                    val parts = line.split("\t")
                    if (parts.size >= 9) {
                        entries.add(
                            HistoryEntry(
                                timestamp = parts[0].toLongOrNull() ?: 0L,
                                dateString = parts[1],
                                deviceName = parts[2],
                                fileName = parts[3],
                                type = parts[4],
                                size = parts[5],
                                status = parts[6],
                                timeTaken = parts[7],
                                destination = parts[8]
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            println("Error reading history: ${e.message}")
        }
        return entries.sortedByDescending { it.timestamp }
    }
    
    fun clearHistory() {
        if (historyFile.exists()) {
            historyFile.delete()
        }
    }
}
