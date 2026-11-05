/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.rules

import android.content.Context
import android.net.Uri
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.ProxyChoices
import com.celzero.bravedns.ui.fragment.RulesImportHelper
import com.celzero.bravedns.util.Utilities
import inet.ipaddr.IPAddress

/**
 * Everything the rules screen reads and writes, behind one fakeable boundary. Production
 * delegates to IpRulesManager / DomainRulesManager, which keep a rule's proxy across status
 * changes and refresh the routing caches on every write.
 */
internal interface CustomRulePort {
    fun normalize(input: String): String
    fun parseIp(input: String): Pair<IPAddress?, Int>
    fun isCidrEnforceable(ip: IPAddress?): Boolean
    fun extractHost(input: String): String?
    fun isWildcard(host: String): Boolean
    fun isDomain(host: String): Boolean

    /** [proxyId]: null keeps an existing rule's proxy, "" clears it */
    suspend fun addIp(uid: Int, ip: IPAddress, port: Int, status: IpRulesManager.IpRuleStatus, proxyId: String?)
    suspend fun replaceIp(previous: CustomIp, ip: IPAddress, port: Int, status: IpRulesManager.IpRuleStatus, proxyId: String?): CustomIp?
    suspend fun addDomain(domain: String, status: DomainRulesManager.Status, type: DomainRulesManager.DomainType, uid: Int, proxyId: String?)
    suspend fun replaceDomain(domain: String, status: DomainRulesManager.Status, type: DomainRulesManager.DomainType, previous: CustomDomain, proxyId: String?)

    suspend fun setIpStatus(ip: CustomIp, status: IpRulesManager.IpRuleStatus)
    suspend fun setDomainStatus(domain: CustomDomain, status: DomainRulesManager.Status)
    suspend fun setIpProxy(ip: CustomIp, proxyId: String)
    suspend fun setDomainProxy(domain: CustomDomain, proxyId: String)
    suspend fun getIp(uid: Int, ip: String, port: Int): CustomIp?
    suspend fun getDomain(uid: Int, domain: String): CustomDomain?

    suspend fun deleteIp(ip: CustomIp)
    suspend fun deleteDomain(domain: CustomDomain)
    suspend fun deleteIps(items: List<CustomIp>)
    suspend fun deleteIpsByUid(uid: Int)
    suspend fun deleteAllAppIps()
    suspend fun deleteDomains(items: List<CustomDomain>)
    suspend fun deleteDomainsByUid(uid: Int)
    suspend fun deleteAllAppDomains()

    suspend fun appName(uid: Int): String?
    suspend fun proxies(): List<ProxyChoices.Choice>

    suspend fun parseImport(uri: Uri, type: RulesImportHelper.ImportType): RulesImportHelper.ParsedFile?
    suspend fun importRules(entries: List<String>, importType: RulesImportHelper.ImportType, uid: Int,
        ipStatus: IpRulesManager.IpRuleStatus = IpRulesManager.IpRuleStatus.BLOCK,
        domainStatus: DomainRulesManager.Status = DomainRulesManager.Status.BLOCK): RulesImportHelper.ImportSummary
}

internal class ManagerCustomRulePort(private val context: Context) : CustomRulePort {
    private val choices = ProxyChoices()

    override fun normalize(input: String) = Utilities.removeLeadingAndTrailingDots(input)
    override fun parseIp(input: String) = IpRulesManager.getIpNetPort(input)
    override fun isCidrEnforceable(ip: IPAddress?) = IpRulesManager.isCidrEnforceable(ip)
    override fun extractHost(input: String) = DomainRulesManager.extractHost(input)
    override fun isWildcard(host: String) = DomainRulesManager.isWildCardEntry(host)
    override fun isDomain(host: String) = DomainRulesManager.isValidDomain(host)

