package com.example.securequicktransferapp.domain

import com.example.secureqt.sdk.SecureQtSdk
import com.example.secureqt.sdk.autoConnectDesktop
import com.example.secureqt.sdk.createDesktopTransport
import com.example.securequicktransferapp.data.repo.UsbRepositoryImpl
import org.usb4java.DeviceHandle
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SecureQtSdkDesktopExtTest {

    @Test
    fun testCreateDesktopTransportExtension() {
        val dummyHandle = DeviceHandle()
        val transport = SecureQtSdk.createDesktopTransport(
            handle = dummyHandle,
            inEp = 0x81.toByte(),
            outEp = 0x01.toByte(),
            ifaceNum = 0
        )
        assertNotNull(transport)
    }

    @Test
    fun testAutoConnectDesktopWhenNoDeviceAttached() {
        // When no physical device is connected, autoConnectDesktop should return null gracefully without crashing
        val channel = SecureQtSdk.autoConnectDesktop(timeoutMs = 100L)
        assertNull(channel)
    }

    @Test
    fun testRepositoryAutoConnectDirectWhenNoDeviceAttached() {
        val channel = UsbRepositoryImpl.autoConnectDirect(timeoutMs = 100L)
        assertNull(channel)
    }
}
