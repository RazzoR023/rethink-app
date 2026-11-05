/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.design

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.celzero.bravedns.ui.compose.RethinkTheme

/** Renders a preview twice: Light and AMOLED dark. */
@Preview(name = "Light", showBackground = true, backgroundColor = 0xFFFAFAFA, widthDp = 360)
@Preview(
    name = "AMOLED dark",
    showBackground = true,
    backgroundColor = 0xFF000000,
    widthDp = 360,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
)
annotation class ThemePreviews

/** Preview host: picks the theme from the preview's night mode (see [ThemePreviews]). */
@Composable
internal fun PreviewSurface(content: @Composable () -> Unit) {
    RethinkTheme(darkTheme = isSystemInDarkTheme()) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(Modifier.padding(RethinkSpacing.lg)) { content() }
        }
    }
}
