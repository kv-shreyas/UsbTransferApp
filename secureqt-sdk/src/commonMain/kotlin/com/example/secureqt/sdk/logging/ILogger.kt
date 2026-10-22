package com.example.secureqt.sdk.logging

/**
 * Standardized logging interface for the SDK and application.
 * Allows dependency inversion: the JVM can inject a standard console logger,
 * while Android can inject its existing File/Logcat logger.
 */
interface ILogger {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}
