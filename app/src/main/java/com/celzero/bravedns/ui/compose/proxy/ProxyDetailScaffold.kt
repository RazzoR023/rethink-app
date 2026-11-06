/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
@file:OptIn(ExperimentalMaterial3Api::class)

package com.celzero.bravedns.ui.compose.proxy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.design.RethinkSpacing
import com.celzero.bravedns.ui.compose.design.SectionDivider
import com.celzero.bravedns.ui.compose.design.SettingRow
import com.celzero.bravedns.ui.compose.design.SettingTrailing
import com.celzero.bravedns.ui.compose.design.SettingsSection
import com.celzero.bravedns.ui.compose.design.StatusChip

/*
 * The layout every proxy page shares (WireGuard config, SOCKS5, HTTP, Orbot): a top bar, the
 * header (name, type, live status, on/off switch), then the page's sections in the same order
 * everywhere: Configuration, Apps, IP and domain rules, then the type's extras. Built only from
 * the design system; each page keeps its own state and I/O.
 */

/** An entry of the top bar's overflow menu. */
class ProxyMenuAction(val label: String, val icon: ImageVector, val destructive: Boolean = false, val onClick: () -> Unit)

@Composable
fun ProxyDetailScaffold(
    title: String,
    header: ProxyHeaderUi,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    menu: List<ProxyMenuAction> = emptyList(),
    /** lines under the status chip (lockdown notes, ...) */
    headerNotes: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier.testTag("proxy_page"),
        topBar = {
            TopAppBar(
                title = { Text(title, Modifier.semantics { heading() }, maxLines = 1) },
                navigationIcon = {
                    IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.rules_navigate_up)) }
                },
                actions = { if (menu.isNotEmpty()) OverflowMenu(menu) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = RethinkSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(RethinkSpacing.lg),
        ) {
            ProxyHeader(header, onToggle, headerNotes)
            content()
        }
    }
}

@Composable
private fun OverflowMenu(menu: List<ProxyMenuAction>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton({ open = true }, Modifier.testTag("proxy_overflow")) {
            Icon(Icons.Filled.MoreVert, stringResource(R.string.rules_more_options))
        }
        DropdownMenu(open, { open = false }) {
            menu.forEach { m ->
                val color = if (m.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                DropdownMenuItem(
                    text = { Text(m.label, color = color) },
                    leadingIcon = { Icon(m.icon, null, tint = color) },
                    onClick = { open = false; m.onClick() },
                )
            }
        }
    }
}

