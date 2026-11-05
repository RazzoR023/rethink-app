/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * Colour tokens for the two app themes. Accent, text and surface values come from
 * res/values/colors.xml as used by the XML styles AppThemeWhite and AppThemeTrueBlack
 * (named in the comments); container colours that the XML themes did not define well
 * (or defined with poor contrast) are derived from those accents.
 *
 * New code reads these only through MaterialTheme.colorScheme and MaterialTheme.statusColors.
 */

/** Light: AppThemeWhite. */
val RethinkLightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF005CB2), // accentGoodLight
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E4FF),
    onPrimaryContainer = Color(0xFF001C3A),
    inversePrimary = Color(0xFF4FC3F7), // accentGoodBlack
    secondary = Color(0xFF1E88E5), // colorPrimaryDarkLight
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE9F9),
    onSecondaryContainer = Color(0xFF0B2440),
    tertiary = Color(0xFF895601), // accentWarningLight
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDDB5),
    onTertiaryContainer = Color(0xFF2C1700),
    error = Color(0xFFD12600), // accentBadLight
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD3),
    onErrorContainer = Color(0xFF3E0500),
    background = Color(0xFFFAFAFA), // colorSurfaceLight
    onBackground = Color(0xFF1C1B1F), // primaryTextLight
    surface = Color(0xFFFAFAFA), // colorSurfaceLight
    onSurface = Color(0xFF1C1B1F), // primaryTextLight
    surfaceVariant = Color(0xFFEEEEEE), // colorSurfaceVariantLight
    onSurfaceVariant = Color(0xFF49454F), // primaryLightColorTextLight
    surfaceTint = Color(0xFF005CB2),
    inverseSurface = Color(0xFF313033),
    inverseOnSurface = Color(0xFFF4EFF4),
    outline = Color(0xFF757575), // borderLight, opaque
    outlineVariant = Color(0xFFD0D0D0),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFAFAFA),
    surfaceDim = Color(0xFFE0E0E0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F6F6),
    surfaceContainer = Color(0xFFF3F3F3), // colorSurfaceContainerLight
    surfaceContainerHigh = Color(0xFFEEEEEE), // colorSurfaceVariantLight
    surfaceContainerHighest = Color(0xFFE8E8E8),
)

/** AMOLED dark: AppThemeTrueBlack accents on a pure black background. */
val RethinkAmoledColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF4FC3F7), // accentGoodBlack
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF003A55),
    onPrimaryContainer = Color(0xFFC7EBFF),
    inversePrimary = Color(0xFF005CB2), // accentGoodLight
    secondary = Color(0xFF4FC3F7), // colorSecondaryBlack
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF282525), // selectedCardBgBlack
    onSecondaryContainer = Color(0xFFF2F2F2), // primaryTextBlack
    tertiary = Color(0xFFFFD149), // accentWarningBlack, opaque
    onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFF3D3000),
    onTertiaryContainer = Color(0xFFFFE08C),
    error = Color(0xFFFF4081), // accentBadBlack
    onError = Color(0xFF000000),
    errorContainer = Color(0xFF4A0E24),
    onErrorContainer = Color(0xFFFFD9E2),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF2F2F2), // primaryTextBlack
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF2F2F2), // primaryTextBlack
    surfaceVariant = Color(0xFF1C1C1C), // colorSurfaceVariantBlack
    onSurfaceVariant = Color(0xFFBDBDBD), // primaryLightColorTextBlack (75% white) on black
    surfaceTint = Color(0xFF4FC3F7),
    inverseSurface = Color(0xFFE6E6E6),
    inverseOnSurface = Color(0xFF1C1C1C),
    outline = Color(0xFF8A8A8A), // borderBlack, opaque
    outlineVariant = Color(0xFF333333),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF2A2A2A),
    surfaceDim = Color(0xFF000000),
    // cards sit a few steps above black
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0B0B0B),
    surfaceContainer = Color(0xFF121212), // colorSurfaceBlack
    surfaceContainerHigh = Color(0xFF1C1C1C), // colorSurfaceVariantBlack
    surfaceContainerHighest = Color(0xFF262626),
)

/**
 * Status colours Material 3 has no slot for. "Waiting" uses the theme's tertiary (amber) and
 * "error" its error colour, so only the positive (up / active) pair is new.
 */
@Immutable
data class RethinkStatusColors(
    val active: Color,
    val activeContainer: Color,
    val onActiveContainer: Color,
    val waiting: Color,
    val waitingContainer: Color,
    val onWaitingContainer: Color,
    val off: Color,
    val offContainer: Color,
    val onOffContainer: Color,
    val error: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
)

val RethinkLightStatusColors = RethinkStatusColors(
    active = Color(0xFF2E7D32), // between chipTextPositiveLight and accentGood greens
    activeContainer = Color(0xFFDCEFDF), // chipBgPositiveLight on white, opaque
    onActiveContainer = Color(0xFF005000), // chipTextPositiveLight
    waiting = RethinkLightColorScheme.tertiary,
    waitingContainer = RethinkLightColorScheme.tertiaryContainer,
    onWaitingContainer = RethinkLightColorScheme.onTertiaryContainer,
    off = Color(0xFF8A8A8A),
    offContainer = RethinkLightColorScheme.surfaceContainerHigh,
    onOffContainer = RethinkLightColorScheme.onSurfaceVariant,
    error = RethinkLightColorScheme.error,
    errorContainer = RethinkLightColorScheme.errorContainer,
    onErrorContainer = RethinkLightColorScheme.onErrorContainer,
)

val RethinkAmoledStatusColors = RethinkStatusColors(
    active = Color(0xFF1DE9B6), // chipTextPositiveBlack, opaque
    activeContainer = Color(0xFF0B2E25),
    onActiveContainer = Color(0xFFA7F5DD),
    waiting = RethinkAmoledColorScheme.tertiary,
    waitingContainer = RethinkAmoledColorScheme.tertiaryContainer,
    onWaitingContainer = RethinkAmoledColorScheme.onTertiaryContainer,
    off = Color(0xFF7A7A7A),
    offContainer = RethinkAmoledColorScheme.surfaceContainerHigh,
    onOffContainer = RethinkAmoledColorScheme.onSurfaceVariant,
    error = RethinkAmoledColorScheme.error,
    errorContainer = RethinkAmoledColorScheme.errorContainer,
    onErrorContainer = RethinkAmoledColorScheme.onErrorContainer,
)

internal val LocalRethinkStatusColors = staticCompositionLocalOf { RethinkLightStatusColors }

/** Status colours of the current theme: `MaterialTheme.statusColors.active`. */
val MaterialTheme.statusColors: RethinkStatusColors
    @Composable @ReadOnlyComposable get() = LocalRethinkStatusColors.current
