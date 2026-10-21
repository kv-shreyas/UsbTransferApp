package com.example.usbdriverinstaller.core

import com.example.usbdriverinstaller.api.DriverInstallResult
import com.example.usbdriverinstaller.api.IUsbDriverInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Standard implementation of [IUsbDriverInstaller] that drives wdi-simple.exe,
 * certificate installation, PnPUtil driver deployment, and the Zadig GUI on Windows.
 */
class ZadigDriverInstallerImpl : IUsbDriverInstaller {

    private val tag = "ZadigDriverInstaller"

    override fun isWindows(): Boolean {
        return System.getProperty("os.name")?.lowercase()?.contains("win") == true
    }

    override fun isDriverAlreadyInstalled(vendorId: Int, productId: Int): Boolean {
        if (!isWindows()) return true

        return try {
            val vidStr = String.format("%04X", vendorId)
            val pidStr = String.format("%04X", productId)

            // Query Windows Registry SetupAPI for the active driver service
            val process = ProcessBuilder(
                "reg", "query",
                "HKLM\\SYSTEM\\CurrentControlSet\\Enum\\USB\\VID_${vidStr}&PID_${pidStr}",
                "/s", "/f", "Service", "/t", "REG_SZ"
            )
                .redirectErrorStream(true)
                .start()

            val result = process.inputStream.bufferedReader().readText()
            process.waitFor(5, TimeUnit.SECONDS)

            val compatibleServices = setOf("winusb", "libusbk", "libusb0")
            val serviceFound = compatibleServices.any { result.lowercase().contains(it) }
            println("[$tag] isDriverAlreadyInstalled VID=$vidStr PID=$pidStr -> $serviceFound")
            serviceFound
        } catch (e: Exception) {
            println("[$tag] Registry check error: ${e.message}")
            false
        }
    }

