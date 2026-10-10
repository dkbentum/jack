package com.example.jack

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.location.LocationManagerCompat

/**
 * Android 6–11 only return BLE scan results while Location (GPS) is switched on.
 * Android 12+ doesn't need it (Jack declares BLUETOOTH_SCAN with neverForLocation).
 */
object LocationHelper {
    private const val TAG = "LocationHelper"

    /** True on Android versions where BLE scanning silently finds nothing with Location off. */
    val isRequiredForBle: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

    fun isLocationEnabled(context: Context): Boolean = try {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        LocationManagerCompat.isLocationEnabled(lm)
    } catch (_: Exception) {
        true
    }

    /** True when BLE is the transport and Location must be turned on before scanning can work. */
    fun needsEnablingForBle(context: Context): Boolean =
        isRequiredForBle && !isLocationEnabled(context)

    /**
     * Turns Location on without any prompt. Only possible when Jack is the device owner (kiosk setup).
     * Returns true if Location is on afterwards.
     */
    fun enableAsDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(context.packageName)) return false

        val admin = ComponentName(context, JackDeviceAdminReceiver::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                dpm.setLocationEnabled(admin, true)
            } else {
                @Suppress("DEPRECATION")
                dpm.setSecureSetting(
                    admin,
                    Settings.Secure.LOCATION_MODE,
                    Settings.Secure.LOCATION_MODE_HIGH_ACCURACY.toString()
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Device owner could not enable Location: ${e.localizedMessage}")
        }
        return isLocationEnabled(context)
    }
}
