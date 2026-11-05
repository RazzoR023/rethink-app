/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.rules

import androidx.compose.runtime.Immutable
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.IpRulesManager.IpRuleStatus
import com.celzero.bravedns.service.ProxyChoices
import com.celzero.bravedns.ui.activity.CustomRulesActivity
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import java.io.Serializable

/*
 * Pure logic of the custom IP / domain rules screens (no Android, no Compose runtime beyond
 * @Immutable): the row model, the actions a rule can take, what a scope shows and allows, and
 * the editor draft. Tested by RulesModelTest.
 */

enum class RuleKind { IP, DOMAIN }

/** A rule's firewall action; independent of its proxy ("Route via"). */
enum class RuleAction { NONE, BLOCK, TRUST, BYPASS_UNIVERSAL }

/** One IP / domain rule as the screens show it; [source] is the stored row it came from. */
@Immutable
data class RuleItem(
    val kind: RuleKind,
    val uid: Int,
    /** ip / cidr (may be "" for a port-only rule) or domain ("*.example.com" for wildcards) */
    val value: String,
    val port: Int,
    val wildcard: Boolean,
    val action: RuleAction,
    val proxyId: String,
    val proxyCC: String,
    val modifiedTs: Long,
    val source: Serializable,
) {
    val key: String get() = ruleKey(source)

    val isUniversal: Boolean get() = uid == UID_EVERYBODY

    /** What the rule matches, as shown: "1.2.3.4", "[10.0.0.0/8]:443", "*:53", "*.example.com". */
    val target: String get() = when (kind) {
        RuleKind.DOMAIN -> value
        RuleKind.IP -> when {
            value.isEmpty() -> "*:$port"
            port == 0 -> value
            else -> IpRulesManager.joinIpNetPort(value, port)
        }
    }

    /** The text the editor starts with when editing this rule. */
    val editorInput: String get() = when (kind) {
        RuleKind.DOMAIN -> value
        RuleKind.IP -> if (port != 0) IpRulesManager.joinIpNetPort(value, port) else value
    }

    fun copyIp(): CustomIp = (source as CustomIp).deepCopy()

    fun copyDomain(): CustomDomain = (source as CustomDomain).let {
        CustomDomain(it.domain, it.uid, it.ips, it.type, it.status, it.proxyId, it.proxyCC, it.modifiedTs, it.deletedTs, it.version)
    }

    companion object {
        fun of(item: Serializable): RuleItem = when (item) {
            is CustomIp -> of(item)
            is CustomDomain -> of(item)
            else -> error("unknown rule $item")
        }

        fun of(ci: CustomIp) = RuleItem(
            kind = RuleKind.IP,
            uid = ci.uid,
            value = ci.ipAddress,
            port = ci.port,
            wildcard = ci.wildcard,
            action = RulesLogic.actionOf(RuleKind.IP, ci.status),
            proxyId = ci.proxyId,
            proxyCC = ci.proxyCC,
            modifiedTs = ci.modifiedDateTime,
            source = ci.deepCopy(),
        )

        fun of(cd: CustomDomain) = RuleItem(
            kind = RuleKind.DOMAIN,
            uid = cd.uid,
            value = cd.domain,
            port = 0,
            wildcard = cd.type == DomainRulesManager.DomainType.WILDCARD.id,
            action = RulesLogic.actionOf(RuleKind.DOMAIN, cd.status),
            proxyId = cd.proxyId,
            proxyCC = cd.proxyCC,
            modifiedTs = cd.modifiedTs,
            source = CustomDomain(cd.domain, cd.uid, cd.ips, cd.type, cd.status, cd.proxyId, cd.proxyCC, cd.modifiedTs, cd.deletedTs, cd.version),
        )
    }
}

/**
 * Which rules a screen shows: one app's (or the universal, all-apps) rules, or every app's
 * rules grouped by app (from Firewall settings; universal rules not included).
 */
@Immutable
data class RulesScope(val uid: Int, val allApps: Boolean) {
    val isUniversal: Boolean get() = !allApps && uid == UID_EVERYBODY

    /** New rules can be added for one app or for all apps, not on the grouped list. */
    val canAdd: Boolean get() = !allApps

    /** Rows are grouped under an app header. */
    val grouped: Boolean get() = allApps

    companion object {
        fun of(uid: Int, rules: CustomRulesActivity.RULES) =
            RulesScope(uid, rules == CustomRulesActivity.RULES.ALL_RULES)
    }
}

/** What "Delete all" removes in a scope. */
enum class DeleteAllScope { ONE_UID, ALL_APP_RULES }

