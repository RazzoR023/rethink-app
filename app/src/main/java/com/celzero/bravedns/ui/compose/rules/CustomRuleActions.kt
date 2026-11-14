/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.rules

import android.net.Uri
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.ui.fragment.RulesImportHelper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every read / write of the rules screen. Runs I/O on [ioDispatcher] and updates [state] on
 * [mainDispatcher]; [notify] shows a short message (a string resource), [log] records an event.
 */
internal class CustomRuleActions(
    private val state: RulesScreenState,
    private val scope: CoroutineScope,
    private val port: CustomRulePort,
    private val notify: (Int, Array<out Any>) -> Unit,
    private val log: (kind: RuleKind, details: String) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    private val uid get() = state.scope.uid

    // ---- add / edit ----

    /** Validates the open editor's draft and saves it; the dialog closes on success. */
    fun saveDraft(): Job? {
        val draft = state.draft ?: return null
        if (!draft.canSave) return null
        return if (draft.kind == RuleKind.IP) saveIp(draft) else saveDomain(draft)
    }

    private fun fail(error: Int) = state.updateDraft { it.copy(error = error, saving = false) }

    private fun saveIp(draft: RuleDraft): Job = scope.launch(mainDispatcher) {
        val value = port.normalize(draft.input.trim())
        val (ip, number) = withContext(ioDispatcher) { port.parseIp(value) }
        if (ip == null || value.isEmpty()) { fail(R.string.ci_dialog_error_invalid_ip); return@launch }
        if (!port.isCidrEnforceable(ip)) { fail(R.string.ci_dialog_error_invalid_cidr); return@launch }
        state.updateDraft { it.copy(saving = true, error = null) }
        val status = RulesLogic.ipStatus(draft.action)
        val previous = draft.editing
        val replaced = withContext(ioDispatcher) {
            if (previous == null) { port.addIp(draft.uid, ip, number, status, draft.proxyId.ifEmpty { null }); null }
            else port.replaceIp(previous.copyIp(), ip, number, status, draft.proxyId)
        }
        state.dialog = null
        if (previous == null) {
            notify(R.string.ci_dialog_added_success, emptyArray())
            log(RuleKind.IP, "Added IP rule: $ip, Port: $number, Status: $status, Proxy: ${draft.proxyId}, UID: ${draft.uid}")
        } else {
            replacedDetails(previous, replaced)
            log(RuleKind.IP, "Updated Custom IP rule: Prev[${previous.target}], New[IP: $ip, Port: $number, Status: $status, Proxy: ${draft.proxyId}]")
        }
    }

    // an edited rule may have a new key; the open details sheet follows it
    private fun replacedDetails(previous: RuleItem, now: java.io.Serializable?) {
        if (state.details?.key != previous.key) return
        state.details = now?.let { RuleItem.of(it) }
    }

    private fun saveDomain(draft: RuleDraft): Job? {
        val previous = draft.editing
        val host = port.extractHost(draft.input.trim())
        if (host == null) { fail(R.string.cd_dialog_error_invalid_domain); return null }
        when (draft.domainType) {
            DomainRulesManager.DomainType.WILDCARD -> if (!port.isWildcard(host)) { fail(R.string.cd_dialog_error_invalid_wildcard); return null }
            DomainRulesManager.DomainType.DOMAIN -> if (!port.isDomain(host)) { fail(R.string.cd_dialog_error_invalid_domain); return null }
        }
        val normalized = port.normalize(host)
        val status = RulesLogic.domainStatus(draft.action)
        state.updateDraft { it.copy(saving = true, error = null) }
        return scope.launch(mainDispatcher) {
            withContext(ioDispatcher) {
                if (previous == null) port.addDomain(normalized, status, draft.domainType, draft.uid, draft.proxyId.ifEmpty { null })
                else port.replaceDomain(normalized, status, draft.domainType, previous.copyDomain(), draft.proxyId)
            }
            state.dialog = null
            if (previous == null) {
                notify(R.string.cd_toast_added, emptyArray())
                log(RuleKind.DOMAIN, "Added domain: $normalized, Type: ${draft.domainType}, Status: $status, Proxy: ${draft.proxyId}, UID: ${draft.uid}")
            } else {
                replacedDetails(previous, withContext(ioDispatcher) { port.getDomain(previous.uid, normalized) })
                notify(R.string.cd_toast_edit, emptyArray())
                log(RuleKind.DOMAIN, "Custom domain insert/update $normalized, status: $status, proxy: ${draft.proxyId}")
            }
        }
    }

    // ---- one rule ----

    /** Trust / Block / None (Bypass universal); keeps the rule's proxy. */
    fun setAction(item: RuleItem, action: RuleAction): Job? {
        if (item.action == action) return null
        state.refreshed(item.copy(action = action))
        return write(item) {
            when (item.kind) {
                RuleKind.IP -> port.setIpStatus(item.copyIp(), RulesLogic.ipStatus(action))
                RuleKind.DOMAIN -> port.setDomainStatus(item.copyDomain(), RulesLogic.domainStatus(action))
            }
            log(item.kind, "Set ${item.target} (uid ${item.uid}) to $action")
        }
    }

    /** "Route via": [proxyId] "" clears the override; keeps the rule's action. */
    fun setProxy(item: RuleItem, proxyId: String): Job? {
        if (item.proxyId == proxyId) return null
        state.refreshed(item.copy(proxyId = proxyId))
        return write(item) {
            when (item.kind) {
                RuleKind.IP -> port.setIpProxy(item.copyIp(), proxyId)
                RuleKind.DOMAIN -> port.setDomainProxy(item.copyDomain(), proxyId)
            }
            log(item.kind, "Route ${item.target} (uid ${item.uid}) via '$proxyId'")
        }
    }

    // writes, then shows the stored row (the source of truth) where the rule is displayed
    private fun write(item: RuleItem, block: suspend () -> Unit): Job = scope.launch(mainDispatcher) {
        val stored = withContext(ioDispatcher) {
            block()
            when (item.kind) {
                RuleKind.IP -> port.getIp(item.uid, item.value, item.port)
                RuleKind.DOMAIN -> port.getDomain(item.uid, item.value)
            }
        }
        if (stored != null) state.refreshed(RuleItem.of(stored))
    }

    fun delete(item: RuleItem): Job {
        state.dialog = null
        state.removed(item.kind, item.key)
        return scope.launch(mainDispatcher) {
            withContext(ioDispatcher) {
                when (item.kind) {
                    RuleKind.IP -> port.deleteIp(item.copyIp())
                    RuleKind.DOMAIN -> port.deleteDomain(item.copyDomain())
                }
            }
            if (item.kind == RuleKind.IP) notify(R.string.univ_ip_delete_individual_toast, arrayOf(item.target))
            else notify(R.string.cd_toast_deleted, emptyArray())
            log(item.kind, "Deleted custom rule for ${item.target} (uid ${item.uid})")
        }
    }

    // ---- many rules ----

    fun deleteSelected(kind: RuleKind): Job {
        state.dialog = null
        val tab = state.tab(kind)
        val items = tab.selected.values.toList()
        tab.clearSelection()
        if (state.details?.let { d -> items.any { it.key == d.key } } == true) state.details = null
        return scope.launch(mainDispatcher) {
            withContext(ioDispatcher) {
                when (kind) {
                    RuleKind.IP -> port.deleteIps(items.map { it.copyIp() })
                    RuleKind.DOMAIN -> port.deleteDomains(items.map { it.copyDomain() })
                }
            }
            notify(R.string.rules_deleted_n, arrayOf(items.size))
            log(kind, "Deleted ${items.size} rules: ${items.joinToString { it.target }}")
        }
    }

    fun deleteAll(kind: RuleKind): Job {
        state.dialog = null
        state.tab(kind).clearSelection()
        state.details = null
        val all = RulesLogic.deleteAllScope(state.scope)
        return scope.launch(mainDispatcher) {
            withContext(ioDispatcher) {
                when (kind) {
                    RuleKind.IP -> if (all == DeleteAllScope.ALL_APP_RULES) port.deleteAllAppIps() else port.deleteIpsByUid(uid)
                    RuleKind.DOMAIN -> if (all == DeleteAllScope.ALL_APP_RULES) port.deleteAllAppDomains() else port.deleteDomainsByUid(uid)
                }
            }
            notify(if (kind == RuleKind.DOMAIN) R.string.cd_deleted_toast else R.string.univ_ip_delete_toast_success, emptyArray())
            log(kind, if (all == DeleteAllScope.ALL_APP_RULES) "Deleted all app-specific rules" else "Deleted all rules for UID: $uid")
        }
    }

    // ---- lookups ----

    fun loadAppName(uid: Int) {
        if (state.appNames.containsKey(uid)) return
        state.appNames[uid] = null // in flight
        scope.launch(mainDispatcher) {
            val name = withContext(ioDispatcher) { port.appName(uid) }
            state.appNames[uid] = name.orEmpty()
        }
    }

    suspend fun refreshProxies() {
        val list = withContext(ioDispatcher) { port.proxies() }
        state.proxies = list
    }

    // ---- debug import ----

    fun parseImport(kind: RuleKind, uri: Uri): Job? {
        if (!DEBUG) return null
        return scope.launch(mainDispatcher) {
            val type = if (kind == RuleKind.DOMAIN) RulesImportHelper.ImportType.DOMAIN else RulesImportHelper.ImportType.IP
            val parsed = withContext(ioDispatcher) { port.parseImport(uri, type) }
            if (parsed == null || parsed.valid.isEmpty()) {
                notify(if (parsed == null) R.string.import_rules_error_unreadable else R.string.import_rules_error_empty, emptyArray())
            } else {
                state.dialog = RulesDialog.Import(kind, parsed, RuleAction.BLOCK)
            }
        }
    }

    fun importConfirmed(): Job? {
        if (!DEBUG) return null
        val d = state.dialog as? RulesDialog.Import ?: return null
        state.dialog = null
        return scope.launch(mainDispatcher) {
            val summary = withContext(ioDispatcher) {
                if (d.kind == RuleKind.DOMAIN) port.importRules(d.parsed.valid, RulesImportHelper.ImportType.DOMAIN, uid,
                    domainStatus = RulesLogic.domainStatus(d.action))
                else port.importRules(d.parsed.valid, RulesImportHelper.ImportType.IP, uid, ipStatus = RulesLogic.ipStatus(d.action))
            }
            state.dialog = RulesDialog.ImportSummary(summary.imported, summary.duplicates, summary.invalid)
            log(d.kind, "Import complete: imported=${summary.imported}, duplicates=${summary.duplicates}, invalid=${summary.invalid}, uid=$uid")
        }
    }
}
