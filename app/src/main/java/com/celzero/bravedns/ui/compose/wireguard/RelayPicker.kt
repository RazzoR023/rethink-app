/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.wireguard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.design.ChoiceOption
import com.celzero.bravedns.ui.compose.design.PreviewSurface
import com.celzero.bravedns.ui.compose.design.RethinkSpacing
import com.celzero.bravedns.ui.compose.design.RethinkStatus
import com.celzero.bravedns.ui.compose.design.SingleChoiceList
import com.celzero.bravedns.ui.compose.design.StatusChip
import com.celzero.bravedns.ui.compose.design.ThemePreviews

/** One WireGuard config this config can be relayed through. */
@Immutable
data class RelayOption(
    val id: Int,
    val name: String,
    val active: Boolean,
    val ip4: Boolean,
    val ip6: Boolean,
    /** mobile-only / SSID configs can't carry a relay */
    val restricted: Boolean = false,
)

/** What the relay sheet shows. [selected] is [RelayLogic.DIRECT] when not relayed. */
@Immutable
data class RelayPickerUi(
    val srcName: String,
    val options: List<RelayOption>,
    val selected: Int = RelayLogic.DIRECT,
    /** id being applied (also [RelayLogic.DIRECT] while removing); null when idle */
    val applying: Int? = null,
    val error: String? = null,
)

object RelayLogic {
    const val DIRECT = -1

    /** whether a tap on [id] should start a change */
    fun canPick(ui: RelayPickerUi, id: Int): Boolean {
        if (ui.applying != null) return false // one change at a time
        if (id == ui.selected) return false // nothing to do
        if (id == DIRECT) return true
        val o = ui.options.firstOrNull { it.id == id } ?: return false
        return !o.restricted
    }

    fun startApplying(ui: RelayPickerUi, id: Int): RelayPickerUi = ui.copy(applying = id, error = null)

    /**
     * Result of a change. [current] is the relay actually in place afterwards (re-read from the
     * hop manager), so a failed switch never leaves a radio on a relay that isn't set.
     */
    fun finish(ui: RelayPickerUi, current: Int, error: String?): RelayPickerUi =
        ui.copy(selected = current, applying = null, error = error)

    fun selectedName(ui: RelayPickerUi): String? =
        ui.options.firstOrNull { it.id == ui.selected }?.name
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayPickerSheet(ui: RelayPickerUi, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        RelayPickerContent(ui, onPick, Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding())
    }
}

@Composable
fun RelayPickerContent(ui: RelayPickerUi, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(bottom = RethinkSpacing.lg).testTag("relay_sheet")) {
        Column(
            Modifier.padding(horizontal = RethinkSpacing.screenGutter, vertical = RethinkSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(RethinkSpacing.xxs),
        ) {
            Text(
                stringResource(R.string.relay_picker_title),
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(R.string.relay_picker_subtitle, ui.srcName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (ui.applying != null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = RethinkSpacing.screenGutter))
        }
        if (ui.error != null) {
            Surface(
                Modifier.fillMaxWidth().padding(horizontal = RethinkSpacing.lg, vertical = RethinkSpacing.xs),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Text(
                    ui.error,
                    Modifier.padding(RethinkSpacing.md),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        val busy = ui.applying != null
        val options = buildList {
            add(
                ChoiceOption(
                    value = RelayLogic.DIRECT,
                    title = stringResource(R.string.relay_picker_direct),
                    description = stringResource(R.string.relay_picker_direct_desc),
                    enabled = !busy,
                )
            )
            ui.options.forEach { o ->
                add(
                    ChoiceOption(
                        value = o.id,
                        title = o.name,
                        description = relayDescription(o),
                        enabled = !busy && !o.restricted,
                        disabledReason = if (o.restricted) stringResource(R.string.hop_error_toast_msg_3) else null,
                        trailing = { StatusChip(if (o.active) RethinkStatus.Active else RethinkStatus.Off) },
                    )
                )
            }
        }
        // while a change is in flight the radio already sits on the target, so taps feel instant
        SingleChoiceList(options, ui.applying ?: ui.selected, onPick)
    }
}

@Composable
private fun relayDescription(o: RelayOption): String {
    val family = when {
        o.ip4 && o.ip6 -> stringResource(R.string.relay_picker_ip46)
        o.ip4 -> stringResource(R.string.settings_ip_text_ipv4)
        o.ip6 -> stringResource(R.string.settings_ip_text_ipv6)
        else -> null
    }
    val id = stringResource(R.string.relay_picker_id, o.id)
    return if (family != null) "$id · $family" else id
}

@ThemePreviews
@Composable
private fun RelayPickerPreview() = PreviewSurface {
    RelayPickerContent(
        RelayPickerUi(
            srcName = "sing",
            options = listOf(
                RelayOption(2, "Neth", active = true, ip4 = true, ip6 = false),
                RelayOption(3, "Home", active = false, ip4 = true, ip6 = true, restricted = true),
            ),
            selected = 2,
        ),
        onPick = {},
    )
}

@ThemePreviews
@Composable
private fun RelayPickerBusyPreview() = PreviewSurface {
    RelayPickerContent(
        RelayPickerUi(
            srcName = "sing",
            options = listOf(RelayOption(2, "Neth", active = true, ip4 = true, ip6 = false)),
            applying = 2,
            error = "Neth can't route sing's endpoint",
        ),
        onPick = {},
    )
}
