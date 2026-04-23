package com.example.usbtransferapp

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform