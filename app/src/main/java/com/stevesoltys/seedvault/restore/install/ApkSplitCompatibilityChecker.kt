/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.restore.install

import android.util.Log

private const val TAG = "SplitCompatChecker"

private const val CONFIG_PREFIX = "config."
private const val CONFIG_LENGTH = CONFIG_PREFIX.length

// see https://developer.android.com/training/multiscreen/screendensities#TaskProvideAltBmp
private const val LDPI_VALUE = 120
private const val MDPI_VALUE = 160
private const val TVDPI_VALUE = 213
private const val HDPI_VALUE = 240
private const val XHDPI_VALUE = 320
private const val XXHDPI_VALUE = 480
private const val XXXHDPI_VALUE = 640

/**
 * Tries to determine APK split compatibility with a device by examining the list of split names.
 * This only looks on the supported ABIs, the screen density and supported languages.
 * Other config splits e.g. based on OpenGL or Vulkan version are also possible,
 * but don't seem to be widely used, so we don't consider those for now.
 */
class ApkSplitCompatibilityChecker(private val deviceInfo: DeviceInfo) {

    private val abiMap = mapOf(
        "armeabi" to "armeabi",
        "armeabi_v7a" to "armeabi-v7a",
        "arm64_v8a" to "arm64-v8a",
        "x86" to "x86",
        "x86_64" to "x86_64",
        "mips" to "mips",
        "mips64" to "mips64"
    )
    private val densityMap = mapOf(
        "ldpi" to LDPI_VALUE,
        "mdpi" to MDPI_VALUE,
        "tvdpi" to TVDPI_VALUE,
        "hdpi" to HDPI_VALUE,
        "xhdpi" to XHDPI_VALUE,
        "xxhdpi" to XXHDPI_VALUE,
        "xxxhdpi" to XXXHDPI_VALUE
    )

    /**
     * Returns true if the list of splits can be considered compatible with the current device,
     * and false otherwise.
     */
    fun isCompatible(deviceName: String, splitNames: Collection<String>): Boolean {
        val unknownAllowed = deviceInfo.areUnknownSplitsAllowed(deviceName)
        return splitNames.all { splitName ->
            // all individual splits need to be compatible (which can be hard to judge by name only)
            isCompatible(deviceName, unknownAllowed, splitName)
        }
    }

    private fun isCompatible(
        deviceName: String,
        unknownAllowed: Boolean,
        splitName: String,
    ): Boolean {
        val index = splitName.indexOf(CONFIG_PREFIX)
        // If this is not a standardized config split
        if (index == -1 && unknownAllowed) {
            // we assume that it will work, as it is most likely a dynamic feature module
            Log.v(TAG, "Not a config split '$splitName'. Assuming it is ok.")
            return true
        } else if (index != 0 && !unknownAllowed) { // not a normal config split at all
            // we refuse it, since unknown splits are not allowed
            Log.v(TAG, "Not a config split '$splitName'. Not allowed.")
            return false
        }

        val name = splitName.substring(index + CONFIG_LENGTH)

        // Check if this is a known ABI config
        if (abiMap.containsKey(name)) {
            // The ABI split must be supported by the current device
            return isAbiCompatible(name)
        }

        // Check if this is a known screen density config
        densityMap[name]?.let { splitDensity ->
            // The split's density must not be much lower than the device's.
            return isDensityCompatible(splitDensity)
        }

        // Check if this is a language split
        if (deviceInfo.isSupportedLanguage(name)) {
            // accept all language splits as an extra language should not break things
            return true
        }

        // At this point we don't know what to make of that split
        return if (deviceInfo.isSameDevice(deviceName)) {
            // so we only allow it if it came from the same device
            Log.v(TAG, "Unhandled config split '$splitName'. Came from same device.")
            true
        } else {
            Log.w(TAG, "Unhandled config split '$splitName'. Not allowed.")
            false
        }
    }

    private fun isAbiCompatible(name: String): Boolean {
        return if (deviceInfo.supportedABIs.contains(abiMap[name])) {
            Log.v(TAG, "Config split '$name' is supported ABI (${deviceInfo.supportedABIs})")
            true
        } else {
            Log.w(TAG, "Config split '$name' is not supported ABI (${deviceInfo.supportedABIs})")
            false
        }
    }

    private fun isDensityCompatible(splitDensity: Int): Boolean {
        @Suppress("MagicNumber")
        val acceptableDiff = deviceInfo.densityDpi / 3
        return if (deviceInfo.densityDpi - splitDensity > acceptableDiff) {
            Log.w(
                TAG,
                "Config split density $splitDensity not compatible with ${deviceInfo.densityDpi}"
            )
            false
        } else {
            Log.v(
                TAG,
                "Config split density $splitDensity compatible with ${deviceInfo.densityDpi}"
            )
            true
        }
    }

}