    override fun installWinUsb(
        vendorId: Int,
        productId: Int,
        deviceName: String,
        timeoutMs: Long
    ): DriverInstallResult {
        if (!isWindows()) {
            println("[$tag] Skipping driver install — host OS is not Windows.")
            return DriverInstallResult.NotWindows
        }

        val vidHex = String.format("0x%04X", vendorId)
        val pidHex = String.format("0x%04X", productId)
        val vidClean = String.format("%04X", vendorId)
        val pidClean = String.format("%04X", productId)

        if (isDriverAlreadyInstalled(vendorId, productId)) {
            println("[$tag] WinUSB driver is already installed for VID=$vidHex PID=$pidHex — skipping redundant installation.")
            return DriverInstallResult.Success
        }

        println("[$tag] Starting WinUSB installation for VID=$vidHex PID=$pidHex ($deviceName)...")

        // 1. Extract wdi-simple.exe
        val wdiExe = ResourceExtractor.extractWdiSimple()
            ?: return DriverInstallResult.ExtractionFailed

        // 2. Prepare driver staging directory
        val driverOutputDir = try {
            Files.createTempDirectory("sqt_driver_").toFile().also { it.deleteOnExit() }
        } catch (e: Exception) {
            println("[$tag] Failed to create driver output directory: ${e.message}")
            return DriverInstallResult.Failure(-1, "Could not create staging directory: ${e.message}")
        }

        // 3. Pre-populate native binaries & INF/CAT/CER packages
        ResourceExtractor.populateDriverBinaries(driverOutputDir)

        // 4. Build scripts for elevated installation
        val logFile = File(driverOutputDir, "wdi_install.log")
        val batchFile = File(driverOutputDir, "run_install.bat")
        val psFile = File(driverOutputDir, "elevate.ps1")
        val forwardSlashPath = driverOutputDir.absolutePath.replace('\\', '/')

        val batchContent = buildString {
            appendLine("@echo off")
            appendLine("chcp 65001 >nul")
            appendLine("echo [USB Driver Installer] Starting driver setup for VID $vidClean PID $pidClean... >> \"${logFile.absolutePath}\"")

            // A. Remove conflicting ssudbus (Samsung) or usbser (CDC serial) drivers for this PID
            appendLine("powershell -NoProfile -Command \"Get-ChildItem 'C:\\Windows\\INF\\oem*.inf' | ForEach-Object { \$c = Get-Content \$_.FullName -Raw -ErrorAction SilentlyContinue; if (\$c -match '$pidClean' -and (\$c -match 'usbser' -or \$c -match 'ssudbus')) { Write-Host 'Removing conflicting driver:' \$_.Name; pnputil /delete-driver \$_.Name /uninstall /force } }\" >> \"${logFile.absolutePath}\" 2>&1")

            // B. Register certificates into TrustedPublisher and Root
            appendLine("powershell -NoProfile -Command \"if (Test-Path '$forwardSlashPath/aoa_driver.cer') { Import-Certificate -FilePath '$forwardSlashPath/aoa_driver.cer' -CertStoreLocation 'Cert:\\LocalMachine\\TrustedPublisher' | Out-Null; Import-Certificate -FilePath '$forwardSlashPath/aoa_driver.cer' -CertStoreLocation 'Cert:\\LocalMachine\\Root' | Out-Null }\" >> \"${logFile.absolutePath}\" 2>&1")
            appendLine("powershell -NoProfile -Command \"Get-ChildItem '$forwardSlashPath/*.cat' | ForEach-Object { try { \$sig = Get-AuthenticodeSignature \$_.FullName; if (\$sig.SignerCertificate) { \$store = Get-Item -Path 'Cert:\\LocalMachine\\TrustedPublisher'; \$store.Open('ReadWrite'); \$store.Add(\$sig.SignerCertificate); \$store.Close() } } catch {} }\" >> \"${logFile.absolutePath}\" 2>&1")

            // C. Install bundled WinUSB driver packages via PnPUtil
            appendLine("if exist \"${driverOutputDir.absolutePath}\\Android_Accessory_Interface.inf\" pnputil /add-driver \"${driverOutputDir.absolutePath}\\Android_Accessory_Interface.inf\" /install >> \"${logFile.absolutePath}\" 2>&1")
            appendLine("if exist \"${driverOutputDir.absolutePath}\\Android_Accessory_Interface_(Interface_0).inf\" pnputil /add-driver \"${driverOutputDir.absolutePath}\\Android_Accessory_Interface_(Interface_0).inf\" /install >> \"${logFile.absolutePath}\" 2>&1")

            // D. Rescan and restart USB devices
            appendLine("echo [USB Driver Installer] Rescanning and restarting USB devices for $vidClean:$pidClean... >> \"${logFile.absolutePath}\"")
            appendLine("pnputil /scan-devices >> \"${logFile.absolutePath}\" 2>&1")
            appendLine("pnputil /restart-device /deviceid \"USB\\VID_$vidClean&PID_$pidClean\" >> \"${logFile.absolutePath}\" 2>&1")
            appendLine("pnputil /restart-device /deviceid \"USB\\VID_$vidClean&PID_$pidClean&MI_00\" >> \"${logFile.absolutePath}\" 2>&1")
            appendLine("echo [USB Driver Installer] WinUSB driver successfully installed. >> \"${logFile.absolutePath}\"")
            appendLine("exit /b 0")
        }
        batchFile.writeText(batchContent, Charsets.UTF_8)

        // PowerShell wrapper to launch the batch script elevated via UAC and capture exit code
        val psContent = buildString {
            appendLine("\$ErrorActionPreference = 'Stop'")
            appendLine("\$batchPath = Join-Path \$PSScriptRoot 'run_install.bat'")
            appendLine("try {")
            appendLine("    \$proc = Start-Process -FilePath 'cmd.exe' -ArgumentList @('/c', \"`\"\$batchPath`\"\") -Verb RunAs -Wait -PassThru -WindowStyle Hidden")
            appendLine("    exit \$proc.ExitCode")
            appendLine("} catch {")
            appendLine("    exit 1223") // ERROR_CANCELLED (User declined UAC prompt)
            appendLine("}")
        }
        psFile.writeText(psContent, Charsets.UTF_8)

        val command = listOf(
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-File",
            psFile.absolutePath
        )

        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val processStdout = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                println("[$tag] Installation timed out after ${timeoutMs}ms.")
                return DriverInstallResult.Failure(-1, "Installation timed out after ${timeoutMs}ms")
            }

            val exitCode = process.exitValue()
            val outputLog = if (logFile.exists() && logFile.length() > 0) {
                val lines = logFile.readLines()
                lines.forEach { println("[$tag][wdi-log] $it") }
                lines.joinToString("\n")
            } else {
                if (processStdout.isNotBlank()) {
                    println("[$tag][powershell-stdout] $processStdout")
                }
                processStdout
            }

