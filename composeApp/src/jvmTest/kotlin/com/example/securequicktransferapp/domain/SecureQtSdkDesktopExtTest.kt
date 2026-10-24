package com.example.securequicktransferapp.domain

import com.example.secureqt.sdk.SecureQtSdk
import com.example.secureqt.sdk.createDesktopTransport
import org.usb4java.DeviceHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
        assertEquals(0x81.toByte(), transport.endpointIn)
        assertEquals(0x01.toByte(), transport.endpointOut)
        assertEquals(0, transport.interfaceNumber)
    }
}
