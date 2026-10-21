package com.example.usbdriverinstaller.api

/**
 * Result of a USB driver installation attempt.
 * Sealed hierarchy providing structured outcome details for both Java and Kotlin consumers.
 */
sealed class DriverInstallResult(
    val isSuccess: Boolean,
    open val message: String
) {
    /** Driver was successfully installed or was already present. */
    object Success : DriverInstallResult(true, "Driver installed successfully or already present.")

    /** The target device was not physically connected when installation was attempted. */
    object DeviceNotFound : DriverInstallResult(false, "Device not physically connected.")

    /** UAC elevation was denied or user dismissed the elevation prompt. */
    object AccessDenied : DriverInstallResult(false, "Administrator elevation was denied (UAC prompt not accepted).")

    /** Windows policy blocked unsigned driver installation. */
    object UnsignedDriverBlocked : DriverInstallResult(false, "Windows driver signature enforcement blocked installation.")

    /** Installer binaries could not be extracted from the classpath resources. */
    object ExtractionFailed : DriverInstallResult(false, "Failed to extract installer tools from JAR resources.")

    /** The host operating system is not Windows (driver installation is Windows-only). */
    object NotWindows : DriverInstallResult(false, "Host operating system is not Windows.")

    /** Generic failure with exit code and diagnostic details. */
    data class Failure(
        val exitCode: Int,
        override val message: String
    ) : DriverInstallResult(false, message)

    /** Java convenience method to check success. */
    fun isSuccessful(): Boolean = isSuccess

    /** Java convenience method to retrieve status message. */
    fun getResultMessage(): String = message
}
