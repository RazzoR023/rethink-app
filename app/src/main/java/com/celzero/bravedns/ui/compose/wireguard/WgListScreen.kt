/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.celzero.bravedns.ui.compose.wireguard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.design.EmptyState
import com.celzero.bravedns.ui.compose.design.PreviewSurface
import com.celzero.bravedns.ui.compose.design.RethinkSpacing
import com.celzero.bravedns.ui.compose.design.RethinkStatus
import com.celzero.bravedns.ui.compose.design.SectionDivider
import com.celzero.bravedns.ui.compose.design.SegmentOption
import com.celzero.bravedns.ui.compose.design.SegmentedChoice
import com.celzero.bravedns.ui.compose.design.SettingRow
import com.celzero.bravedns.ui.compose.design.StatusChip
import com.celzero.bravedns.ui.compose.design.ThemePreviews
import com.celzero.bravedns.ui.compose.proxy.ProxyConfirm
import com.celzero.bravedns.ui.compose.proxy.ProxyConfirmDialog

/*
 * The WireGuard config list: Simple / Advanced, one row per config (tap opens the config page,
 * the switch turns it on or off) and an Add button. WgMainActivity does all reads and writes.
 */

/** Observable state of the list; WgMainActivity fills it. */
class WgListState(mode: WgListMode) {
    var mode by mutableStateOf(mode)
    /** null until the first load: neither the list nor the empty state shows */
    var hasConfigs by mutableStateOf<Boolean?>(null)
    var items by mutableStateOf<List<WgListItem>>(emptyList())
    /** "RDNS Default" for "DNS forwarded to …"; null hides the line */
    var dnsTarget by mutableStateOf<String?>(null)
    var vpnOn by mutableStateOf(true)
    var confirm by mutableStateOf<ProxyConfirm?>(null)
    var addSheet by mutableStateOf(false)
}

interface WgListActions {
    fun back()
    fun selectMode(mode: WgListMode)
    fun open(id: Int)
    fun toggle(id: Int, on: Boolean)
    fun importFile()
    fun scanQr()
    fun create()
    fun deleteAll()
}

@Composable
fun WgListScreen(state: WgListState, actions: WgListActions) {
    Scaffold(
        modifier = Modifier.testTag("wg_list"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.lbl_wireguard), Modifier.semantics { heading() }, maxLines = 1) },
                navigationIcon = {
                    IconButton(actions::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.rules_navigate_up)) }
                },
                actions = { if (state.hasConfigs == true) WgListOverflow(actions::deleteAll) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.wg_list_add)) },
                icon = { Icon(Icons.Filled.Add, null) },
                onClick = { state.addSheet = true },
                modifier = Modifier.testTag("wg_add_fab"),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when (state.hasConfigs) {
            null -> Box(Modifier.fillMaxSize().padding(padding))
            false -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Filled.VpnKey,
                    title = stringResource(R.string.wg_list_empty_title),
                    // the Add button is the FAB
                    body = stringResource(R.string.wireguard_no_config_msg),
                )
            }
            true -> WgList(state, actions, padding)
        }
    }

    if (state.addSheet) WgListAddSheet(onDismiss = { state.addSheet = false }, actions)
    state.confirm?.let { c -> ProxyConfirmDialog(c) { state.confirm = null } }
}

@Composable
private fun WgListOverflow(onDeleteAll: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton({ open = true }, Modifier.testTag("wg_list_overflow")) {
            Icon(Icons.Filled.MoreVert, stringResource(R.string.rules_more_options))
        }
        DropdownMenu(open, { open = false }) {
            val color = MaterialTheme.colorScheme.error
            DropdownMenuItem(
                text = { Text(stringResource(R.string.wg_list_delete_all), color = color) },
                leadingIcon = { Icon(Icons.Filled.DeleteForever, null, tint = color) },
                onClick = { open = false; onDeleteAll() },
            )
        }
    }
}

