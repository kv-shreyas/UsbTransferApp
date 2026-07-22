package com.example.securequicktransferapp.data

sealed class UsbRole {
    // This device is the Host/Initiator - it controls the connection and sends commands
    data class Host(val connectedDeviceName: String = "Unknown Device") : UsbRole()
    // This device is the Client/Responder - it receives and processes commands
    data class Client(val connectedHostName: String = "Unknown Host") : UsbRole()
}
