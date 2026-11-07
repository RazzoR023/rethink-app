/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.celzero.bravedns.ui.compose.wireguard

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.appdetails.DrawableImage
import com.celzero.bravedns.ui.compose.design.EmptyState
import com.celzero.bravedns.ui.compose.design.PreviewSurface
import com.celzero.bravedns.ui.compose.design.RethinkSpacing
import com.celzero.bravedns.ui.compose.design.ThemePreviews
import com.celzero.bravedns.ui.compose.proxy.ProxyConfirm
import com.celzero.bravedns.ui.compose.proxy.ProxyConfirmDialog

/*
 * The per-proxy app picker: "Apps via <proxy>" with search, All / Selected / Unselected chips and
 * a flat list of toggleable app rows. Select all / Unselect all live in the overflow menu behind a
 * confirmation. WgIncludeAppsActivity owns the I/O and implements [AppPickerActions].
 */

/** Observable state of the picker; the activity writes it, the screen reads it. */
class AppPickerState(query: String = "", filter: AppPickerFilter = AppPickerFilter.ALL) {
    var query by mutableStateOf(query)
    var filter by mutableStateOf(filter)
    /** apps on this proxy (live from the db) */
    var selectedCount by mutableIntStateOf(0)
    var bulk by mutableStateOf(AppPickerBulk.None)
    /** the app list is being re-synced with the package manager */
    var refreshing by mutableStateOf(false)
    /** a select-all / unselect-all is being written */
    var bulkBusy by mutableStateOf(false)
    /** bumped after every change so visible rows reload their proxy state */
    var version by mutableIntStateOf(0)
        private set
    var confirm by mutableStateOf<ProxyConfirm?>(null)

    fun changed() { version++ }
}

interface AppPickerActions {
    fun back()
    fun search(query: String)
    fun filter(filter: AppPickerFilter)
    fun refreshApps()
    fun toggle(row: AppPickerRow)
    fun selectAll()
    fun unselectAll()
}

/**
 * The list is given as an index-based source so the screen works with paging (the activity's
 * LazyPagingItems) and with plain lists (previews): [rowKey] must be stable, [rowAt] may return
 * null for a placeholder that is still loading.
 */
class AppPickerRows(
    // read lazily: the paged list's key map re-runs on its own when the data set is swapped
    // (filter change), and a count captured at composition time is stale by then (index OOB)
    val count: () -> Int,
    val loading: Boolean,
    val rowKey: (Int) -> Any,
    val rowAt: @Composable (Int) -> AppPickerRow?,
)

@Composable
fun AppPickerScreen(proxyName: String, state: AppPickerState, rows: AppPickerRows, actions: AppPickerActions) {
    Scaffold(
        modifier = Modifier.testTag("app_picker"),
        topBar = { AppPickerTopBar(proxyName, state, actions) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            AppPickerSearch(state, actions)
            AppPickerFilters(state, actions)
            if (state.bulkBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            else Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (!rows.loading && rows.count() == 0) {
                    AppPickerEmptyState(proxyName, state, Modifier.align(Alignment.TopCenter).padding(top = RethinkSpacing.xl))
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize().testTag("app_picker_list"),
                        contentPadding = PaddingValues(bottom = RethinkSpacing.lg),
                    ) {
                        items(rows.count(), key = rows.rowKey) { index ->
                            val row = rows.rowAt(index)
                            if (row == null) Spacer(Modifier.fillMaxWidth().heightIn(min = 72.dp))
                            else AppPickerRowItem(row, onToggle = actions::toggle)
                        }
                    }
                }
            }
        }
    }
    state.confirm?.let { ProxyConfirmDialog(it) { state.confirm = null } }
}

