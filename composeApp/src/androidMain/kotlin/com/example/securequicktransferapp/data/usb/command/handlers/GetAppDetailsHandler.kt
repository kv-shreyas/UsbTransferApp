package com.example.securequicktransferapp.data.usb.command.handlers

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.example.secureqt.sdk.channel.SecureChannel
import com.example.securequicktransferapp.data.logging.UsbLogger
import com.example.securequicktransferapp.data.usb.command.CommandContext
import com.example.securequicktransferapp.data.usb.command.ICommandHandler
import com.example.securequicktransferapp.domain.command.AppCommandIds
import com.example.securequicktransferapp.domain.model.deviceinfo.ClientAppDetails
import com.example.securequicktransferapp.domain.model.deviceinfo.ClientAppDetailsCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GetAppDetailsHandler"

/**
 * Handles [AppCommandIds.CMD_GET_APP_DETAILS]: queries local Android application metadata
 * (appName, packageName, appVersion, and osVersion) and returns the encoded [ClientAppDetails]
 * payload back to the Desktop Host over the secure channel.
 *
 * Wire format received: `[cmd: CMD_GET_APP_DETAILS]` (no arguments needed)
 * Wire format sent:     Encoded [ClientAppDetails] byte array via [ClientAppDetailsCodec]
 */
@Singleton
class GetAppDetailsHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val channel: SecureChannel,
    private val usbLogger: UsbLogger
) : ICommandHandler {

    override val commandId: Byte = AppCommandIds.CMD_GET_APP_DETAILS

    override suspend fun handle(payload: ByteBuffer, cmdCtx: CommandContext): Boolean {
        usbLogger.d(TAG, "Received CMD_GET_APP_DETAILS request from host")

        val details = collectClientAppDetails()
        val responseBytes = ClientAppDetailsCodec.encode(details)

        usbLogger.i(TAG, "Sending ClientAppDetails response (${responseBytes.size} bytes) for ${details.packageName} v${details.appVersion}")
        channel.sendSecure(responseBytes)

        return true // keep command receive loop active
    }

    private fun collectClientAppDetails(): ClientAppDetails {
        val pm = context.packageManager
        val packageName = context.packageName

        val appName = try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            usbLogger.w(TAG, "Failed to resolve application label: ${e.message}")
            "Secure Quick Transfer"
        }

        val pkgInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
        } catch (e: Exception) {
            usbLogger.w(TAG, "Failed to resolve PackageInfo: ${e.message}")
            null
        }

        val appVersion = pkgInfo?.versionName ?: "1.0.0"
        val osVersion = Build.ID
        usbLogger.i(TAG, "osVersion: $osVersion")


        return ClientAppDetails(
            appName = appName,
            packageName = packageName,
            appVersion = appVersion,
            osVersion = osVersion
        )
    }
}