@Composable
fun ProxyHeader(header: ProxyHeaderUi, onToggle: (Boolean) -> Unit, notes: @Composable ColumnScope.() -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().padding(start = RethinkSpacing.screenGutter, end = RethinkSpacing.lg, top = RethinkSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(RethinkSpacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.lg)) {
            Surface(Modifier.size(48.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Box(contentAlignment = Alignment.Center) {
                    if (!header.flag.isNullOrBlank()) {
                        Text(header.flag, style = MaterialTheme.typography.headlineSmall)
                    } else {
                        Icon(
                            painterResource(typeIcon(header.type)),
                            null,
                            Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(RethinkSpacing.xxs)) {
                Text(
                    header.name,
                    Modifier.testTag("proxy_name"),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val type = stringResource(typeLabel(header.type))
                SelectionContainer {
                    Text(
                        listOfNotNull(type, header.detail).joinToString(stringResource(R.string.app_details_summary_separator)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (header.toggleable) {
                val cd = stringResource(R.string.proxy_page_enable_cd, header.name)
                Switch(
                    checked = header.enabled,
                    onCheckedChange = onToggle,
                    enabled = !header.busy,
                    modifier = Modifier.testTag("proxy_enable").semantics { contentDescription = cd },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (header.statusText != null) StatusChip(header.status, Modifier.testTag("proxy_status"), label = header.statusText)
            else StatusChip(header.status, Modifier.testTag("proxy_status"))
        }
        notes()
    }
}

/** A short note under the header or inside a section (lockdown, conflicts, ...). */
@Composable
fun ProxyNote(text: String, modifier: Modifier = Modifier, warning: Boolean = false, onClick: (() -> Unit)? = null) {
    val color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    if (onClick != null) {
        TextButton(onClick, modifier) { Text(text, style = MaterialTheme.typography.bodyMedium) }
    } else {
        Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

/**
 * The Apps section: either "All apps" with why, or the number of picked apps opening the
 * shared per-proxy picker. [top] goes first (e.g. SOCKS5's All apps / Selected apps choice).
 */
@Composable
fun ProxyAppsSection(
    apps: ProxyAppsUi,
    onOpenApps: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    top: @Composable ColumnScope.() -> Unit = {},
) {
    SettingsSection(modifier.testTag("proxy_apps"), title = stringResource(R.string.proxy_page_section_apps)) {
        top()
        when (apps) {
            is ProxyAppsUi.AllApps -> SettingRow(
                title = stringResource(R.string.lbl_all_apps),
                supportingText = stringResource(apps.reason),
                icon = Icons.Filled.Apps,
            )
            is ProxyAppsUi.Picked -> {
                SettingRow(
                    title = stringResource(R.string.proxy_page_apps_title),
                    modifier = Modifier.testTag("proxy_apps_row"),
                    supportingText = if (apps.count == 0) stringResource(R.string.proxy_page_apps_none)
                        else pluralStringResource(R.plurals.proxy_page_apps_count, apps.count, apps.count),
                    icon = Icons.Filled.Apps,
                    enabled = enabled,
                    onClick = onOpenApps,
                ) { SettingTrailing.Chevron() }
                if (apps.warnNone) {
                    Row(
                        Modifier.padding(horizontal = RethinkSpacing.screenGutter, vertical = RethinkSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.sm),
                    ) {
                        Icon(Icons.Filled.WarningAmber, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        Text(
                            stringResource(R.string.proxy_page_apps_warn_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

/** A text field inside a section, inset like the rows. */
@Composable
fun ProxyTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: Int? = null,
    keyboardType: KeyboardType = KeyboardType.Uri,
    password: Boolean = false,
    supporting: String? = null,
) {
    val errorText = error?.let { stringResource(it) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        isError = errorText != null,
        supportingText = (errorText ?: supporting)?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
    )
}

/** "Unsaved changes" + Revert / Save under a form. */
@Composable
fun ProxyFormActions(dirty: Boolean, onRevert: () -> Unit, onSave: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = RethinkSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (dirty) stringResource(R.string.socks5_unsaved) else "",
            Modifier.weight(1f).padding(start = RethinkSpacing.sm),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onRevert, enabled = dirty, modifier = Modifier.testTag("proxy_form_revert")) { Text(stringResource(R.string.socks5_revert)) }
        TextButton(onSave, enabled = dirty, modifier = Modifier.testTag("proxy_form_save")) { Text(stringResource(R.string.lbl_save)) }
    }
}

/** A labelled, selectable value in the Configuration section (public key, DNS, exit IP). */
@Composable
fun ProxyValueRow(label: String, value: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    SettingRow(title = label, modifier = modifier, supportingText = value, onClick = onClick)
}

@Composable
fun ProxySectionDivider() = SectionDivider()

internal fun typeLabel(type: ProxyType): Int = when (type) {
    ProxyType.WIREGUARD -> R.string.proxy_page_type_wireguard
    ProxyType.SOCKS5 -> R.string.proxy_page_type_socks5
    ProxyType.HTTP -> R.string.proxy_page_type_http
    ProxyType.ORBOT -> R.string.proxy_page_type_orbot
}

internal fun typeIcon(type: ProxyType): Int = when (type) {
    ProxyType.WIREGUARD -> R.drawable.ic_wireguard_icon
    ProxyType.SOCKS5 -> R.drawable.ic_socks5
    ProxyType.HTTP -> R.drawable.ic_http
    ProxyType.ORBOT -> R.drawable.ic_orbot
}

@Composable
internal fun appsCountLabel(count: Int): String = pluralStringResource(R.plurals.proxy_page_apps_count, count, count)

/**
 * A plain confirm / info dialog owned by a proxy page (delete, duplicate keys, permissions).
 * [onDismiss] runs on cancel too. [neutralLabel] adds a third button left of the others.
 */
class ProxyConfirm(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val onConfirm: () -> Unit,
    val dismissLabel: String? = null,
    val onDismiss: () -> Unit = {},
    val neutralLabel: String? = null,
    val onNeutral: () -> Unit = {},
    val destructive: Boolean = false,
    val cancelable: Boolean = true,
)

@Composable
fun ProxyConfirmDialog(confirm: ProxyConfirm, close: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (confirm.cancelable) { close(); confirm.onDismiss() } },
        modifier = Modifier.testTag("proxy_confirm"),
        title = { Text(confirm.title) },
        text = { Text(confirm.message, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton({ close(); confirm.onConfirm() }, Modifier.testTag("proxy_confirm_ok")) {
                Text(
                    confirm.confirmLabel,
                    color = if (confirm.destructive) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified,
                )
            }
        },
        dismissButton = {
            Row {
                confirm.neutralLabel?.let { TextButton({ close(); confirm.onNeutral() }) { Text(it) } }
                confirm.dismissLabel?.let { TextButton({ close(); confirm.onDismiss() }) { Text(it) } }
            }
        },
    )
}
