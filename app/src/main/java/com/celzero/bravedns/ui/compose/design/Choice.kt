/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * One option of a [SingleChoiceList].
 *
 * @param value what [SingleChoiceList] passes to onSelect.
 * @param disabledReason shown instead of [description] when [enabled] is false.
 * @param trailing optional extra content on the right, e.g. a [StatusChip].
 */
@Immutable
data class ChoiceOption<T>(
    val value: T,
    val title: String,
    val description: String? = null,
    val enabled: Boolean = true,
    val disabledReason: String? = null,
    val trailing: (@Composable () -> Unit)? = null,
)

/**
 * Radio-button list: one option selected at a time ([selected] may be null for "none").
 * Each row is a single selectable element (role RadioButton) inside a selectable group, so
 * screen readers announce "x of n" and the selection state.
 */
@Composable
fun <T> SingleChoiceList(
    options: List<ChoiceOption<T>>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    /** row inset; 0 inside dialogs, which already pad their content */
    horizontalPadding: androidx.compose.ui.unit.Dp = RethinkSpacing.screenGutter,
) {
    Column(modifier.fillMaxWidth().selectableGroup()) {
        options.forEach { option ->
            val isSelected = option.value == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .selectable(
                        selected = isSelected,
                        enabled = option.enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(option.value) },
                    )
                    .padding(horizontal = horizontalPadding, vertical = RethinkSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.md),
            ) {
                // the row handles input; the radio only draws the state
                RadioButton(selected = isSelected, onClick = null, enabled = option.enabled)
                Column(
                    Modifier.weight(1f).alpha(if (option.enabled) 1f else DisabledAlpha),
                    verticalArrangement = Arrangement.spacedBy(RethinkSpacing.xxs),
                ) {
                    Text(option.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    val secondary = if (!option.enabled && option.disabledReason != null) option.disabledReason else option.description
                    if (secondary != null) {
                        Text(secondary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                option.trailing?.invoke()
            }
        }
    }
}

/** One option of a [SegmentedChoice]. */
@Immutable
data class SegmentOption<T>(val value: T, val label: String, val enabled: Boolean = true)

/**
 * A row of 2 to 4 connected buttons, one selected (Material 3 segmented button), for short
 * exclusive choices like "Allowed | Blocked".
 */
@Composable
fun <T> SegmentedChoice(
    options: List<SegmentOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    require(options.size in 2..4) { "SegmentedChoice takes 2 to 4 options, got ${options.size}" }
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option.value == selected,
                onClick = { onSelect(option.value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                enabled = option.enabled,
                label = { Text(option.label, maxLines = 1) },
            )
        }
    }
}

@ThemePreviews
@Composable
private fun ChoicePreview() = PreviewSurface {
    var mode by remember { mutableStateOf("normal") }
    var access by remember { mutableStateOf(true) }
    Column(verticalArrangement = Arrangement.spacedBy(RethinkSpacing.lg)) {
        SegmentedChoice(
            listOf(SegmentOption(true, "Allowed"), SegmentOption(false, "Blocked")),
            selected = access, onSelect = { access = it },
        )
        SettingsSection(title = "Mode") {
            SingleChoiceList(
                listOf(
                    ChoiceOption("normal", "Normal", "Firewall and DNS rules apply"),
                    ChoiceOption("isolate", "Isolate", "Only trusted IPs and domains"),
                    ChoiceOption("exclude", "Exclude from VPN", enabled = false, disabledReason = "Not available in lockdown"),
                ),
                selected = mode, onSelect = { mode = it },
            )
        }
    }
}
