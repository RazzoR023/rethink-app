/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.celzero.bravedns.ui.compose.rules

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.service.ProxyChoices
import com.celzero.bravedns.ui.compose.design.EmptyState
import com.celzero.bravedns.ui.compose.design.RethinkSpacing
import com.celzero.bravedns.ui.compose.design.StatusChip
import com.celzero.bravedns.ui.compose.design.displayTitle
import com.celzero.bravedns.ui.compose.design.statusColors
import com.celzero.bravedns.ui.compose.design.toStatus
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.util.Utilities
import inet.ipaddr.IPAddressString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Custom IP / domain rules: two tabs (IP & port, domains), each a searchable list of rules.
 * A row shows what the rule matches, the app it applies to, its action (Trust / Block / None)
 * and, when set, "Route via <proxy>" with the proxy's live status. Tapping a row opens the
 * rule's details (RuleDetailsSheet); a long press starts multi-selection. Built only from
 * the design system; state in RulesScreenState, I/O in CustomRuleActions.
 */

/** One tab's data, wired by CustomRulesActivity. */
internal class RulesTabData(
    val kind: RuleKind,
    val rows: LazyPagingItems<RuleItem>,
    /** total rules in this scope (not filtered by the search); -1 while loading */
    val count: Int,
    val onQuery: (String) -> Unit,
)

/** What the screen asks its host (the activity) to do. */
internal interface RulesHost {
    fun back()
    fun openAppRules(kind: RuleKind, uid: Int)
    fun pickImportFile(kind: RuleKind)
}

@Composable
internal fun RulesScreen(
    state: RulesScreenState,
    actions: CustomRuleActions,
    host: RulesHost,
    initialTab: Int,
    tabs: List<RulesTabData>,
) {
    val pager = rememberPagerState(initialPage = initialTab.coerceIn(0, tabs.size - 1), pageCount = { tabs.size })
    val coroutines = rememberCoroutineScope()
    val current = tabs[pager.currentPage]
    val tabState = state.tab(current.kind)
    val focus = LocalFocusManager.current

    // selection belongs to one tab
    LaunchedEffect(pager.currentPage) {
        tabs.filter { it.kind != current.kind }.forEach { state.tab(it.kind).clearSelection() }
        focus.clearFocus()
    }

    Scaffold(
        topBar = { RulesTopBar(state, actions, host, current) },
        floatingActionButton = {
            // the empty state has its own Add button
            if (state.scope.canAdd && !tabState.selecting && current.count != 0) {
                ExtendedFloatingActionButton(
                    onClick = { state.openEditor(current.kind) },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text(stringResource(R.string.rules_add)) },
                    modifier = Modifier.testTag("rules_add"),
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = pager.currentPage, containerColor = MaterialTheme.colorScheme.background) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = pager.currentPage == index,
                        onClick = { coroutines.launch { pager.animateScrollToPage(index) } },
                        text = { Text(stringResource(tabTitle(tab.kind)), maxLines = 1) },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("rules_tab_${tab.kind.name.lowercase()}"),
                    )
                }
            }
            HorizontalPager(pager, Modifier.weight(1f), beyondViewportPageCount = 1) { page ->
                RulesTab(state, actions, host, tabs[page])
            }
        }
    }
    RulesDialogs(state, actions)
    state.details?.let { item -> RuleDetailsSheet(item, state, actions) }
    RulePickerHost(state, actions)
}

internal fun tabTitle(kind: RuleKind) = if (kind == RuleKind.IP) R.string.univ_view_blocked_ip else R.string.dc_custom_block_heading

