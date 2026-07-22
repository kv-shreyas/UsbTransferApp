package com.example.securequicktransferapp

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform