package com.example.securequicktransferapp.domain.model.deviceinfo

import java.nio.ByteBuffer

/**
 * Protocol codec for serializing and deserializing [ClientAppDetails] over the USB transport layer.
 *
 * Wire specification:
 * - [4 bytes: appNameLen]    [appNameLen bytes: UTF-8]
 * - [4 bytes: pkgNameLen]    [pkgNameLen bytes: UTF-8]
 * - [4 bytes: appVersionLen] [appVersionLen bytes: UTF-8]
 * - [4 bytes: osVersionLen]  [osVersionLen bytes: UTF-8]
 */
object ClientAppDetailsCodec {

    fun encode(details: ClientAppDetails): ByteArray {
        val appNameBytes = details.appName.toByteArray(Charsets.UTF_8)
        val pkgNameBytes = details.packageName.toByteArray(Charsets.UTF_8)
        val appVersionBytes = details.appVersion.toByteArray(Charsets.UTF_8)
        val osVersionBytes = details.osVersion.toByteArray(Charsets.UTF_8)

        val totalSize = 4 + appNameBytes.size +
                4 + pkgNameBytes.size +
                4 + appVersionBytes.size +
                4 + osVersionBytes.size

        val buffer = ByteBuffer.allocate(totalSize)
        putString(buffer, appNameBytes)
        putString(buffer, pkgNameBytes)
        putString(buffer, appVersionBytes)
        putString(buffer, osVersionBytes)

        return buffer.array()
    }

    fun decode(bytes: ByteArray): ClientAppDetails {
        require(bytes.isNotEmpty()) { "Cannot decode empty byte array into ClientAppDetails" }
        val buffer = ByteBuffer.wrap(bytes)

        val appName = readString(buffer)
        val packageName = readString(buffer)
        val appVersion = readString(buffer)
        val osVersion = readString(buffer)

        return ClientAppDetails(
            appName = appName,
            packageName = packageName,
            appVersion = appVersion,
            osVersion = osVersion
        )
    }

    private fun putString(buffer: ByteBuffer, bytes: ByteArray) {
        buffer.putInt(bytes.size)
        if (bytes.isNotEmpty()) {
            buffer.put(bytes)
        }
    }

    private fun readString(buffer: ByteBuffer): String {
        val len = buffer.int
        if (len <= 0) return ""
        val bytes = ByteArray(len)
        buffer.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
