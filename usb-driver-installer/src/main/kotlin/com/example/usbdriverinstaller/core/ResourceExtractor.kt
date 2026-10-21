package com.example.usbdriverinstaller.core

import java.io.File
import java.io.InputStream
import java.nio.file.Files

/**
 * Handles extraction of bundled native binaries, driver inf/cat packages, and tools
 * from the JAR classpath to temporary directories on the host filesystem.
 */
object ResourceExtractor {

    private const val TAG = "ResourceExtractor"

    const val WDI_SIMPLE_RESOURCE = "tools/wdi-simple.exe"
    const val ZADIG_RESOURCE = "tools/zadig.exe"
    const val USB_DRIVER_RESOURCE_PREFIX = "tools/usb_driver"
    const val DRIVER_PKG_RESOURCE_PREFIX = "tools/driver_pkg"

    val USB_DRIVER_BINARIES = listOf(
        "installer_x64.exe",
        "installer_x86.exe",
        "installer_arm64.exe",
        "amd64/winusbcoinstaller2.dll",
        "amd64/WdfCoInstaller01011.dll",
        "amd64/install-filter.exe",
        "amd64/libusb0.dll",
        "amd64/libusb0.sys",
        "amd64/libusb0_x86.dll",
        "amd64/libusbK.dll",
        "amd64/libusbK.sys",
        "amd64/libusbK_x86.dll",
        "x86/winusbcoinstaller2.dll",
        "x86/WdfCoInstaller01011.dll",
        "x86/install-filter.exe",
        "x86/libusb0.dll",
        "x86/libusb0.sys",
        "x86/libusb0_x86.dll",
        "x86/libusbK.dll",
        "x86/libusbK.sys",
        "arm64/install-filter.exe",
        "arm64/libusb0.dll",
        "arm64/libusb0.sys"
    )

    val DRIVER_PACKAGE_FILES = listOf(
        "Android_Accessory_Interface.cat",
        "Android_Accessory_Interface.inf",
        "Android_Accessory_Interface_(Interface_0).cat",
        "Android_Accessory_Interface_(Interface_0).inf",
        "aoa_driver.cer"
    )

