package com.fersaiyan.cyanbridge.ui.localagent

import android.graphics.drawable.Drawable

data class BlacklistAppItem(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isSystemApp: Boolean,
)
