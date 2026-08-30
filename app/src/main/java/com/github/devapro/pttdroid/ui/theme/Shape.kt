package com.github.devapro.pttdroid.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Generous, evenly-stepped radii. Everything on the main screen is either a pill or a disc, so
 * the corner scale mostly exists to keep the settings cards from looking like a different app.
 */
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
