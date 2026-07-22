package com.example.securequicktransferapp.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.example.securequicktransferapp.data.UsbRole
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsbPreferencesManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("usb_transfer_prefs", Context.MODE_PRIVATE)

    var usbRole: UsbRole?
        get() = when (prefs.getString(KEY_ROLE, null)) {
            "HOST" -> UsbRole.Host(prefs.getString(KEY_ROLE_NAME, "Remote Device") ?: "Remote Device")
            "CLIENT" -> UsbRole.Client(prefs.getString(KEY_ROLE_NAME, "Remote Host") ?: "Remote Host")
            else -> null
        }
        set(value) {
            val editor = prefs.edit()
            when (value) {
                is UsbRole.Host -> {
                    editor.putString(KEY_ROLE, "HOST")
                    editor.putString(KEY_ROLE_NAME, value.connectedDeviceName)
                }
                is UsbRole.Client -> {
                    editor.putString(KEY_ROLE, "CLIENT")
                    editor.putString(KEY_ROLE_NAME, value.connectedHostName)
                }
                null -> {
                    editor.remove(KEY_ROLE)
                }
            }
            editor.apply()
        }

    companion object {
        private const val KEY_ROLE = "saved_usb_role"
        private const val KEY_ROLE_NAME = "saved_usb_role_name"
    }
}