@Composable
private fun RulesTopBar(state: RulesScreenState, actions: CustomRuleActions, host: RulesHost, current: RulesTabData) {
    val tab = state.tab(current.kind)
    if (tab.selecting) {
        TopAppBar(
            title = { Text(stringResource(R.string.rules_selected, tab.selected.size), Modifier.semantics { heading() }) },
            navigationIcon = {
                IconButton(tab::clearSelection) { Icon(Icons.Filled.Close, stringResource(R.string.rules_close_selection)) }
            },
            actions = {
                IconButton(
                    { state.dialog = RulesDialog.ConfirmDeleteSelected(current.kind, tab.selected.size) },
                    Modifier.testTag("rules_delete_selected"),
                ) { Icon(Icons.Filled.Delete, stringResource(R.string.rules_delete_selected)) }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        )
        return
    }
    TopAppBar(
        title = {
            Column {
                Text(stringResource(R.string.rules_title), Modifier.semantics { heading() }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    scopeLabel(state, actions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        navigationIcon = {
            IconButton(host::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.rules_navigate_up)) }
        },
        actions = {
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton({ menu = true }, Modifier.testTag("rules_overflow")) {
                    Icon(Icons.Filled.MoreVert, stringResource(R.string.rules_more_options))
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rules_delete_all)) },
                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                        enabled = current.count > 0,
                        onClick = { menu = false; state.dialog = RulesDialog.ConfirmDeleteAll(current.kind) },
                        modifier = Modifier.testTag("rules_delete_all"),
                    )
                    if (DEBUG && state.scope.canAdd) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.import_rules_fab_desc)) },
                            leadingIcon = { Icon(Icons.Filled.FileOpen, null) },
                            onClick = { menu = false; host.pickImportFile(current.kind) },
                            modifier = Modifier.testTag("rules_import"),
                        )
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
private fun scopeLabel(state: RulesScreenState, actions: CustomRuleActions): String {
    val scope = state.scope
    return when {
        scope.allApps -> stringResource(R.string.rules_scope_app_rules)
        scope.isUniversal -> stringResource(R.string.rules_scope_all_apps)
        else -> appLabel(state, actions, uid = scope.uid)
    }
}

/** The app a rule applies to: "All apps" for universal rules, else its name (loaded once). */
@Composable
internal fun appLabel(state: RulesScreenState, actions: CustomRuleActions?, uid: Int): String {
    if (uid == UID_EVERYBODY) return stringResource(R.string.rules_scope_all_apps)
    LaunchedEffect(uid) { actions?.loadAppName(uid) }
    return when (val name = state.appNames[uid]) {
        null -> "" // still loading; blank beats a flash of "Unknown app"
        "" -> stringResource(R.string.rules_unknown_app, uid)
        else -> name
    }
}

