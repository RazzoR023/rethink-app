/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.rules

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.celzero.bravedns.R
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.ProxyChoices
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the rule sheets read and write, behind one fakeable boundary. Status and proxy are
 * separate writes: a status change keeps the rule's proxy, a proxy change keeps its status.
 */
internal interface RuleSheetPort {
    /** [row] with its stored action and proxy, loaded = true */
    suspend fun read(row: RuleSheetRow): RuleSheetRow

    /** sets the action, keeping the proxy; false when the rule can't be stored (not a CIDR-able IP) */
    suspend fun setAction(row: RuleSheetRow, action: RuleAction): Boolean

    /** sets ("" clears) the proxy, keeping the action; creates a route-only rule if none; false as above */
    suspend fun setProxy(row: RuleSheetRow, proxyId: String): Boolean

    suspend fun proxies(): List<ProxyChoices.Choice>
}

/** Production port: IpRulesManager / DomainRulesManager. */
internal class ManagerRuleSheetPort(private val choices: ProxyChoices = ProxyChoices()) : RuleSheetPort {

    override suspend fun read(row: RuleSheetRow): RuleSheetRow = when (row.kind) {
        RuleKind.IP -> {
            // as before: the most specific rule for the ip (may be a cidr); proxy: this ip's own rule
            val status = IpRulesManager.getMostSpecificRuleMatch(row.uid, row.value)
            val ip = IpRulesManager.getIpNetPort(row.value).first
            val stored = ip?.let { IpRulesManager.getRule(row.uid, it) }
            row.copy(action = RulesLogic.actionOf(RuleKind.IP, status.id), proxyId = stored?.proxyId.orEmpty(), loaded = true)
        }
        RuleKind.DOMAIN -> {
            val status = if (row.matchWildcards) DomainRulesManager.status(row.value, row.uid)
            else DomainRulesManager.getDomainRule(row.value, row.uid)
            val stored = DomainRulesManager.getObj(row.uid, row.value)
            row.copy(action = RulesLogic.actionOf(RuleKind.DOMAIN, status.id), proxyId = stored?.proxyId.orEmpty(), loaded = true)
        }
    }

    override suspend fun setAction(row: RuleSheetRow, action: RuleAction): Boolean = when (row.kind) {
        RuleKind.IP -> {
            val ip = enforceableIp(row)
            if (ip != null) IpRulesManager.addIpRule(row.uid, ip, 0, RulesLogic.ipStatus(action))
            ip != null
        }
        RuleKind.DOMAIN -> {
            DomainRulesManager.addDomainRule(row.value, RulesLogic.domainStatus(action), DomainRulesManager.DomainType.DOMAIN, row.uid)
            true
        }
    }

    override suspend fun setProxy(row: RuleSheetRow, proxyId: String): Boolean = when (row.kind) {
        RuleKind.IP -> {
            val ip = enforceableIp(row)
            if (ip != null) IpRulesManager.setProxy(row.uid, ip, 0, proxyId)
            ip != null
        }
        RuleKind.DOMAIN -> {
            val cd = DomainRulesManager.getObj(row.uid, row.value) ?: DomainRulesManager.makeCustomDomain(row.uid, row.value)
            DomainRulesManager.setProxy(cd, proxyId)
            true
        }
    }

    // the ip trie only takes CIDR notation; anything else would be stored but never enforced
    private fun enforceableIp(row: RuleSheetRow) =
        IpRulesManager.getIpNetPort(row.value).first?.takeIf { IpRulesManager.isCidrEnforceable(it) }

    override suspend fun proxies() = choices.list()
}

/** Observable state of one rule sheet: its rows, the proxies (for names / status) and the open picker. */
@Stable
class RuleSheetState {
    val rows = mutableStateListOf<RuleSheetRow>()

    /** null while loading */
    var proxies by mutableStateOf<List<ProxyChoices.Choice>?>(null)

    /** the row whose "Route via" picker is open */
    var picker by mutableStateOf<RuleSheetRow?>(null)

    fun row(key: String): RuleSheetRow? = rows.firstOrNull { it.key == key }

    internal fun put(row: RuleSheetRow) {
        val i = rows.indexOfFirst { it.key == row.key }
        if (i >= 0) rows[i] = row else rows.add(row)
    }
}

/**
 * Reads and writes of a rule sheet: I/O on [ioDispatcher], state on [mainDispatcher]. Rows update
 * at once and then show what was stored. [notify] shows a short message (string resource),
 * [log] records an event.
 */
internal class RuleSheetActions(
    private val state: RuleSheetState,
    private val scope: CoroutineScope,
    private val port: RuleSheetPort,
    private val notify: (Int) -> Unit,
    private val log: (String) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    /** Shows [rows] and reads their stored rules. */
    fun load(rows: List<RuleSheetRow>): Job {
        state.rows.clear()
        state.rows.addAll(rows)
        return scope.launch(mainDispatcher) {
            val read = withContext(ioDispatcher) { rows.map { port.read(it) } }
            read.forEach { state.put(it) }
        }
    }

    /** Trust / Block / None; keeps the row's proxy. */
    fun setAction(row: RuleSheetRow, action: RuleAction): Job? {
        val next = RuleSheetLogic.withAction(row, action) ?: return null
        state.put(next)
        return write(row) {
            val ok = port.setAction(row, action)
            if (ok) log("${row.kind} rule for ${row.value} (uid ${row.uid}) set to $action")
            ok
        }
    }

    /** "Route via"; "" clears the override. Keeps the row's action. */
    fun setProxy(row: RuleSheetRow, proxyId: String): Job? {
        val next = RuleSheetLogic.withProxy(row, proxyId) ?: return null
        state.put(next)
        return write(row) {
            val ok = port.setProxy(row, proxyId)
            if (ok) log("${row.kind} rule for ${row.value} (uid ${row.uid}) routed via '$proxyId'")
            ok
        }
    }

    // writes, then shows the stored rule (also when the write was refused)
    private fun write(row: RuleSheetRow, block: suspend () -> Boolean): Job = scope.launch(mainDispatcher) {
        val (ok, stored) = withContext(ioDispatcher) {
            val ok = block()
            ok to port.read(row)
        }
        if (!ok) notify(R.string.ci_dialog_error_invalid_cidr)
        state.put(stored)
    }

    suspend fun refreshProxies() {
        state.proxies = withContext(ioDispatcher) { port.proxies() }
    }
}
