package com.aplusconnect.companion.ui

import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The colours of every outlined text field in the app.
 *
 * Used by the rename and quick-text dialogs in the screen, and by the
 * pairing fields in the settings tab, so it lives in one place.
 */
@Composable
internal fun outlinedFieldColors(borderColor: Color, textPrimary: Color, textSecondary: Color) =
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = ColorOSGreen,
        unfocusedBorderColor = borderColor,
        focusedLabelColor = ColorOSGreen,
        unfocusedLabelColor = textSecondary,
        cursorColor = ColorOSGreen,
        focusedTextColor = textPrimary,
        unfocusedTextColor = textPrimary
    )
