/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.rules

import androidx.compose.runtime.Immutable
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY

/*
 * Pure logic of the per-connection rule sheets (app IP log -> AppIpRulesBottomSheet, app domain
 * log -> AppDomainRulesBottomSheet, network log -> ConnTrackerBottomSheet): one row per IP /
 * domain seen on a connection, each with an action (Trust / Block / None) and an independent
 * "Route via" proxy. Tested by RuleSheetModelTest.
 */

/** One IP or domain on a rule sheet, for one uid (an app, or UID_EVERYBODY for all apps). */
@Immutable
data class RuleSheetRow(
    val kind: RuleKind,
    val uid: Int,
    /** the IP as logged, or the domain */
    val value: String,
    val action: RuleAction = RuleAction.NONE,
    /** "" = no proxy override */
    val proxyId: String = "",
    /** false until the stored rule has been read */
    val loaded: Boolean = false,
    /** domains: the shown action also counts wildcard rules (*.example.com), as the old domain sheet did */
    val matchWildcards: Boolean = false,
    /** an action not offered here (Bypass universal on an app's IP) is shown but not changed */
    val lockUnlisted: Boolean = false,
) {
    val key: String get() = "${kind.name}:$uid:$value"
    val isUniversal: Boolean get() = uid == UID_EVERYBODY
}

object RuleSheetLogic {

    /** The actions offered for [row] (as the rules page: Bypass universal for universal IPs). */
    fun options(row: RuleSheetRow): List<RuleAction> =
        if (locked(row)) listOf(row.action) else RulesLogic.actionOptions(row.kind, row.uid, row.action)

    /**
     * The row's action is read-only: [RuleSheetRow.lockUnlisted] is set and the stored action is
     * not one this sheet offers (e.g. Bypass universal on an app IP rule, set from the IP rules
     * page). The proxy stays editable.
     */
    fun locked(row: RuleSheetRow): Boolean =
        row.lockUnlisted && row.action !in RulesLogic.actionOptions(row.kind, row.uid)

    /** [row] with [action], or null when nothing changes (same action, locked, not loaded). */
    fun withAction(row: RuleSheetRow, action: RuleAction): RuleSheetRow? = when {
        !row.loaded || locked(row) || row.action == action -> null
        action !in options(row) -> null
        else -> row.copy(action = action)
    }

    /** [row] routed via [proxyId] ("" = no override), or null when nothing changes. */
    fun withProxy(row: RuleSheetRow, proxyId: String): RuleSheetRow? =
        if (!row.loaded || row.proxyId == proxyId) null else row.copy(proxyId = proxyId)

    /** Blocked flows are dropped, never routed: say so when a blocked row has a proxy. */
    fun proxyIgnored(row: RuleSheetRow): Boolean = RulesLogic.proxyIgnored(row.action, row.proxyId)

    /** The domains of an IP log row ("a.com,b.com"), trimmed, without blanks or repeats. */
    fun domains(csv: String?): List<String> =
        csv.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** Rows of the app IP sheet: the IP, then each of its domains. */
    fun ipSheetRows(uid: Int, ip: String, domainsCsv: String?): List<RuleSheetRow> =
        listOf(RuleSheetRow(RuleKind.IP, uid, ip)) + domains(domainsCsv).map { RuleSheetRow(RuleKind.DOMAIN, uid, it) }

    /** Row of the app domain sheet (counts wildcard rules, as before). */
    fun domainSheetRows(uid: Int, domain: String): List<RuleSheetRow> =
        listOf(RuleSheetRow(RuleKind.DOMAIN, uid, domain, matchWildcards = true))

    /**
     * Rows of the connection details sheet: the app's rule for the IP and, when the connection
     * had a DNS query, for its domain. Bypass universal stays read-only here, as before.
     */
    fun connectionRows(uid: Int, ip: String, domain: String?): List<RuleSheetRow> =
        listOfNotNull(
            RuleSheetRow(RuleKind.IP, uid, ip, lockUnlisted = true),
            domain?.takeIf { it.isNotBlank() }?.let { RuleSheetRow(RuleKind.DOMAIN, uid, it) },
        )
}
