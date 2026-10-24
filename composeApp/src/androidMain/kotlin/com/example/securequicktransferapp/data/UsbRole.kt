package com.example.securequicktransferapp.data

sealed class UsbRole {
    // Android is strictly the Client/Responder - it receives and processes commands from the Desktop Host
    data class Client(val connectedHostName: String = "Desktop Host") : UsbRole()
}