    override suspend fun addIp(uid: Int, ip: IPAddress, port: Int, status: IpRulesManager.IpRuleStatus, proxyId: String?) {
        IpRulesManager.addIpRule(uid, ip, port, status, proxyId)
    }
    override suspend fun replaceIp(previous: CustomIp, ip: IPAddress, port: Int, status: IpRulesManager.IpRuleStatus, proxyId: String?) =
        IpRulesManager.replaceIpRule(previous, ip, port, status, proxyId)
    override suspend fun addDomain(domain: String, status: DomainRulesManager.Status, type: DomainRulesManager.DomainType, uid: Int, proxyId: String?) {
        DomainRulesManager.addDomainRule(domain, status, type, uid, proxyId)
    }
    override suspend fun replaceDomain(domain: String, status: DomainRulesManager.Status, type: DomainRulesManager.DomainType, previous: CustomDomain, proxyId: String?) {
        DomainRulesManager.updateDomainRule(domain, status, type, previous, proxyId)
    }

    override suspend fun setIpStatus(ip: CustomIp, status: IpRulesManager.IpRuleStatus) {
        when (status) {
            IpRulesManager.IpRuleStatus.NONE -> IpRulesManager.updateNoRule(ip)
            IpRulesManager.IpRuleStatus.BLOCK -> IpRulesManager.updateBlock(ip)
            IpRulesManager.IpRuleStatus.TRUST -> IpRulesManager.updateTrust(ip)
            IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL -> IpRulesManager.updateBypass(ip)
        }
    }
    override suspend fun setDomainStatus(domain: CustomDomain, status: DomainRulesManager.Status) {
        when (status) {
            DomainRulesManager.Status.NONE -> DomainRulesManager.noRule(domain)
            DomainRulesManager.Status.BLOCK -> DomainRulesManager.block(domain)
            DomainRulesManager.Status.TRUST -> DomainRulesManager.trust(domain)
        }
    }
    override suspend fun setIpProxy(ip: CustomIp, proxyId: String) { IpRulesManager.setProxy(ip, proxyId) }
    override suspend fun setDomainProxy(domain: CustomDomain, proxyId: String) { DomainRulesManager.setProxy(domain, proxyId) }
    override suspend fun getIp(uid: Int, ip: String, port: Int) = IpRulesManager.getObj(uid, ip, port)
    override suspend fun getDomain(uid: Int, domain: String) = DomainRulesManager.getObj(uid, domain)

    override suspend fun deleteIp(ip: CustomIp) { IpRulesManager.removeIpRule(ip.uid, ip.ipAddress, ip.port) }
    override suspend fun deleteDomain(domain: CustomDomain) { DomainRulesManager.deleteDomain(domain) }
    override suspend fun deleteIps(items: List<CustomIp>) { IpRulesManager.deleteRules(items) }
    override suspend fun deleteIpsByUid(uid: Int) { IpRulesManager.deleteRulesByUid(uid) }
    override suspend fun deleteAllAppIps() { IpRulesManager.deleteAllAppsRules() }
    override suspend fun deleteDomains(items: List<CustomDomain>) { DomainRulesManager.deleteRules(items) }
    override suspend fun deleteDomainsByUid(uid: Int) { DomainRulesManager.deleteRulesByUid(uid) }
    override suspend fun deleteAllAppDomains() { DomainRulesManager.deleteAllAppsRules() }

    override suspend fun appName(uid: Int): String? {
        val names = FirewallManager.getAppNamesByUid(uid)
        return when {
            names.isEmpty() -> null
            names.size == 1 -> names[0]
            else -> context.getString(com.celzero.bravedns.R.string.ctbs_app_other_apps, names[0], (names.size - 1).toString())
        }
    }
    override suspend fun proxies() = choices.list()

    override suspend fun parseImport(uri: Uri, type: RulesImportHelper.ImportType) = RulesImportHelper.parseFile(context, uri, type)
    override suspend fun importRules(entries: List<String>, importType: RulesImportHelper.ImportType, uid: Int, ipStatus: IpRulesManager.IpRuleStatus, domainStatus: DomainRulesManager.Status) =
        RulesImportHelper.importRules(entries, importType, uid, ipStatus, domainStatus)
}
