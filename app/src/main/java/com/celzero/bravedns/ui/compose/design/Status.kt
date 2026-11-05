/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R

/** A live state shown by [StatusDot] / [StatusChip]. */
enum class RethinkStatus { Active, Waiting, Off, Error }

/** Default user-facing label of a status ("Up", "Waiting", "Off", "Error"). */
@Composable
@ReadOnlyComposable
fun RethinkStatus.label(): String = stringResource(
    when (this) {
        RethinkStatus.Active -> R.string.design_status_up
        RethinkStatus.Waiting -> R.string.design_status_waiting
        RethinkStatus.Off -> R.string.design_status_off
        RethinkStatus.Error -> R.string.design_status_error
    }
)

private data class StatusPalette(val dot: Color, val container: Color, val content: Color)

@Composable
@ReadOnlyComposable
private fun RethinkStatus.palette(): StatusPalette {
    val c = MaterialTheme.statusColors
    return when (this) {
        RethinkStatus.Active -> StatusPalette(c.active, c.activeContainer, c.onActiveContainer)
        RethinkStatus.Waiting -> StatusPalette(c.waiting, c.waitingContainer, c.onWaitingContainer)
        RethinkStatus.Off -> StatusPalette(c.off, c.offContainer, c.onOffContainer)
        RethinkStatus.Error -> StatusPalette(c.error, c.errorContainer, c.onErrorContainer)
    }
}

/**
 * A small status dot. "Off" is drawn hollow so the state does not rely on colour alone.
 * Pass [contentDescription] when the dot is shown without a text label next to it.
 */
@Composable
fun StatusDot(
    status: RethinkStatus,
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
    contentDescription: String? = null,
) {
    val color = status.palette().dot
    val shape = Modifier.size(size).let {
        if (status == RethinkStatus.Off) it.border(1.5.dp, color, CircleShape)
        else it.background(color, CircleShape)
    }
    Box(
        modifier.then(shape).then(
            if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription }
            else Modifier
        )
    )
}

/** A pill with a [StatusDot] and a label; read by screen readers as "Status: <label>". */
@Composable
fun StatusChip(
    status: RethinkStatus,
    modifier: Modifier = Modifier,
    label: String = status.label(),
) {
    val palette = status.palette()
    val description = stringResource(R.string.design_status_cd, label)
    Row(
        modifier
            .background(palette.container, MaterialTheme.shapes.small)
            .padding(horizontal = RethinkSpacing.sm, vertical = RethinkSpacing.xxs)
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.xs),
    ) {
        StatusDot(status, size = 8.dp)
        Text(label, style = MaterialTheme.typography.labelMedium, color = palette.content, maxLines = 1)
    }
}

@ThemePreviews
@Composable
private fun StatusPreview() = PreviewSurface {
    Column(verticalArrangement = Arrangement.spacedBy(RethinkSpacing.sm)) {
        RethinkStatus.entries.forEach { status ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.md)) {
                StatusDot(status)
                StatusChip(status)
            }
        }
    }
}
