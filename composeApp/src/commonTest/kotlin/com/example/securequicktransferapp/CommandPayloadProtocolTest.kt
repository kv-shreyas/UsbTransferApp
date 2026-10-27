package com.example.securequicktransferapp

import com.example.secureqt.sdk.protocol.CommandPayloadBuilder
import com.example.secureqt.sdk.protocol.CommandPayloadParser
import com.example.secureqt.sdk.protocol.StandardCommandIds
import com.example.securequicktransferapp.domain.command.AppCommandIds
import com.example.securequicktransferapp.domain.command.AppCommandRegistry
import com.example.securequicktransferapp.domain.model.deviceinfo.ClientAppDetails
import com.example.securequicktransferapp.domain.model.deviceinfo.ClientAppDetailsCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandPayloadProtocolTest {

    @Test
    fun testBuildAndParseSimpleCommand() {
        val raw = CommandPayloadBuilder.buildSimple(AppCommandIds.CMD_DISCONNECT)
        val command = CommandPayloadParser.parse(raw)

        assertNotNull(command)
        assertEquals(AppCommandIds.CMD_DISCONNECT, command.commandId)
        assertEquals(0, command.payload.remaining())
    }

    @Test
    fun testBuildAndParseStringCommand() {
        val path = "/storage/emulated/0/DCIM/Camera/photo.jpg"
        val raw = CommandPayloadBuilder.buildWithString(AppCommandIds.CMD_LIST_DIR, path)
        val command = CommandPayloadParser.parse(raw)

        assertNotNull(command)
        assertEquals(AppCommandIds.CMD_LIST_DIR, command.commandId)
        val parsedPath = CommandPayloadParser.readString(command.payload)
        assertEquals(path, parsedPath)
    }

    @Test
    fun testBuildAndParseTwoStringsCommand() {
        val oldPath = "/storage/emulated/0/Documents/old_name.pdf"
        val newName = "new_name.pdf"
        val raw = CommandPayloadBuilder.buildWithTwoStrings(AppCommandIds.CMD_RENAME_FILE, oldPath, newName)
        val command = CommandPayloadParser.parse(raw)

        assertNotNull(command)
        assertEquals(AppCommandIds.CMD_RENAME_FILE, command.commandId)
        val (first, second) = CommandPayloadParser.readTwoStrings(command.payload)
        assertEquals(oldPath, first)
        assertEquals(newName, second)
    }

    @Test
    fun testBuildAndParseFileStartCommand() {
        val remotePath = "/data/user/0/com.app/files/database.db"
        val size = 104857600L // 100 MB
        val raw = CommandPayloadBuilder.buildFileStart(AppCommandIds.CMD_SEND_FILE_START, remotePath, size)
        val command = CommandPayloadParser.parse(raw)

        assertNotNull(command)
        assertEquals(AppCommandIds.CMD_SEND_FILE_START, command.commandId)
        val (parsedPath, parsedSize) = CommandPayloadParser.readFileStart(command.payload)
        assertEquals(remotePath, parsedPath)
        assertEquals(size, parsedSize)
    }

    @Test
    fun testParseNullOrEmptyPayloadReturnsNull() {
        assertNull(CommandPayloadParser.parse(null))
        assertNull(CommandPayloadParser.parse(ByteArray(0)))
    }

    @Test
    fun testAppCommandRegistryResolution() {
        assertEquals("CMD_LIST_DIR", AppCommandRegistry.nameFor(AppCommandIds.CMD_LIST_DIR))
        assertEquals("CMD_SEND_FILE_START", AppCommandRegistry.nameFor(AppCommandIds.CMD_SEND_FILE_START))
        assertEquals("CMD_GET_APP_VERSION", AppCommandRegistry.nameFor(AppCommandIds.CMD_GET_APP_VERSION))
        assertEquals("CMD_GET_APP_DETAILS", AppCommandRegistry.nameFor(AppCommandIds.CMD_GET_APP_DETAILS))
        assertTrue(AppCommandRegistry.nameFor(0x7F.toByte()).startsWith("UNKNOWN"))
    }

    @Test
    fun testClientAppDetailsCodecRoundTrip() {
        val original = ClientAppDetails(
            appName = "Secure Quick Transfer",
            packageName = "com.example.securequicktransferapp",
            appVersion = "2.4.1-alpha",
            osVersion = 34
        )

        val encoded = ClientAppDetailsCodec.encode(original)
        assertTrue(encoded.isNotEmpty())

        val decoded = ClientAppDetailsCodec.decode(encoded)
        assertEquals(original, decoded)
        assertEquals("Secure Quick Transfer", decoded.appName)
        assertEquals("com.example.securequicktransferapp", decoded.packageName)
        assertEquals("2.4.1-alpha", decoded.appVersion)
        assertEquals(34, decoded.osVersion)
    }

    @Test
    fun testClientAppDetailsCodecWithEmptyStrings() {
        val original = ClientAppDetails(
            appName = "",
            packageName = "com.pkg",
            appVersion = "",
            osVersion = 35
        )

        val encoded = ClientAppDetailsCodec.encode(original)
        val decoded = ClientAppDetailsCodec.decode(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun testStandardCommandIdsMatchAppCommandIds() {
        assertEquals(StandardCommandIds.CMD_LIST_DIR, AppCommandIds.CMD_LIST_DIR)
        assertEquals(StandardCommandIds.CMD_SEND_FILE_START, AppCommandIds.CMD_SEND_FILE_START)
        assertEquals(StandardCommandIds.CMD_FETCH_FILE_START, AppCommandIds.CMD_FETCH_FILE_START)
        assertEquals(StandardCommandIds.CMD_FETCH_DIR_ZIP_START, AppCommandIds.CMD_FETCH_DIR_ZIP_START)
        assertEquals(StandardCommandIds.CMD_DISCONNECT, AppCommandIds.CMD_DISCONNECT)
        assertEquals(StandardCommandIds.CMD_SEND_DIR_ZIP_START, AppCommandIds.CMD_SEND_DIR_ZIP_START)
        assertEquals(StandardCommandIds.CMD_DELETE_FILE, AppCommandIds.CMD_DELETE_FILE)
        assertEquals(StandardCommandIds.CMD_RENAME_FILE, AppCommandIds.CMD_RENAME_FILE)
        assertEquals(StandardCommandIds.CMD_CREATE_FOLDER, AppCommandIds.CMD_CREATE_FOLDER)
        assertEquals(StandardCommandIds.CMD_SET_DEVICE_ID, AppCommandIds.CMD_SET_DEVICE_ID)
    }
}