/** Editor draft for adding or editing a rule. [editing] is null when adding. */
@Immutable
data class RuleDraft(
    val kind: RuleKind,
    val uid: Int,
    val editing: RuleItem? = null,
    val input: String = editing?.editorInput.orEmpty(),
    val domainType: DomainRulesManager.DomainType =
        if (editing?.wildcard == true) DomainRulesManager.DomainType.WILDCARD else DomainRulesManager.DomainType.DOMAIN,
    val action: RuleAction = editing?.action ?: RuleAction.BLOCK,
    /** "" = no proxy override */
    val proxyId: String = editing?.proxyId.orEmpty(),
    /** string resource of the validation error, if any */
    val error: Int? = null,
    val saving: Boolean = false,
) {
    val isEdit: Boolean get() = editing != null

    /** Typing "*." or "." switches a domain rule to wildcard, and back. */
    fun withInput(text: String): RuleDraft = when (kind) {
        RuleKind.DOMAIN -> copy(
            input = text, error = null,
            domainType = if (text.startsWith("*") || text.startsWith(".")) DomainRulesManager.DomainType.WILDCARD
            else DomainRulesManager.DomainType.DOMAIN,
        )
        RuleKind.IP -> copy(input = text, error = null)
    }

    val canSave: Boolean get() = input.isNotBlank() && !saving
}

object RulesLogic {

    fun actionOf(kind: RuleKind, status: Int): RuleAction = when (kind) {
        RuleKind.DOMAIN -> when (DomainRulesManager.Status.getStatus(status)) {
            DomainRulesManager.Status.NONE -> RuleAction.NONE
            DomainRulesManager.Status.BLOCK -> RuleAction.BLOCK
            DomainRulesManager.Status.TRUST -> RuleAction.TRUST
        }
        RuleKind.IP -> when (IpRuleStatus.getStatus(status)) {
            IpRuleStatus.NONE -> RuleAction.NONE
            IpRuleStatus.BLOCK -> RuleAction.BLOCK
            IpRuleStatus.TRUST -> RuleAction.TRUST
            IpRuleStatus.BYPASS_UNIVERSAL -> RuleAction.BYPASS_UNIVERSAL
        }
    }

    fun ipStatus(action: RuleAction): IpRuleStatus = when (action) {
        RuleAction.NONE -> IpRuleStatus.NONE
        RuleAction.BLOCK -> IpRuleStatus.BLOCK
        RuleAction.TRUST -> IpRuleStatus.TRUST
        RuleAction.BYPASS_UNIVERSAL -> IpRuleStatus.BYPASS_UNIVERSAL
    }

    /** Domains have no bypass-universal; it maps to trust (never offered for domains). */
    fun domainStatus(action: RuleAction): DomainRulesManager.Status = when (action) {
        RuleAction.NONE -> DomainRulesManager.Status.NONE
        RuleAction.BLOCK -> DomainRulesManager.Status.BLOCK
        RuleAction.TRUST, RuleAction.BYPASS_UNIVERSAL -> DomainRulesManager.Status.TRUST
    }

    /**
     * Actions offered for a rule of [kind] and [uid], as before the redesign: universal IP
     * rules trust by bypassing the universal firewall ("Bypass universal"); app IP rules and
     * domain rules "Trust". [current] is added if it is not among them (e.g. set on TV).
     */
    fun actionOptions(kind: RuleKind, uid: Int, current: RuleAction? = null): List<RuleAction> {
        val trust = if (kind == RuleKind.IP && uid == UID_EVERYBODY) RuleAction.BYPASS_UNIVERSAL else RuleAction.TRUST
        val base = listOf(trust, RuleAction.BLOCK, RuleAction.NONE)
        return if (current == null || current in base) base else base + current
    }

    /** The trust-like action for a new rule of [kind] / [uid] (debug import, old dialog). */
    fun trustAction(kind: RuleKind, uid: Int): RuleAction = actionOptions(kind, uid).first()

    fun deleteAllScope(scope: RulesScope): DeleteAllScope =
        if (scope.allApps) DeleteAllScope.ALL_APP_RULES else DeleteAllScope.ONE_UID

    /** The rule's proxy as the picker / rows know it; null when the rule has no proxyId. */
    fun proxyChoice(proxyId: String, proxies: List<ProxyChoices.Choice>?): ProxyChoices.Choice? {
        if (proxyId.isBlank()) return null
        val known = proxies?.firstOrNull { it.proxyId == proxyId }
        return known ?: ProxyChoices.withUnknown(emptyList(), listOf(proxyId)).first()
    }

    /** A note to show when the action makes the proxy moot (blocked flows are never routed). */
    fun proxyIgnored(action: RuleAction, proxyId: String): Boolean =
        action == RuleAction.BLOCK && proxyId.isNotBlank()

    /** An app header goes above the first row of each uid in a grouped list. */
    fun showHeader(grouped: Boolean, index: Int, previousUid: Int?, uid: Int) =
        grouped && (index == 0 || previousUid != uid)
}

internal fun ruleUid(item: Serializable): Int = when (item) {
    is CustomIp -> item.uid
    is CustomDomain -> item.uid
    else -> error("Unknown rule")
}

internal fun ruleKey(item: Serializable): String = when (item) {
    is CustomIp -> "ip:${item.uid}:${item.ipAddress}:${item.port}:${item.protocol}"
    is CustomDomain -> "domain:${item.uid}:${item.domain}"
    else -> error("Unknown rule")
}
