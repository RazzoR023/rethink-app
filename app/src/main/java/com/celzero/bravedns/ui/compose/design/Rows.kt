/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LocalContentColor

/** Alpha Material 3 uses for disabled content. */
internal const val DisabledAlpha = 0.38f

/**
 * Title above a group of rows, with an optional text action on the right (e.g. "See all").
 * The title is marked as a heading so screen readers can jump between sections.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = RethinkSpacing.minTouchTarget)
            .padding(start = RethinkSpacing.screenGutter, end = RethinkSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * A card that groups related rows (like a `<section>` with a rounded background).
 * Put [SettingRow] / [SwitchRow] / [SingleChoiceList] inside; use [SectionDivider] between rows.
 */
@Composable
fun SettingsSection(
    modifier: Modifier = Modifier,
    title: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) SectionHeader(title, actionLabel = actionLabel, onAction = onAction)
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = RethinkSpacing.md),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(Modifier.padding(vertical = RethinkSpacing.xs), content = content)
        }
    }
}

/** A hairline between rows of a [SettingsSection], inset to line up with the text. */
@Composable
fun SectionDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier.padding(horizontal = RethinkSpacing.screenGutter),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** Trailing content helpers for [SettingRow]. */
object SettingTrailing {
    /** A ">" chevron for rows that open another screen. Decorative: no content description. */
    @Composable
    fun Chevron() {
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    /** The current value of a setting, e.g. "AMOLED dark". */
    @Composable
    fun Value(text: String) {
        Text(
            text,
            modifier = Modifier.widthIn(max = 160.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * One settings row: optional leading icon, title, optional supporting text and a trailing slot
 * (nothing, [SettingTrailing.Chevron], [SettingTrailing.Value], a [StatusChip], ...).
 *
 * The whole row is the click target when [onClick] is set; it is at least 56dp high.
 * For an on/off setting use [SwitchRow] instead of putting a Switch in [trailing].
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val interaction = if (onClick != null) {
        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    } else Modifier
    RowLayout(modifier.then(interaction), title, supportingText, icon, enabled, trailing)
}

/**
 * A [SettingRow] with a Switch. The row is one toggleable element (role Switch), so tapping
 * anywhere flips it and screen readers announce "title, switch, on/off".
 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    RowLayout(
        modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        title, supportingText, icon, enabled,
    ) {
        // the row handles input and semantics; the switch only draws the state
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
internal fun RowLayout(
    modifier: Modifier,
    title: String,
    supportingText: String?,
    icon: ImageVector?,
    enabled: Boolean,
    trailing: (@Composable RowScope.() -> Unit)?,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = RethinkSpacing.screenGutter, vertical = RethinkSpacing.md)
            .alpha(if (enabled) 1f else DisabledAlpha),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.lg),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(RethinkSpacing.xxs)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (supportingText != null) {
                Text(supportingText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
            }
        }
    }
}

@ThemePreviews
@Composable
private fun RowsPreview() = PreviewSurface {
    var on by remember { mutableStateOf(true) }
    Box(Modifier.padding(vertical = RethinkSpacing.sm)) {
        SettingsSection(title = "General", actionLabel = "See all", onAction = {}) {
            SettingRow("Theme", icon = Icons.Filled.Palette, onClick = {}) { SettingTrailing.Value("AMOLED dark") }
            SectionDivider()
            SwitchRow("Notifications", on, { on = it }, icon = Icons.Filled.Notifications, supportingText = "Show a persistent notification")
            SectionDivider()
            SettingRow("DNS", supportingText = "RDNS Default", icon = Icons.Filled.Dns, onClick = {}) { SettingTrailing.Chevron() }
            SectionDivider()
            SettingRow("Lockdown", supportingText = "Needs the VPN to be on", icon = Icons.Filled.Shield, enabled = false, onClick = {}) {
                StatusChip(RethinkStatus.Off)
            }
        }
    }
}
