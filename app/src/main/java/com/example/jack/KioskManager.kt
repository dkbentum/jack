package com.example.jack

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log

class KioskManager(private val context: Context) {
    companion object {
        private const val TAG = "DedicatedDevice"
    }

    private val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val adminComponent = ComponentName(context, JackDeviceAdminReceiver::class.java)

    val isDeviceOwner: Boolean
        get() {
            val owner = devicePolicyManager.isDeviceOwnerApp(context.packageName)
            Log.d(TAG, "Device Owner status: $owner")
            return owner
        }

    fun configureKiosk() {
        if (!isDeviceOwner) {
            Log.d(TAG, "Not Device Owner. Skipping kiosk configuration.")
            return
        }

        try {
            Log.d(TAG, "Configuring Lock Task packages for package: ${context.packageName}")
            devicePolicyManager.setLockTaskPackages(adminComponent, arrayOf(context.packageName))
            devicePolicyManager.setKeyguardDisabled(adminComponent, true)
            Log.d(TAG, "Lock Task package and keyguard configuration successful.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure Lock Task packages: ${e.localizedMessage}")
        }
    }

    fun startLockTask(activity: Activity) {
        if (!isDeviceOwner) {
            Log.d(TAG, "Not Device Owner. Cannot enforce Lock Task Mode.")
            return
        }

        try {
            if (!isLockTaskActive) {
                Log.d(TAG, "Starting Lock Task Mode...")
                activity.startLockTask()
                Log.d(TAG, "Lock Task Mode started successfully.")
            } else {
                Log.d(TAG, "Lock Task Mode already active.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Lock Task Mode: ${e.localizedMessage}")
        }
    }

    fun stopLockTask(activity: Activity) {
        try {
            if (isLockTaskActive) {
                Log.d(TAG, "Stopping Lock Task Mode...")
                activity.stopLockTask()
                Log.d(TAG, "Lock Task Mode stopped.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop Lock Task Mode: ${e.localizedMessage}")
        }
    }

    val isLockTaskActive: Boolean
        get() = try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE
        } catch (_: Exception) {
            false
        }
}
