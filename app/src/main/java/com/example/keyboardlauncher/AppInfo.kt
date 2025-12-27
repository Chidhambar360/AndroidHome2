package com.example.keyboardlauncher

import android.content.Intent
import android.graphics.drawable.Drawable

data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable,
    val launchIntent: Intent
)
