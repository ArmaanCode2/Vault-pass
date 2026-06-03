package com.example.ui.theme

import androidx.compose.ui.graphics.Color

enum class AccentColor(
    val title: String,
    val lightPrimary: Color,
    val lightPrimaryContainer: Color,
    val darkPrimary: Color,
    val darkPrimaryContainer: Color
) {
    BLUE("Blue", Color(0xFF005FB0), Color(0xFFD7E3FF), Color(0xFFA4C8FF), Color(0xFF004786)),
    RED("Red", Color(0xFFB3261E), Color(0xFFF9DEDC), Color(0xFFF2B8B5), Color(0xFF8C1D18)),
    GREEN("Green", Color(0xFF1E8E3E), Color(0xFFE6F4EA), Color(0xFF81C995), Color(0xFF137333)),
    PURPLE("Purple", Color(0xFF6750A4), Color(0xFFEADDFF), Color(0xFFD0BCFF), Color(0xFF4F378B)),
    ORANGE("Orange", Color(0xFFD95D1E), Color(0xFFFFDBCF), Color(0xFFFFB598), Color(0xFF8C2C00))
}
