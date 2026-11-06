/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.proxy

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.database.CustomDomainDAO
import com.celzero.bravedns.database.CustomIpDao
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.ui.compose.design.RethinkSpacing
import com.celzero.bravedns.ui.compose.design.SectionDivider
import com.celzero.bravedns.ui.compose.design.SettingsSection
import com.celzero.bravedns.ui.compose.rules.CustomRuleActions
import com.celzero.bravedns.ui.compose.rules.ManagerCustomRulePort
import com.celzero.bravedns.ui.compose.rules.RuleActionBadge
import com.celzero.bravedns.ui.compose.rules.RuleDetailsSheet
import com.celzero.bravedns.ui.compose.rules.RuleItem
import com.celzero.bravedns.ui.compose.rules.RuleKind
import com.celzero.bravedns.ui.compose.rules.RuleLeading
import com.celzero.bravedns.ui.compose.rules.RulePickerHost
import com.celzero.bravedns.ui.compose.rules.RulesDialog
import com.celzero.bravedns.ui.compose.rules.RulesDialogs
import com.celzero.bravedns.ui.compose.rules.RulesScope
import com.celzero.bravedns.ui.compose.rules.RulesScreenState
import com.celzero.bravedns.ui.compose.rules.appLabel
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * "IP and domain rules" on a proxy page: every IP / domain rule (of any app, incl. All apps)
 * routed via the proxy, live from the database. Rows look like the rules page's; a row opens
 * the shared rule details sheet, and "stop routing" clears only the rule's proxy (its action
 * stays). "Add" opens the shared add dialog with Route via set to this proxy and an "Applies
 * to" chooser (one app or All apps). Reuses RulesScreenState / CustomRuleActions, so every
 * write goes through IpRulesManager / DomainRulesManager like the rules page.
 */

/** Where the rules come from; [LiveProxyRulesSource] in the app, fakes in tests. */
interface ProxyRulesSource {
    /** rules routed via [proxyId], re-emitted on every change */
    fun rules(proxyId: String): Flow<List<RuleItem>>

    /** (uid, app name) of the installed apps, for the "Applies to" chooser */
    suspend fun apps(): List<Pair<Int, String>>
}

class LiveProxyRulesSource(private val ipDao: CustomIpDao, private val domainDao: CustomDomainDAO) : ProxyRulesSource {
    override fun rules(proxyId: String): Flow<List<RuleItem>> =
        combine(ipDao.getRulesByProxyIdFlow(proxyId), domainDao.getRulesByProxyIdFlow(proxyId)) { ips, domains ->
            ProxyRulesLogic.merge(proxyId, ips, domains)
        }

    override suspend fun apps(): List<Pair<Int, String>> =
        FirewallManager.getAppInfoSnapshot().values.filter { it.tombstoneTs == 0L }.map { it.uid to it.appName }
}

/** State and actions of the rules section of one proxy page. */
class ProxyRulesController internal constructor(
    val proxyId: String,
    internal val state: RulesScreenState,
    internal val actions: CustomRuleActions,
    private val source: ProxyRulesSource,
    private val scope: CoroutineScope,
    private val allAppsName: String,
    private val notify: (Int, Array<out Any>) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    /** the rules routed via the proxy; null until loaded */
    var rules by mutableStateOf<List<RuleItem>?>(null)
        private set

    /** the "Applies to" choices; null until loaded */
    var appChoices by mutableStateOf<List<AppScopeChoice>?>(null)
        private set

    /** Collects the rules until [scope] is cancelled. */
    fun start(): Job = scope.launch(mainDispatcher) {
        source.rules(proxyId).flowOn(ioDispatcher).collect { rules = it }
    }

    /** Opens the add dialog for a [kind] rule routed via this proxy, for All apps. */
    fun add(kind: RuleKind) {
        state.dialog = RulesDialog.Editor(ProxyRulesLogic.newDraft(kind, proxyId))
    }

    fun open(item: RuleItem) {
        state.details = item
    }

    /** Stops routing [item] via this proxy; its action (block / trust / none) stays. */
    fun stopRouting(item: RuleItem): Job? {
        val job = actions.setProxy(item, "") ?: return null
        notify(R.string.proxy_page_rule_unrouted, arrayOf(item.target))
        return job
    }

    fun openScopePicker(): Job {
        state.scopePicker = true
        return loadApps()
    }

    /** Loads the "Applies to" choices once; the editor opens the picker via [RulesScreenState.scopePicker]. */
    fun loadApps(): Job = scope.launch(mainDispatcher) {
        if (appChoices != null) return@launch
        val apps = withContext(ioDispatcher) { source.apps() }
        appChoices = AppScopeChoice.list(allAppsName, apps)
    }

    fun pickScope(uid: Int) {
        state.updateDraft { ProxyRulesLogic.withScope(it, uid) }
        state.scopePicker = false
    }

    suspend fun refreshProxies() = actions.refreshProxies()

    companion object {
        /** The live controller of a proxy page (rules via [proxyId]). */
        fun create(
            context: Context,
            proxyId: String,
            scope: CoroutineScope,
            ipDao: CustomIpDao,
            domainDao: CustomDomainDAO,
            notify: (Int, Array<out Any>) -> Unit,
            log: (kind: RuleKind, details: String) -> Unit,
        ): ProxyRulesController {
            val state = RulesScreenState(RulesScope(UID_EVERYBODY, allApps = false), scopePickable = true)
            val actions = CustomRuleActions(state, scope, ManagerCustomRulePort(context.applicationContext), notify, log)
            return ProxyRulesController(
                proxyId, state, actions, LiveProxyRulesSource(ipDao, domainDao), scope,
                context.getString(R.string.rules_scope_all_apps), notify,
            )
        }
    }
}