@Composable
private fun RulesTab(state: RulesScreenState, actions: CustomRuleActions, host: RulesHost, data: RulesTabData) {
    val tab = state.tab(data.kind)
    val rows = data.rows
    if (data.count == 0 && tab.query.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = if (data.kind == RuleKind.IP) Icons.Filled.Lan else Icons.Filled.Language,
                title = stringResource(if (data.kind == RuleKind.IP) R.string.rules_empty_ip_title else R.string.rules_empty_domain_title),
                body = stringResource(if (data.kind == RuleKind.IP) R.string.rules_empty_ip_body else R.string.rules_empty_domain_body),
                actionLabel = if (state.scope.canAdd) stringResource(R.string.rules_add) else null,
                onAction = if (state.scope.canAdd) ({ state.openEditor(data.kind) }) else null,
                modifier = Modifier.testTag("rules_empty"),
            )
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        SearchField(tab, data)
        val loaded = rows.loadState.refresh !is LoadState.Loading
        if (loaded && rows.itemCount == 0 && tab.query.isNotEmpty()) {
            Text(
                stringResource(R.string.rules_empty_search, tab.query),
                Modifier.fillMaxWidth().padding(RethinkSpacing.xl),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(
            Modifier.fillMaxSize().testTag("rules_list_${data.kind.name.lowercase()}"),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            items(rows.itemCount, key = rows.itemKey { it.key }) { index ->
                val item = rows[index] ?: run { Box(Modifier.fillMaxWidth().heightIn(min = 72.dp)); return@items }
                val previousUid = if (index > 0) rows.peek(index - 1)?.uid else null
                Column {
                    if (RulesLogic.showHeader(state.scope.grouped, index, previousUid, item.uid)) {
                        AppGroupHeader(state, actions, item.uid) { host.openAppRules(data.kind, item.uid) }
                    }
                    RuleRow(
                        item = item,
                        appName = appLabel(state, actions, item.uid),
                        proxies = state.proxies,
                        selecting = tab.selecting,
                        selected = tab.selected.containsKey(item.key),
                        onClick = { if (tab.selecting) tab.toggle(item) else state.details = item },
                        onLongClick = { tab.toggle(item) },
                        onEdit = { state.openEditor(item.kind, item) },
                        onRoute = { state.picker = PickerTarget.Rule(item) },
                        onDelete = { state.dialog = RulesDialog.ConfirmDelete(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(tab: RulesTabState, data: RulesTabData) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = tab.query,
        onValueChange = { tab.search(it, data.onQuery) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = RethinkSpacing.lg, vertical = RethinkSpacing.sm)
            .testTag("rules_search"),
        placeholder = { Text(stringResource(if (data.kind == RuleKind.IP) R.string.rules_search_ip else R.string.rules_search_domain)) },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = {
            if (tab.query.isNotEmpty()) {
                IconButton({ tab.search("", data.onQuery) }) { Icon(Icons.Filled.Close, stringResource(R.string.rules_search_clear)) }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
    )
}

@Composable
private fun AppGroupHeader(state: RulesScreenState, actions: CustomRuleActions, uid: Int, onShowAll: () -> Unit) {
    val name = appLabel(state, actions, uid)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = RethinkSpacing.minTouchTarget)
            .padding(start = RethinkSpacing.screenGutter, end = RethinkSpacing.xs, top = RethinkSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val cd = stringResource(R.string.rules_show_all_cd, name)
        TextButton(onShowAll, Modifier.semantics { this.contentDescription = cd }) { Text(stringResource(R.string.ssv_see_more)) }
    }
}

@Composable
internal fun RuleRow(
    item: RuleItem,
    appName: String,
    proxies: List<ProxyChoices.Choice>?,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onEdit: () -> Unit,
    onRoute: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClickLabel = stringResource(if (selecting) R.string.rules_select else R.string.rules_open),
                onLongClickLabel = stringResource(R.string.rules_select),
                role = if (selecting) Role.Checkbox else Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .then(if (selecting) Modifier.semantics { this.selected = selected } else Modifier)
            .heightIn(min = 72.dp)
            .padding(start = RethinkSpacing.screenGutter, end = RethinkSpacing.xs, top = RethinkSpacing.sm, bottom = RethinkSpacing.sm)
            .testTag("rule_row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.md),
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            if (selecting) Checkbox(checked = selected, onCheckedChange = null)
            else RuleLeading(item)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(RethinkSpacing.xxs)) {
            Text(
                item.target,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.sm)) {
                RuleActionBadge(item.action)
                Text(
                    listOf(appName, changedLabel(item.modifiedTs)).joinToString(stringResource(R.string.app_details_summary_separator)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            RouteLine(item, proxies)
        }
        if (!selecting) RowMenu(item, onEdit, onRoute, onDelete) else Box(Modifier.size(RethinkSpacing.minTouchTarget))
    }
}

/** "Route via <proxy>" with the proxy's live status, when the rule routes via one. */
@Composable
internal fun RouteLine(item: RuleItem, proxies: List<ProxyChoices.Choice>?) {
    val choice = RulesLogic.proxyChoice(item.proxyId, proxies) ?: return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.sm),
        modifier = Modifier.testTag("rule_route"),
    ) {
        Icon(Icons.Filled.Route, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            stringResource(R.string.rules_route_via, choice.displayTitle()),
            Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (proxies != null) StatusChip(choice.health.toStatus())
    }
}

@Composable
private fun RowMenu(item: RuleItem, onEdit: () -> Unit, onRoute: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton({ open = true }, Modifier.testTag("rule_row_menu")) {
            Icon(Icons.Filled.MoreVert, stringResource(R.string.rules_row_more, item.target))
        }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rules_edit)) },
                leadingIcon = { Icon(Icons.Filled.Edit, null) },
                onClick = { open = false; onEdit() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rules_route_via_menu)) },
                leadingIcon = { Icon(Icons.Filled.Route, null) },
                onClick = { open = false; onRoute() },
                modifier = Modifier.testTag("rule_menu_route"),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rules_delete)) },
                leadingIcon = { Icon(Icons.Filled.Delete, null) },
                onClick = { open = false; onDelete() },
            )
        }
    }
}

