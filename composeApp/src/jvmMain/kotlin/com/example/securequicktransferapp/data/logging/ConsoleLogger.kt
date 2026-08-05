package com.example.securequicktransferapp.data.logging

import com.example.secureqt.sdk.logging.ILogger

class ConsoleLogger : ILogger {
    override fun d(tag: String, message: String) {
        println("[DEBUG] [$tag] $message")
    }

    override fun i(tag: String, message: String) {
        println("[INFO] [$tag] $message")
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        println("[WARN] [$tag] $message")
        throwable?.printStackTrace()
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        println("[ERROR] [$tag] $message")
        throwable?.printStackTrace()
    }
}