/** The section: count in the header, the rows (or an empty note) and the add buttons. */
@Composable
fun ProxyRulesSection(controller: ProxyRulesController, proxyName: String, modifier: Modifier = Modifier) {
    val rules = controller.rules
    val title = if (rules == null) stringResource(R.string.proxy_page_section_rules)
        else stringResource(R.string.proxy_page_section_rules_n, rules.size)
    SettingsSection(modifier.testTag("proxy_rules"), title = title) {
        when {
            rules == null -> Unit
            rules.isEmpty() -> Text(
                stringResource(R.string.proxy_page_rules_empty, proxyName),
                Modifier.padding(horizontal = RethinkSpacing.screenGutter, vertical = RethinkSpacing.md).testTag("proxy_rules_empty"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> rules.forEachIndexed { i, item ->
                if (i > 0) SectionDivider()
                ProxyRuleRow(controller, item)
            }
        }
        SectionDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = RethinkSpacing.lg, vertical = RethinkSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.sm),
        ) {
            OutlinedButton(
                { controller.add(RuleKind.DOMAIN) },
                Modifier.weight(1f).testTag("proxy_rules_add_domain"),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(Icons.Filled.Add, null, Modifier.size(ButtonDefaults.IconSize))
                Text(stringResource(R.string.proxy_page_add_domain), Modifier.padding(start = ButtonDefaults.IconSpacing))
            }
            OutlinedButton(
                { controller.add(RuleKind.IP) },
                Modifier.weight(1f).testTag("proxy_rules_add_ip"),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(Icons.Filled.Add, null, Modifier.size(ButtonDefaults.IconSize))
                Text(stringResource(R.string.proxy_page_add_ip), Modifier.padding(start = ButtonDefaults.IconSpacing))
            }
        }
    }
}

@Composable
private fun ProxyRuleRow(controller: ProxyRulesController, item: RuleItem) {
    val appName = appLabel(controller.state, controller.actions, item.uid)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.rules_open)) { controller.open(item) }
            .heightIn(min = 64.dp)
            .padding(start = RethinkSpacing.screenGutter, end = RethinkSpacing.xs, top = RethinkSpacing.sm, bottom = RethinkSpacing.sm)
            .testTag("proxy_rule_row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.md),
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { RuleLeading(item) }
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
                    appName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton({ controller.stopRouting(item) }, Modifier.testTag("proxy_rule_unroute")) {
            Icon(Icons.Filled.LinkOff, stringResource(R.string.proxy_page_rule_unroute_cd, item.target), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The shared rule dialogs, details sheet, proxy picker and the "Applies to" chooser. */
@Composable
fun ProxyRulesHosts(controller: ProxyRulesController) {
    val state = controller.state
    RulesDialogs(state, controller.actions)
    state.details?.let { RuleDetailsSheet(it, state, controller.actions) }
    RulePickerHost(state, controller.actions)
    ScopePickerHost(controller)
}

/** The "Applies to" chooser the editor opens (it only sets `scopePicker`); loads the apps once. */
@Composable
internal fun ScopePickerHost(controller: ProxyRulesController) {
    val state = controller.state
    if (state.scopePicker) {
        val draft = state.draft
        if (draft == null) {
            state.scopePicker = false
        } else {
            // the editor only sets the flag; load the apps here (once)
            LaunchedEffect(controller) { controller.loadApps() }
            AppScopeDialog(
                choices = controller.appChoices,
                selectedUid = draft.uid,
                onPick = controller::pickScope,
                onDismiss = { state.scopePicker = false },
            )
        }
    }
}

/** One app or All apps, with a search; a radio list in a dialog. */
@Composable
internal fun AppScopeDialog(
    choices: List<AppScopeChoice>?,
    selectedUid: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("proxy_scope_dialog"),
        title = { Text(stringResource(R.string.proxy_page_scope_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(RethinkSpacing.sm)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().testTag("proxy_scope_search"),
                    placeholder = { Text(stringResource(R.string.proxy_page_scope_search)) },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.extraLarge,
                )
                if (choices == null) {
                    Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodyMedium)
                } else {
                    val shown = AppScopeChoice.filter(choices, query)
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(shown, key = { it.uid }) { c ->
                            RadioLine(c.name, c.uid == selectedUid, emphasis = c.isAllApps) { onPick(c.uid) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.lbl_cancel)) } },
    )
}

/** One radio row of a chooser dialog. */
@Composable
internal fun RadioLine(text: String, selected: Boolean, emphasis: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(vertical = RethinkSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.md),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text,
            style = if (emphasis) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Keeps the rule rows' proxy status chips (details sheet, editor) fresh while shown. */
@Composable
fun ProxyRulesRefresh(controller: ProxyRulesController) {
    LaunchedEffect(controller) { controller.refreshProxies() }
}
