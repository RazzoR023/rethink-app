/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.rules

import android.net.Uri
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.IpRulesManager.IpRuleStatus
import com.celzero.bravedns.service.ProxyChoices
import com.celzero.bravedns.ui.fragment.RulesImportHelper
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.util.Utilities
import inet.ipaddr.IPAddress

/** In-memory rules; validation is the real (static) code. */
internal class FakePort : CustomRulePort {
    val ips = linkedMapOf<Triple<Int, String, Int>, CustomIp>()
    val domains = linkedMapOf<Pair<Int, String>, CustomDomain>()
    val calls = mutableListOf<String>()

    override fun normalize(input: String) = Utilities.removeLeadingAndTrailingDots(input)
    override fun parseIp(input: String) = IpRulesManager.getIpNetPort(input)
    override fun isCidrEnforceable(ip: IPAddress?) = IpRulesManager.isCidrEnforceable(ip)
    override fun extractHost(input: String) = DomainRulesManager.extractHost(input)
    override fun isWildcard(host: String) = DomainRulesManager.isWildCardEntry(host)
    override fun isDomain(host: String) = DomainRulesManager.isValidDomain(host)

    private fun putIp(uid: Int, ip: String, port: Int, status: IpRuleStatus, proxyId: String) {
        ips[Triple(uid, ip, port)] = CustomIp().apply {
            this.uid = uid; ipAddress = ip; this.port = port; this.status = status.id; this.proxyId = proxyId
        }
    }

    override suspend fun addIp(uid: Int, ip: IPAddress, port: Int, status: IpRuleStatus, proxyId: String?) {
        calls += "addIp:$proxyId"
        val prev = ips[Triple(uid, ip.toNormalizedString(), port)]
        putIp(uid, ip.toNormalizedString(), port, status, proxyId ?: prev?.proxyId.orEmpty())
    }

    override suspend fun replaceIp(previous: CustomIp, ip: IPAddress, port: Int, status: IpRuleStatus, proxyId: String?): CustomIp? {
        calls += "replaceIp:$proxyId"
        ips.remove(Triple(previous.uid, previous.ipAddress, previous.port))
        putIp(previous.uid, ip.toNormalizedString(), port, status, proxyId ?: previous.proxyId)
        return ips[Triple(previous.uid, ip.toNormalizedString(), port)]
    }

    override suspend fun addDomain(domain: String, status: DomainRulesManager.Status, type: DomainRulesManager.DomainType, uid: Int, proxyId: String?) {
        calls += "addDomain:$proxyId"
        val prev = domains[uid to domain]
        domains[uid to domain] = CustomDomain(domain, uid, "", type.id, status.id, proxyId ?: prev?.proxyId.orEmpty(), "", 1L, 0L, 1L)
    }

    override suspend fun replaceDomain(domain: String, status: DomainRulesManager.Status, type: DomainRulesManager.DomainType, previous: CustomDomain, proxyId: String?) {
        calls += "replaceDomain:$proxyId"
        domains.remove(previous.uid to previous.domain)
        domains[previous.uid to domain] = CustomDomain(domain, previous.uid, "", type.id, status.id, proxyId ?: previous.proxyId, "", 1L, 0L, 1L)
    }

    // like the managers: status writes read the stored proxy, proxy writes the stored status
    override suspend fun setIpStatus(ip: CustomIp, status: IpRuleStatus) {
        ips[Triple(ip.uid, ip.ipAddress, ip.port)]!!.status = status.id
    }
    override suspend fun setDomainStatus(domain: CustomDomain, status: DomainRulesManager.Status) {
        domains[domain.uid to domain.domain]!!.status = status.id
    }
    override suspend fun setIpProxy(ip: CustomIp, proxyId: String) {
        ips[Triple(ip.uid, ip.ipAddress, ip.port)]!!.proxyId = proxyId
    }
    override suspend fun setDomainProxy(domain: CustomDomain, proxyId: String) {
        domains[domain.uid to domain.domain]!!.proxyId = proxyId
    }
    override suspend fun getIp(uid: Int, ip: String, port: Int) = ips[Triple(uid, ip, port)]?.deepCopy()
    override suspend fun getDomain(uid: Int, domain: String) = domains[uid to domain]?.let {
        CustomDomain(it.domain, it.uid, it.ips, it.type, it.status, it.proxyId, it.proxyCC, it.modifiedTs, it.deletedTs, it.version)
    }

    override suspend fun deleteIp(ip: CustomIp) { ips.remove(Triple(ip.uid, ip.ipAddress, ip.port)) }
    override suspend fun deleteDomain(domain: CustomDomain) { domains.remove(domain.uid to domain.domain) }
    override suspend fun deleteIps(items: List<CustomIp>) { items.forEach { deleteIp(it) } }
    override suspend fun deleteIpsByUid(uid: Int) { calls += "deleteIpsByUid:$uid"; ips.keys.removeAll { it.first == uid } }
    override suspend fun deleteAllAppIps() { calls += "deleteAllAppIps"; ips.keys.removeAll { it.first != UID_EVERYBODY } }
    override suspend fun deleteDomains(items: List<CustomDomain>) { items.forEach { deleteDomain(it) } }
    override suspend fun deleteDomainsByUid(uid: Int) { calls += "deleteDomainsByUid:$uid"; domains.keys.removeAll { it.first == uid } }
    override suspend fun deleteAllAppDomains() { calls += "deleteAllAppDomains"; domains.keys.removeAll { it.first != UID_EVERYBODY } }

    /** a uid with no installed app */
    val missingUid = 99999

    override suspend fun appName(uid: Int) = if (uid == missingUid) null else "App $uid"
    override suspend fun proxies() = listOf(
        ProxyChoices.Choice("wg1", ProxyChoices.Kind.WIREGUARD, "wg-one", enabled = true, health = ProxyChoices.Health.UP),
    )
    override suspend fun parseImport(uri: Uri, type: RulesImportHelper.ImportType): RulesImportHelper.ParsedFile? = null
    override suspend fun importRules(entries: List<String>, importType: RulesImportHelper.ImportType, uid: Int,
                                     ipStatus: IpRuleStatus, domainStatus: DomainRulesManager.Status) =
        RulesImportHelper.ImportSummary(0, 0, 0)
}
