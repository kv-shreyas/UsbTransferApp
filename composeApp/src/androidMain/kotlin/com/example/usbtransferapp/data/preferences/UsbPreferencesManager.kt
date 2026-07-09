package com.example.usbtransferapp.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.example.usbtransferapp.data.UsbConnectionMode
import com.example.usbtransferapp.data.UsbRole
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsbPreferencesManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("usb_transfer_prefs", Context.MODE_PRIVATE)

    var connectionMode: UsbConnectionMode
        get() = try {
            val modeStr = prefs.getString(KEY_MODE, UsbConnectionMode.DESKTOP_TO_ANDROID.name)
            UsbConnectionMode.valueOf(modeStr ?: UsbConnectionMode.DESKTOP_TO_ANDROID.name)
        } catch (e: Exception) {
            UsbConnectionMode.DESKTOP_TO_ANDROID
        }
        set(value) {
            prefs.edit().putString(KEY_MODE, value.name).apply()
        }

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
        private const val KEY_MODE = "saved_connection_mode"
        private const val KEY_ROLE = "saved_usb_role"
        private const val KEY_ROLE_NAME = "saved_usb_role_name"
    }
}