/** Flag of the IP's country (universal IP rules, as before), else an icon for the kind. */
@Composable
internal fun RuleLeading(item: RuleItem) {
    val context = LocalContext.current
    var flag by remember(item.value) { mutableStateOf("") }
    if (item.kind == RuleKind.IP && !item.wildcard && item.value.isNotEmpty()) {
        LaunchedEffect(item.value) {
            flag = withContext(Dispatchers.IO) {
                val inet = try { IPAddressString(item.value).hostAddress?.toInetAddress() } catch (_: Exception) { null }
                Utilities.getFlag(Utilities.getCountryCode(inet, context))
            }
        }
    }
    if (flag.isNotBlank()) {
        Text(flag, style = MaterialTheme.typography.headlineSmall)
    } else {
        Icon(
            if (item.kind == RuleKind.IP) Icons.Filled.Lan else Icons.Filled.Language,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The rule's action as a small labelled pill; icon + text, so it doesn't rely on colour. */
@Composable
internal fun RuleActionBadge(action: RuleAction, modifier: Modifier = Modifier) {
    val c = MaterialTheme.statusColors
    val (container, content) = when (action) {
        RuleAction.BLOCK -> c.errorContainer to c.onErrorContainer
        RuleAction.TRUST, RuleAction.BYPASS_UNIVERSAL -> c.activeContainer to c.onActiveContainer
        RuleAction.NONE -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(modifier, shape = MaterialTheme.shapes.small, color = container, contentColor = content) {
        Row(
            Modifier.padding(horizontal = RethinkSpacing.sm, vertical = RethinkSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.xs),
        ) {
            Icon(actionIcon(action), null, Modifier.size(14.dp))
            Text(stringResource(actionLabel(action)), style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

internal fun actionIcon(action: RuleAction): ImageVector = when (action) {
    RuleAction.BLOCK -> Icons.Filled.Block
    RuleAction.TRUST, RuleAction.BYPASS_UNIVERSAL -> Icons.Filled.VerifiedUser
    RuleAction.NONE -> Icons.Outlined.RemoveCircleOutline
}

internal fun actionLabel(action: RuleAction): Int = when (action) {
    RuleAction.TRUST -> R.string.rules_action_trust
    RuleAction.BLOCK -> R.string.rules_action_block
    RuleAction.NONE -> R.string.rules_action_none
    RuleAction.BYPASS_UNIVERSAL -> R.string.rules_action_bypass
}

internal fun actionDescription(action: RuleAction): Int = when (action) {
    RuleAction.TRUST -> R.string.rules_action_trust_desc
    RuleAction.BLOCK -> R.string.rules_action_block_desc
    RuleAction.NONE -> R.string.rules_action_none_desc
    RuleAction.BYPASS_UNIVERSAL -> R.string.rules_action_bypass_desc
}

@Composable
internal fun changedLabel(ts: Long): String {
    if (System.currentTimeMillis() - ts in 0 until DateUtils.MINUTE_IN_MILLIS) return stringResource(R.string.rules_changed_just_now)
    val rel = DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
    return stringResource(R.string.rules_changed, rel)
}