@Composable
private fun WgList(state: WgListState, actions: WgListActions, padding: PaddingValues) {
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        // room for the FAB under the last row
        contentPadding = PaddingValues(bottom = 88.dp),
    ) {
        item(key = "mode") { WgListModeHeader(state, actions::selectMode) }
        if (!state.vpnOn) {
            item(key = "vpn_off") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = RethinkSpacing.screenGutter, vertical = RethinkSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.sm),
                ) {
                    Icon(Icons.Filled.WarningAmber, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    Text(
                        stringResource(R.string.wg_list_vpn_off),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        itemsIndexed(state.items, key = { _, it -> it.id }) { index, item ->
            WgListSectionItem(index, state.items.size) {
                WgListRow(item, mode = state.mode, onOpen = { actions.open(item.id) }, onToggle = { actions.toggle(item.id, it) })
            }
        }
    }
}

/** Rows share one rounded section: first and last item round their outer corners. */
@Composable
private fun WgListSectionItem(index: Int, count: Int, content: @Composable () -> Unit) {
    val r = MaterialTheme.shapes.large
    val zero = androidx.compose.foundation.shape.CornerSize(0.dp)
    val shape = when {
        count == 1 -> r
        index == 0 -> r.copy(bottomStart = zero, bottomEnd = zero)
        index == count - 1 -> r.copy(topStart = zero, topEnd = zero)
        else -> androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
    }
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = RethinkSpacing.md),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(top = if (index == 0) RethinkSpacing.xs else 0.dp, bottom = if (index == count - 1) RethinkSpacing.xs else 0.dp)) {
            if (index > 0) SectionDivider()
            content()
        }
    }
}