    /**
     * Extracts an individual resource stream to [destinationFile].
     */
    fun extractResource(resourcePath: String, destinationFile: File): Boolean {
        destinationFile.parentFile?.mkdirs()
        val stream = openResourceStream(resourcePath) ?: return false

        return try {
            stream.use { input ->
                destinationFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            destinationFile.setExecutable(true, false)
            destinationFile.deleteOnExit()
            true
        } catch (e: Exception) {
            println("[$TAG] Failed to extract $resourcePath to ${destinationFile.absolutePath}: ${e.message}")
            false
        }
    }

    /**
     * Extracts wdi-simple.exe to a temporary directory.
     */
    fun extractWdiSimple(): File? {
        return try {
            val tempDir = Files.createTempDirectory("sqt_wdi_").toFile().also { it.deleteOnExit() }
            val outFile = File(tempDir, "wdi-simple.exe")
            if (extractResource(WDI_SIMPLE_RESOURCE, outFile)) {
                println("[$TAG] Extracted wdi-simple.exe -> ${outFile.absolutePath}")
                outFile
            } else {
                null
            }
        } catch (e: Exception) {
            println("[$TAG] Could not create temp directory for wdi-simple.exe: ${e.message}")
            null
        }
    }

    /**
     * Extracts zadig.exe and writes a pre-configured zadig.ini in the same temp directory.
     */
    fun extractZadig(): File? {
        return try {
            val tempDir = Files.createTempDirectory("sqt_zadig_ui_").toFile().also { it.deleteOnExit() }
            val outFile = File(tempDir, "zadig.exe")
            if (!extractResource(ZADIG_RESOURCE, outFile)) {
                return null
            }
            println("[$TAG] Extracted zadig.exe -> ${outFile.absolutePath}")

            // Write pre-configured zadig.ini so Zadig opens with "List All Devices" pre-checked
            // and WinUSB pre-selected as the default driver
            val iniFile = File(tempDir, "zadig.ini")
            val iniContent = """
                [general]
                exit_on_success = true

                [device]
                list_all = true
                include_hubs = true

                [driver]
                default_driver = 0
            """.trimIndent()
            iniFile.writeText(iniContent, Charsets.UTF_8)
            iniFile.deleteOnExit()
            println("[$TAG] Generated zadig.ini in ${iniFile.absolutePath}")

            outFile
        } catch (e: Exception) {
            println("[$TAG] Could not extract zadig.exe: ${e.message}")
            null
        }
    }

    /**
     * Populates [destDir] with all native architecture binaries and driver INF/CAT/CER files.
     * This avoids the `WDI_ERROR_NOT_FOUND` (-5) error on 64-bit Windows.
     */
    fun populateDriverBinaries(destDir: File): Boolean {
        destDir.mkdirs()
        var allSucceeded = true

        // 1. Extract usb_driver binaries
        for (relPath in USB_DRIVER_BINARIES) {
            val resourcePath = "$USB_DRIVER_RESOURCE_PREFIX/$relPath"
            val targetFile = File(destDir, relPath)
            if (!extractResource(resourcePath, targetFile)) {
                // Fallback attempt: check local relative filesystem paths if running in IDE/unbundled
                val fallbackPaths = listOf(
                    File("tools/windows/usb_driver", relPath),
                    File("tools/usb_driver", relPath),
                    File(System.getProperty("user.home"), "usb_driver/$relPath")
                )
                var copied = false
                for (fallback in fallbackPaths) {
                    if (fallback.exists()) {
                        try {
                            targetFile.parentFile?.mkdirs()
                            fallback.copyTo(targetFile, overwrite = true)
                            targetFile.setExecutable(true, false)
                            targetFile.deleteOnExit()
                            copied = true
                            break
                        } catch (ignored: Exception) {}
                    }
                }
                if (!copied) {
                    println("[$TAG] Warning: could not resolve $resourcePath")
                    allSucceeded = false
                }
            }
        }

        // 2. Extract driver_pkg INF/CAT/CER packages
        for (pkgFile in DRIVER_PACKAGE_FILES) {
            val resourcePath = "$DRIVER_PKG_RESOURCE_PREFIX/$pkgFile"
            val targetFile = File(destDir, pkgFile)
            if (!extractResource(resourcePath, targetFile)) {
                val fallbackPaths = listOf(
                    File("tools/windows/driver_pkg", pkgFile),
                    File("tools/driver_pkg", pkgFile)
                )
                var copied = false
                for (fallback in fallbackPaths) {
                    if (fallback.exists()) {
                        try {
                            targetFile.parentFile?.mkdirs()
                            fallback.copyTo(targetFile, overwrite = true)
                            targetFile.deleteOnExit()
                            copied = true
                            break
                        } catch (ignored: Exception) {}
                    }
                }
                if (!copied) {
                    println("[$TAG] Warning: could not resolve $resourcePath")
                    allSucceeded = false
                }
            }
        }

        return allSucceeded
    }

    /**
     * Extracts all tools into the provided [destinationDirectory].
     */
    fun extractAllTools(destinationDirectory: File): Boolean {
        destinationDirectory.mkdirs()
        val wdiSuccess = extractResource(WDI_SIMPLE_RESOURCE, File(destinationDirectory, "wdi-simple.exe"))
        val zadigSuccess = extractResource(ZADIG_RESOURCE, File(destinationDirectory, "zadig.exe"))
        val binariesSuccess = populateDriverBinaries(destinationDirectory)
        return wdiSuccess && zadigSuccess && binariesSuccess
    }

    private fun openResourceStream(resourcePath: String): InputStream? {
        val normalized = resourcePath.trimStart('/')
        // 1. Thread Context ClassLoader
        Thread.currentThread().contextClassLoader?.getResourceAsStream(normalized)?.let { return it }
        // 2. ResourceExtractor ClassLoader
        ResourceExtractor::class.java.classLoader?.getResourceAsStream(normalized)?.let { return it }
        // 3. Absolute path via Class.getResourceAsStream
        ResourceExtractor::class.java.getResourceAsStream("/$normalized")?.let { return it }
        return null
    }
}