            println("[$tag] Elevated installer finished with exit code: $exitCode")
            mapExitCode(exitCode, outputLog)
        } catch (e: Exception) {
            println("[$tag] Execution error: ${e.message}")
            DriverInstallResult.Failure(-1, e.message ?: "Unknown process execution error")
        }
    }

    override fun installWinUsbAsync(
        vendorId: Int,
        productId: Int,
        deviceName: String,
        timeoutMs: Long
    ): CompletableFuture<DriverInstallResult> {
        return CompletableFuture.supplyAsync {
            installWinUsb(vendorId, productId, deviceName, timeoutMs)
        }
    }

    override suspend fun installWinUsbSuspend(
        vendorId: Int,
        productId: Int,
        deviceName: String,
        timeoutMs: Long
    ): DriverInstallResult = withContext(Dispatchers.IO) {
        installWinUsb(vendorId, productId, deviceName, timeoutMs)
    }

    override fun launchZadigGui(timeoutMs: Long): Boolean {
        if (!isWindows()) {
            println("[$tag] Skipping Zadig GUI launch — host is not Windows.")
            return false
        }

        val zadigExe = ResourceExtractor.extractZadig() ?: return false
        val zadigDir = zadigExe.parentFile
        val zadigPath = zadigExe.absolutePath.replace("'", "''")
        val zadigDirPath = zadigDir.absolutePath.replace("'", "''")

        val runnerScript = File(zadigDir, "run_zadig.ps1")
        val scriptContent = buildString {
            appendLine("\$ErrorActionPreference = 'SilentlyContinue'")
            appendLine("# 1. Clean up stale/conflicting usbser drivers for Android devices")
            appendLine("Get-ChildItem 'C:\\Windows\\INF\\oem*.inf' -ErrorAction SilentlyContinue | ForEach-Object {")
            appendLine("    \$c = Get-Content \$_.FullName -Raw -ErrorAction SilentlyContinue")
            appendLine("    if (\$c -match '18D1' -and \$c -match 'usbser') {")
            appendLine("        pnputil /delete-driver \$_.Name /uninstall /force | Out-Null")
            appendLine("    }")
            appendLine("}")
            appendLine("# 2. Launch Zadig in elevated mode with working directory pointing to zadig.ini")
            appendLine("\$proc = Start-Process -FilePath '$zadigPath' -WorkingDirectory '$zadigDirPath' -Wait -PassThru")
            appendLine("exit \$proc.ExitCode")
        }
        runnerScript.writeText(scriptContent, Charsets.UTF_8)
        val scriptPath = runnerScript.absolutePath.replace("'", "''")

        val command = listOf(
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-Command",
            "Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', '$scriptPath') -Verb RunAs -Wait"
        )

        return try {
            val process = ProcessBuilder(command).start()
            println("[$tag] Zadig GUI launched with elevation request and pre-configured zadig.ini.")
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                println("[$tag] Zadig execution timed out.")
                return false
            }
            val exitCode = process.exitValue()
            println("[$tag] Zadig process finished with exit code: $exitCode")
            exitCode == 0
        } catch (e: Exception) {
            println("[$tag] Failed to launch Zadig GUI: ${e.message}")
            false
        }
    }

    override fun launchZadigGuiAsync(timeoutMs: Long): CompletableFuture<Boolean> {
        return CompletableFuture.supplyAsync {
            launchZadigGui(timeoutMs)
        }
    }

    override suspend fun launchZadigGuiSuspend(timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        launchZadigGui(timeoutMs)
    }

    override fun extractTools(destinationDirectory: File): Boolean {
        return ResourceExtractor.extractAllTools(destinationDirectory)
    }

    override fun cleanupConflictingDrivers(vendorId: Int, productId: Int): Boolean {
        if (!isWindows()) return false
        val pidClean = String.format("%04X", productId)
        return try {
            val cmd = listOf(
                "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
                "Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoProfile', '-Command', \"Get-ChildItem 'C:\\Windows\\INF\\oem*.inf' | ForEach-Object { \$c = Get-Content \$_.FullName -Raw -ErrorAction SilentlyContinue; if (\$c -match '$pidClean' -and (\$c -match 'usbser' -or \$c -match 'ssudbus')) { pnputil /delete-driver \$_.Name /uninstall /force } }\") -Verb RunAs -Wait"
            )
            val process = ProcessBuilder(cmd).start()
            process.waitFor(30, TimeUnit.SECONDS)
            process.exitValue() == 0
        } catch (e: Exception) {
            println("[$tag] Cleanup failed: ${e.message}")
            false
        }
    }

    override fun rescanAndRestartDevice(vendorId: Int, productId: Int): Boolean {
        if (!isWindows()) return false
        val vidClean = String.format("%04X", vendorId)
        val pidClean = String.format("%04X", productId)
        return try {
            val cmd = listOf(
                "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
                "Start-Process -FilePath 'pnputil.exe' -ArgumentList @('/scan-devices') -Verb RunAs -Wait; Start-Process -FilePath 'pnputil.exe' -ArgumentList @('/restart-device', '/deviceid', 'USB\\VID_$vidClean&PID_$pidClean') -Verb RunAs -Wait"
            )
            val process = ProcessBuilder(cmd).start()
            process.waitFor(30, TimeUnit.SECONDS)
            process.exitValue() == 0
        } catch (e: Exception) {
            println("[$tag] Device restart failed: ${e.message}")
            false
        }
    }

    private fun mapExitCode(exitCode: Int, output: String): DriverInstallResult = when (exitCode) {
        0    -> DriverInstallResult.Success
        2    -> DriverInstallResult.Failure(2, "Installer attempted to install serial driver (usbser) instead of WinUSB. Please use Device Manager or Zadig to select WinUSB.")
        -4   -> DriverInstallResult.DeviceNotFound
        -3   -> DriverInstallResult.AccessDenied
        -15  -> DriverInstallResult.AccessDenied         // WDI_ERROR_NEEDS_ADMIN
        1223 -> DriverInstallResult.AccessDenied         // ERROR_CANCELLED (User declined UAC prompt)
        -19  -> DriverInstallResult.UnsignedDriverBlocked
        else -> DriverInstallResult.Failure(exitCode, output.takeLast(500))
    }
}