@Composable
private fun WgListModeHeader(state: WgListState, onSelect: (WgListMode) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = RethinkSpacing.screenGutter).padding(top = RethinkSpacing.sm, bottom = RethinkSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(RethinkSpacing.sm),
    ) {
        SegmentedChoice(
            listOf(
                SegmentOption(WgListMode.SIMPLE, stringResource(R.string.rt_list_simple_btn_txt)),
                SegmentOption(WgListMode.ADVANCED, stringResource(R.string.lbl_advanced)),
            ),
            selected = state.mode,
            onSelect = { if (it != state.mode) onSelect(it) },
            modifier = Modifier.testTag("wg_list_mode"),
        )
        Text(
            stringResource(if (state.mode == WgListMode.SIMPLE) R.string.wg_list_mode_simple_desc else R.string.wg_list_mode_advanced_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.dnsTarget?.let {
            Text(
                stringResource(R.string.wireguard_disclaimer, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One config. The row opens the config page; the switch is a separate target that turns the
 * config on or off.
 */
@Composable
fun WgListRow(item: WgListItem, mode: WgListMode, onOpen: () -> Unit, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onOpen)
            .heightIn(min = 72.dp)
            .padding(start = RethinkSpacing.screenGutter, end = RethinkSpacing.md, top = RethinkSpacing.md, bottom = RethinkSpacing.md)
            .testTag("wg_row_${item.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.lg),
    ) {
        WgListAvatar(item.flag)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(RethinkSpacing.xs)) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.sm)) {
                if (item.statusText != null) StatusChip(item.status, Modifier.weight(1f, fill = false), label = item.statusText)
                else StatusChip(item.status)
                WgListAppsText(item.apps, item.id)
            }
            val activity = listOfNotNull(item.uptime, item.traffic).joinToString("  ·  ")
            if (activity.isNotEmpty()) {
                Text(activity, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (item.tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.xs), verticalArrangement = Arrangement.spacedBy(RethinkSpacing.xs)) {
                    item.tags.forEach { WgListTagChip(it) }
                }
            }
        }
        val cd = stringResource(R.string.wg_list_toggle_cd, item.name)
        Switch(
            checked = item.checked,
            onCheckedChange = onToggle,
            enabled = !item.busy,
            modifier = Modifier.testTag("wg_switch_${item.id}").semantics { contentDescription = cd },
        )
    }
}

@Composable
private fun WgListAppsText(apps: WgListApps, id: Int) {
    val sep = stringResource(R.string.app_details_summary_separator)
    val idText = stringResource(R.string.proxy_page_wg_id, id)
    val (text, warn) = when (apps) {
        WgListApps.Hidden -> null to false
        WgListApps.All -> stringResource(R.string.wg_list_apps_all) to false
        WgListApps.Remaining -> stringResource(R.string.wg_list_apps_remaining) to false
        is WgListApps.Picked -> (if (apps.count == 0) stringResource(R.string.wg_list_apps_none)
            else pluralStringResource(R.plurals.proxy_page_apps_count, apps.count, apps.count)) to (apps.count == 0)
    }
    Row {
        Text(
            if (text != null) idText + sep else idText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        if (text != null) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun WgListTagChip(tag: WgListTag) {
    val container = if (tag.warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    val content = if (tag.warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        tag.label,
        Modifier.background(container, MaterialTheme.shapes.extraSmall).padding(horizontal = RethinkSpacing.sm - 2.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall,
        color = content,
        maxLines = 1,
    )
}

/** Same avatar as the config page header: endpoint flag, else the WireGuard icon. */
@Composable
private fun WgListAvatar(flag: String?) {
    Surface(Modifier.size(40.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
        Box(contentAlignment = Alignment.Center) {
            if (!flag.isNullOrBlank()) {
                Text(flag, style = MaterialTheme.typography.titleLarge)
            } else {
                Icon(
                    painterResource(R.drawable.ic_wireguard_icon),
                    null,
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun WgListAddSheet(onDismiss: () -> Unit, actions: WgListActions) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = RethinkSpacing.lg).testTag("wg_add_sheet")) {
            Text(
                stringResource(R.string.wg_list_add_title),
                Modifier.padding(horizontal = RethinkSpacing.screenGutter, vertical = RethinkSpacing.sm).semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SettingRow(
                stringResource(R.string.wg_list_add_import),
                Modifier.testTag("wg_add_import"),
                supportingText = stringResource(R.string.wg_list_add_import_desc),
                icon = Icons.Filled.FileOpen,
                onClick = { onDismiss(); actions.importFile() },
            )
            SettingRow(
                stringResource(R.string.wg_list_add_qr),
                Modifier.testTag("wg_add_qr"),
                supportingText = stringResource(R.string.wg_list_add_qr_desc),
                icon = Icons.Filled.QrCodeScanner,
                onClick = { onDismiss(); actions.scanQr() },
            )
            SettingRow(
                stringResource(R.string.wg_list_add_create),
                Modifier.testTag("wg_add_create"),
                supportingText = stringResource(R.string.wg_list_add_create_desc),
                icon = Icons.Filled.Edit,
                onClick = { onDismiss(); actions.create() },
            )
        }
    }
}

private object NoWgListActions : WgListActions {
    override fun back() {}
    override fun selectMode(mode: WgListMode) {}
    override fun open(id: Int) {}
    override fun toggle(id: Int, on: Boolean) {}
    override fun importFile() {}
    override fun scanQr() {}
    override fun create() {}
    override fun deleteAll() {}
}

@ThemePreviews
@Composable
private fun WgListScreenPreview() = PreviewSurface {
    val state = remember {
        WgListState(WgListMode.ADVANCED).apply {
            hasConfigs = true
            dnsTarget = "RDNS Default"
            items = listOf(
                WgListItem(
                    id = 1, name = "Mullvad SE", flag = "🇸🇪", checked = true,
                    status = RethinkStatus.Active, statusText = "Connected (2 min. ago)",
                    apps = WgListApps.Picked(3), uptime = "Active since 4 min. ago", traffic = "6.3 KB ▲  21.5 KB ▼",
                    tags = listOf(WgListTag("IPv4"), WgListTag("Always-on")),
                ),
                WgListItem(
                    id = 2, name = "Home router", checked = true, status = RethinkStatus.Waiting,
                    statusText = "Waiting", apps = WgListApps.Picked(0),
                ),
                WgListItem(id = 3, name = "Work", tags = listOf(WgListTag("Lockdown", warning = true))),
            )
        }
    }
    Box(Modifier.heightIn(max = 640.dp)) { WgListScreen(state, NoWgListActions) }
}

@ThemePreviews
@Composable
private fun WgListEmptyPreview() = PreviewSurface {
    val state = remember { WgListState(WgListMode.SIMPLE).apply { hasConfigs = false } }
    Box(Modifier.heightIn(max = 480.dp)) { WgListScreen(state, NoWgListActions) }
}
