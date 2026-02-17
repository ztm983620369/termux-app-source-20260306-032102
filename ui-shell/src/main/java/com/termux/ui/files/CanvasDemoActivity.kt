package com.termux.ui.files

import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

class CanvasDemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color(0xFF15161A).toArgb()
        window.navigationBarColor = Color(0xFF0E0F12).toArgb()
        setContent {
            CanvasPlaceholderOptimized.Screen(onBack = { finish() })
        }
    }
}
