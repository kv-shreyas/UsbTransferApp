package com.example.usbtransferapp.data.converter

import java.io.File
import java.io.InputStream
import java.io.PrintWriter

object AnfToCsvConverter {

    fun convert(inputStream: InputStream, outputFile: File) {
        // We use a temporary file to store the filtered NMEA lines 
        // because we need to know the max columns before writing the final CSV header
        val tempFile = File.createTempFile("nmea_filtered", ".txt")
        var maxColumns = 0

        println("[Converter] Pass 1: Filtering binary noise and determining max fields...")
        
        // Pass 1: Filter binary garbage and find max columns
        tempFile.printWriter().use { writer ->
            inputStream.bufferedReader().forEachLine { line ->
                if (line.contains("$")) {
                    // Find the start of the actual message
                    val messageStart = line.indexOf("$")
                    val cleanedLine = line.substring(messageStart).trim()
                    
                    if (cleanedLine.isNotEmpty()) {
                        val columnCount = cleanedLine.split(",").size
                        if (columnCount > maxColumns) {
                            maxColumns = columnCount
                        }
                        writer.println(cleanedLine)
                    }
                }
            }
        }

        println("[Converter] Max fields detected: $maxColumns")
        println("[Converter] Pass 2: Writing final CSV structure...")

        // Pass 2: Write final CSV with consistent columns
        PrintWriter(outputFile).use { writer ->
            // Write Header
            val header = mutableListOf("SentenceType")
            for (i in 1 until maxColumns) {
                header.add("Field_$i")
            }
            writer.println(header.joinToString(","))

            // Write Data
            tempFile.forEachLine { line ->
                val parts = line.split(",")
                val csvLine = StringBuilder()
                for (i in 0 until maxColumns) {
                    if (i < parts.size) {
                        // Clean any remaining special chars and remove checksum if it's the last part
                        val value = parts[i].replace("\r", "").replace("\n", "").trim()
                        // Optional: if it's the last part of the line, it might contain a checksum like *6C
                        // We keep it as per requirement "everything untill the /r/n"
                        csvLine.append(value)
                    }
                    if (i < maxColumns - 1) {
                        csvLine.append(",")
                    }
                }
                writer.println(csvLine.toString())
            }
        }

        tempFile.delete()
        println("[Converter] Conversion complete. Output saved to ${outputFile.name}")
    }
}
