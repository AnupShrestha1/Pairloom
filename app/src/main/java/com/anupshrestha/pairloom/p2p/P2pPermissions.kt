package com.anupshrestha.pairloom.p2p

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object P2pPermissions {
    /**
     * Runtime permissions Wi-Fi Direct discovery needs on the given Android version.
     * Android 13 (API 33) and newer use "Nearby devices"; older versions use Location.
     */
    fun required(sdk: Int = Build.VERSION.SDK_INT): List<String> =
        if (sdk >= 33) {
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** Android 12 (API 32) and older need Location services switched on for Wi-Fi Direct discovery. */
    fun locationServicesRequired(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk <= 32

    fun missing(context: Context): List<String> = required().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }
}
