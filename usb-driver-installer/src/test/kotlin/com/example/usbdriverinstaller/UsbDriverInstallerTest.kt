package com.example.usbdriverinstaller

import com.example.usbdriverinstaller.api.DriverInstallResult
import com.example.usbdriverinstaller.api.DriverInstallState
import com.example.usbdriverinstaller.api.UsbDriverInstallerFactory
import com.example.usbdriverinstaller.core.ResourceExtractor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UsbDriverInstallerTest {

    @Test
    fun testFactoryCreation() {
        val installer = UsbDriverInstallerFactory.createInstaller()
        assertNotNull(installer, "Installer should be instantiated")

        val manager = UsbDriverInstallerFactory.createManager(installer)
        assertNotNull(manager, "Manager should be instantiated")
        assertEquals(DriverInstallState.Idle, manager.driverInstallState.value)
    }

    @Test
    fun testClasspathResourcesExist() {
        // Verify wdi-simple.exe resource is on classpath
        val wdiStream = javaClass.classLoader.getResourceAsStream(ResourceExtractor.WDI_SIMPLE_RESOURCE)
        assertNotNull(wdiStream, "Resource '${ResourceExtractor.WDI_SIMPLE_RESOURCE}' must exist in classpath")
        wdiStream.close()

        // Verify zadig.exe resource is on classpath
        val zadigStream = javaClass.classLoader.getResourceAsStream(ResourceExtractor.ZADIG_RESOURCE)
        assertNotNull(zadigStream, "Resource '${ResourceExtractor.ZADIG_RESOURCE}' must exist in classpath")
        zadigStream.close()

        // Verify driver package files exist
        for (pkgFile in ResourceExtractor.DRIVER_PACKAGE_FILES) {
            val path = "${ResourceExtractor.DRIVER_PKG_RESOURCE_PREFIX}/$pkgFile"
            val stream = javaClass.classLoader.getResourceAsStream(path)
            assertNotNull(stream, "Driver package resource '$path' must exist in classpath")
            stream.close()
        }

        // Verify key native binaries exist
        val x64Installer = "${ResourceExtractor.USB_DRIVER_RESOURCE_PREFIX}/installer_x64.exe"
        val x64Stream = javaClass.classLoader.getResourceAsStream(x64Installer)
        assertNotNull(x64Stream, "Native binary '$x64Installer' must exist in classpath")
        x64Stream.close()
    }

    @Test
    fun testDriverResultHelpers() {
        val success = DriverInstallResult.Success
        assertTrue(success.isSuccessful())
        assertEquals("Driver installed successfully or already present.", success.getResultMessage())

        val failure = DriverInstallResult.Failure(2, "Test error")
        assertTrue(!failure.isSuccessful())
        assertEquals(2, failure.exitCode)
    }
}
