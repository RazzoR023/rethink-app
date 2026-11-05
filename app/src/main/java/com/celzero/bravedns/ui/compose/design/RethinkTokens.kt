/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/*
 * Typography, shape and spacing tokens. Read them through MaterialTheme.typography,
 * MaterialTheme.shapes and RethinkSpacing.
 */

/**
 * Spacing scale (a 4dp grid). Use these instead of literal dp values for padding and gaps.
 *
 * Like CSS custom properties: `Modifier.padding(horizontal = RethinkSpacing.lg)`.
 */
object RethinkSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** Minimum size of anything tappable (Material / Android accessibility guideline). */
    val minTouchTarget = 48.dp

    /** Horizontal padding of screen content and of rows inside a section. */
    val screenGutter = lg
}

/** Corner radii. Cards and sections use [Shapes.large]; chips [Shapes.small]. */
val RethinkShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

// TextAppearance.Rethink.* in res/values/styles.xml: sans-serif (regular) or sans-serif-medium,
// sizes in sp, letter spacing in em, no forced line height (OEM-font safe), font padding kept.
private fun style(sizeSp: Int, medium: Boolean, letterSpacingEm: Float) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = if (medium) FontWeight.Medium else FontWeight.Normal,
    fontSize = sizeSp.sp,
    letterSpacing = letterSpacingEm.em,
    textDirection = TextDirection.Content,
    platformStyle = PlatformTextStyle(includeFontPadding = true),
)

val RethinkTypography = Typography(
    displayLarge = style(57, medium = false, -0.00438596f),
    displayMedium = style(45, medium = false, 0f),
    displaySmall = style(36, medium = false, 0f),
    headlineLarge = style(32, medium = false, 0f),
    headlineMedium = style(28, medium = false, 0f),
    headlineSmall = style(24, medium = false, 0f),
    titleLarge = style(22, medium = true, 0f),
    titleMedium = style(16, medium = true, 0.009375f),
    titleSmall = style(14, medium = true, 0.00714286f),
    bodyLarge = style(16, medium = false, 0.03125f),
    bodyMedium = style(14, medium = false, 0.01785714f),
    bodySmall = style(12, medium = false, 0.03333333f),
    labelLarge = style(14, medium = true, 0.00714286f),
    labelMedium = style(12, medium = true, 0.04166667f),
    labelSmall = style(11, medium = true, 0.04545455f),
)