@Composable
private fun AppPickerTopBar(proxyName: String, state: AppPickerState, actions: AppPickerActions) {
    TopAppBar(
        title = {
            Column {
                Text(
                    stringResource(R.string.app_picker_title, proxyName),
                    Modifier.semantics { heading() },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.app_picker_selected_count, state.selectedCount),
                    Modifier.testTag("app_picker_count"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        },
        navigationIcon = {
            IconButton(actions::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.rules_navigate_up)) }
        },
        actions = {
            if (state.refreshing) {
                Box(Modifier.size(RethinkSpacing.minTouchTarget), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            } else {
                IconButton(actions::refreshApps, Modifier.testTag("app_picker_refresh")) {
                    Icon(Icons.Filled.Refresh, stringResource(R.string.app_picker_refresh))
                }
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton({ menu = true }, Modifier.testTag("app_picker_overflow")) {
                    Icon(Icons.Filled.MoreVert, stringResource(R.string.rules_more_options))
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.app_picker_select_all)) },
                        leadingIcon = { Icon(Icons.Filled.CheckBox, null) },
                        enabled = state.bulk.canSelectAll && !state.bulkBusy,
                        onClick = { menu = false; actions.selectAll() },
                        modifier = Modifier.testTag("app_picker_select_all"),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.app_picker_unselect_all)) },
                        leadingIcon = { Icon(Icons.Filled.CheckBoxOutlineBlank, null) },
                        enabled = state.bulk.canUnselectAll && !state.bulkBusy,
                        onClick = { menu = false; actions.unselectAll() },
                        modifier = Modifier.testTag("app_picker_unselect_all"),
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
private fun AppPickerSearch(state: AppPickerState, actions: AppPickerActions) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = state.query,
        onValueChange = actions::search,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = RethinkSpacing.lg, vertical = RethinkSpacing.sm)
            .testTag("app_picker_search"),
        placeholder = { Text(stringResource(R.string.app_picker_search_hint)) },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = {
            if (state.query.isNotEmpty()) {
                IconButton({ actions.search("") }) { Icon(Icons.Filled.Close, stringResource(R.string.app_picker_search_clear)) }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
    )
}

