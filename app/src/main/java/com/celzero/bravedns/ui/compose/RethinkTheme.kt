/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose

import com.celzero.bravedns.ui.compose.resources.obtainOrderedStyledAttributes

import android.content.Context
import androidx.annotation.AttrRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.celzero.bravedns.ui.compose.design.LocalRethinkStatusColors
import com.celzero.bravedns.ui.compose.design.RethinkAmoledColorScheme
import com.celzero.bravedns.ui.compose.design.RethinkAmoledStatusColors
import com.celzero.bravedns.ui.compose.design.RethinkLightColorScheme
import com.celzero.bravedns.ui.compose.design.RethinkLightStatusColors
import com.celzero.bravedns.ui.compose.design.RethinkShapes
import com.celzero.bravedns.ui.compose.design.RethinkTypography
import com.google.android.material.R as MaterialR

/**
 * Legacy: reads a colour attribute of the host's XML theme (AppThemeWhite / AppThemeTrueBlack
 * or a bottom-sheet theme). Only the ported screens use this; new code must use
 * MaterialTheme.colorScheme / MaterialTheme.statusColors instead.
 */
@Composable
fun themeColor(@AttrRes attribute: Int): Color {
    LocalConfiguration.current
    val values = LocalContext.current.obtainOrderedStyledAttributes(intArrayOf(attribute))
    return try {
        check(values.hasValue(0)) { "Required Rethink theme color is missing: $attribute" }
        Color(values.getColor(0, android.graphics.Color.TRANSPARENT))
    } finally { values.recycle() }
}

/**
 * The app's Material 3 theme, Light or AMOLED dark, matching the XML theme the host
 * Activity / dialog applied (see util/Themes.kt), so Compose and XML always agree.
 */
@Composable
fun RethinkTheme(content: @Composable () -> Unit) {
    LocalConfiguration.current
    val fallback = isSystemInDarkTheme()
    val dark = LocalContext.current.isDarkXmlTheme() ?: fallback
    RethinkTheme(darkTheme = dark, content = content)
}

/**
 * The app's Material 3 theme with an explicit choice: [darkTheme] true is AMOLED dark.
 * Use it for previews and galleries; screens use the no-argument overload.
 */
@Composable
fun RethinkTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalRethinkStatusColors provides
            if (darkTheme) RethinkAmoledStatusColors else RethinkLightStatusColors
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) RethinkAmoledColorScheme else RethinkLightColorScheme,
            typography = RethinkTypography,
            shapes = RethinkShapes,
            content = content,
        )
    }
}

/** True if the context's XML theme has a dark surface; null if it has no colorSurface. */
private fun Context.isDarkXmlTheme(): Boolean? {
    val values = obtainOrderedStyledAttributes(intArrayOf(MaterialR.attr.colorSurface))
    return try {
        if (!values.hasValue(0)) null
        else Color(values.getColor(0, android.graphics.Color.WHITE)).luminance() < 0.5f
    } finally { values.recycle() }
}
