package com.fersaiyan.cyanbridge.devices

import androidx.annotation.DrawableRes
import com.fersaiyan.cyanbridge.R

/**
 * Chapter 3: Device scanning + pairing.
 */
enum class DeviceClass {
    HEY_CYAN,
    META_RAYBAN,
    GENERIC_AUDIO,
    UNKNOWN;

    fun displayName(): String = when (this) {
        // Keep internal enum as HEY_CYAN, but avoid showing vendor name in the UI.
        HEY_CYAN -> "Camera+Audio glasses"
        META_RAYBAN -> "Meta Rayban"
        GENERIC_AUDIO -> "Audio-only glasses"
        UNKNOWN -> "Unknown"
    }

    @DrawableRes
    fun iconRes(): Int = when (this) {
        HEY_CYAN -> R.drawable.ic_device_heycyan
        META_RAYBAN -> R.drawable.ic_device_meta
        GENERIC_AUDIO -> R.drawable.ic_device_generic_audio
        UNKNOWN -> R.drawable.ic_device_unknown
    }
}