@Composable
private fun AppPickerFilters(state: AppPickerState, actions: AppPickerActions) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = RethinkSpacing.lg),
        horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.sm),
    ) {
        AppPickerFilter.entries.forEach { f ->
            val selected = state.filter == f
            FilterChip(
                selected = selected,
                onClick = { actions.filter(f) },
                label = { Text(stringResource(filterLabel(f))) },
                leadingIcon = if (selected) ({ Icon(Icons.Filled.Done, null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
                modifier = Modifier.testTag("app_picker_filter_${f.name.lowercase()}"),
            )
        }
    }
}

private fun filterLabel(f: AppPickerFilter) = when (f) {
    AppPickerFilter.ALL -> R.string.lbl_all
    AppPickerFilter.SELECTED -> R.string.rt_filter_parent_selected
    AppPickerFilter.UNSELECTED -> R.string.lbl_unselected
}

/**
 * One app: icon, name (dimmed without the INTERNET permission), a supporting line explaining any
 * non-default state, and a checkbox. The whole row is one toggleable element (role Checkbox).
 */
@Composable
fun AppPickerRowItem(row: AppPickerRow, onToggle: (AppPickerRow) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .toggleable(value = row.checked, enabled = row.enabled, role = Role.Checkbox) { onToggle(row) }
            .heightIn(min = 72.dp)
            .padding(horizontal = RethinkSpacing.screenGutter, vertical = RethinkSpacing.sm)
            .alpha(if (row.enabled) 1f else DISABLED_ALPHA)
            .testTag("app_picker_row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.lg),
    ) {
        Box(Modifier.size(40.dp).alpha(if (row.noInternet) NO_INTERNET_ALPHA else 1f)) {
            DrawableImage(row.icon, Modifier.size(40.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(RethinkSpacing.xxs)) {
            Text(
                row.appName,
                Modifier.alpha(if (row.noInternet) NO_INTERNET_ALPHA else 1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val notes = AppPickerLogic.notes(row)
            if (notes.isNotEmpty()) {
                Text(
                    notes.map { noteText(it) }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // the row handles input and semantics; the checkbox only draws the state
        // (the row already fades when disabled; a disabled checkbox on top would fade twice)
        Checkbox(checked = row.checked, onCheckedChange = null)
    }
}

@Composable
private fun noteText(note: AppPickerNote): String = when (note) {
    AppPickerNote.BypassesProxies -> stringResource(R.string.exclude_apps_from_proxy_failure_toast)
    is AppPickerNote.RoutedVia -> stringResource(R.string.proxy_app_routed_via, note.names)
    AppPickerNote.NoInternet -> stringResource(R.string.app_picker_no_internet)
}

@Composable
private fun AppPickerEmptyState(proxyName: String, state: AppPickerState, modifier: Modifier) {
    val (title, body) = when (AppPickerLogic.empty(state.query, state.filter)) {
        AppPickerEmpty.NO_MATCH ->
            stringResource(R.string.app_picker_empty_search_title) to
                stringResource(R.string.app_picker_empty_search_body, state.query.trim())
        AppPickerEmpty.NONE_SELECTED ->
            stringResource(R.string.app_picker_empty_selected_title) to
                stringResource(R.string.app_picker_empty_selected_body, proxyName)
        AppPickerEmpty.ALL_SELECTED ->
            stringResource(R.string.app_picker_empty_unselected_title) to
                stringResource(R.string.app_picker_empty_unselected_body, proxyName)
        AppPickerEmpty.NO_APPS ->
            stringResource(R.string.app_picker_empty_all_title) to stringResource(R.string.app_picker_empty_all_body)
    }
    EmptyState(
        icon = if (state.query.isNotBlank()) Icons.Filled.SearchOff else Icons.Filled.Apps,
        title = title,
        body = body,
        modifier = modifier.testTag("app_picker_empty"),
    )
}

private const val DISABLED_ALPHA = 0.38f
private const val NO_INTERNET_ALPHA = 0.4f

private object NoopPickerActions : AppPickerActions {
    override fun back() {}
    override fun search(query: String) {}
    override fun filter(filter: AppPickerFilter) {}
    override fun refreshApps() {}
    override fun toggle(row: AppPickerRow) {}
    override fun selectAll() {}
    override fun unselectAll() {}
}

@ThemePreviews
@Composable
private fun AppPickerScreenPreview() = PreviewSurface {
    val fake = listOf(
        AppPickerRow(10101, "org.mozilla.firefox", "Firefox", selected = true),
        AppPickerRow(10102, "com.whatsapp", "WhatsApp", selected = false, routedVia = "Home WireGuard"),
        AppPickerRow(10103, "com.android.vending", "Google Play Store with a very long name", selected = true),
        AppPickerRow(10104, "com.example.bypass", "Banking", selected = false, bypassesProxies = true),
        AppPickerRow(10105, "com.example.offline", "Calculator", selected = false, noInternet = true),
    )
    val state = remember { AppPickerState(filter = AppPickerFilter.ALL).apply { selectedCount = 2 } }
    Box(Modifier.heightIn(max = 640.dp)) {
        AppPickerScreen(
            proxyName = "Office VPN",
            state = state,
            rows = AppPickerRows({ fake.size }, loading = false, rowKey = { fake[it].key }, rowAt = { fake[it] }),
            actions = NoopPickerActions,
        )
    }
}

@ThemePreviews
@Composable
private fun AppPickerEmptyPreview() = PreviewSurface {
    val state = remember { AppPickerState(query = "zzz") }
    Box(Modifier.heightIn(max = 400.dp)) {
        AppPickerScreen("SOCKS5", state, AppPickerRows({ 0 }, loading = false, rowKey = { it }, rowAt = { null }), NoopPickerActions)
    }
}
