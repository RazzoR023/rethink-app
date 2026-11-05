/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.rules

import android.net.Uri
import com.celzero.bravedns.R
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.IpRulesManager.IpRuleStatus
import com.celzero.bravedns.service.ProxyChoices
import com.celzero.bravedns.shadows.ShadowBackend
import com.celzero.bravedns.ui.fragment.RulesImportHelper
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.util.Utilities
import inet.ipaddr.IPAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CustomRuleActions against an in-memory port: the editor saves with its proxy, validation
 * errors stay in the dialog, Trust / Block / None keep the proxy and Route via keeps the action,
 * and "Delete all" removes what the scope says.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [ShadowBackend::class])
class CustomRuleActionsTest {

    private val app = 10042

    /** In-memory rules; validation is the real (static) code. */
    private class FakePort : CustomRulePort {
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

        override suspend fun appName(uid: Int) = "App $uid"
        override suspend fun proxies() = listOf(
            ProxyChoices.Choice("wg1", ProxyChoices.Kind.WIREGUARD, "wg-one", enabled = true, health = ProxyChoices.Health.UP),
        )
        override suspend fun parseImport(uri: Uri, type: RulesImportHelper.ImportType): RulesImportHelper.ParsedFile? = null
        override suspend fun importRules(entries: List<String>, importType: RulesImportHelper.ImportType, uid: Int,
                                         ipStatus: IpRuleStatus, domainStatus: DomainRulesManager.Status) =
            RulesImportHelper.ImportSummary(0, 0, 0)
    }

    private val port = FakePort()
    private val notices = mutableListOf<Int>()

    private fun actions(state: RulesScreenState) = CustomRuleActions(
        state = state,
        scope = CoroutineScope(Dispatchers.Unconfined),
        port = port,
        notify = { id, _ -> notices += id },
        log = { _, _ -> },
        ioDispatcher = Dispatchers.Unconfined,
        mainDispatcher = Dispatchers.Unconfined,
    )

    private fun stateFor(uid: Int = app, allApps: Boolean = false) = RulesScreenState(RulesScope(uid, allApps))

    private fun join(a: CustomRuleActions, f: CustomRuleActions.() -> kotlinx.coroutines.Job?) = runBlocking { a.f()?.join() }

    // ---- add / edit ----

    @Test fun addIp_withProxy_savesAndCloses() {
        val s = stateFor()
        val a = actions(s)
        s.openEditor(RuleKind.IP)
        s.updateDraft { it.withInput("8.8.8.8:53").copy(action = RuleAction.NONE, proxyId = "wg1") }
        join(a) { saveDraft() }
        val stored = port.ips[Triple(app, "8.8.8.8", 53)]
        assertNotNull(stored)
        assertEquals("wg1", stored!!.proxyId)
        assertEquals(IpRuleStatus.NONE.id, stored.status)
        assertNull(s.dialog)
        assertEquals(listOf(R.string.ci_dialog_added_success), notices)
    }

    @Test fun addIp_withoutProxy_passesNullSoAnExistingProxyIsKept() {
        val s = stateFor()
        val a = actions(s)
        s.openEditor(RuleKind.IP)
        s.updateDraft { it.withInput("1.1.1.1") }
        join(a) { saveDraft() }
        assertEquals(listOf("addIp:null"), port.calls)
    }

    @Test fun addIp_invalid_showsErrorAndStaysOpen() {
        val s = stateFor()
        val a = actions(s)
        s.openEditor(RuleKind.IP)
        s.updateDraft { it.withInput("not-an-ip") }
        join(a) { saveDraft() }
        assertEquals(R.string.ci_dialog_error_invalid_ip, s.draft!!.error)
        assertTrue(port.ips.isEmpty())
    }

    @Test fun editIp_changesProxyAndFollowsInDetails() {
        val s = stateFor()
        val a = actions(s)
        runBlocking { port.addIp(app, IpRulesManager.getIpNetPort("1.1.1.1").first!!, 0, IpRuleStatus.BLOCK, "S5") }
        val item = RuleItem.of(port.ips.values.first())
        s.details = item
        s.openEditor(RuleKind.IP, item)
        s.updateDraft { it.withInput("1.1.1.1:443").copy(proxyId = "") }
        join(a) { saveDraft() }
        val stored = port.ips[Triple(app, "1.1.1.1", 443)]!!
        assertEquals("", stored.proxyId)
        assertEquals(IpRuleStatus.BLOCK.id, stored.status)
        assertEquals("1.1.1.1:443", s.details!!.target)
        assertNull(s.dialog)
    }

    @Test fun addDomain_wildcardWithProxy() {
        val s = stateFor(UID_EVERYBODY)
        val a = actions(s)
        s.openEditor(RuleKind.DOMAIN)
        s.updateDraft { it.withInput("*.example.com").copy(action = RuleAction.TRUST, proxyId = "S5") }
        join(a) { saveDraft() }
        val stored = port.domains[UID_EVERYBODY to "*.example.com"]!!
        assertEquals("S5", stored.proxyId)
        assertEquals(DomainRulesManager.DomainType.WILDCARD.id, stored.type)
        assertEquals(DomainRulesManager.Status.TRUST.id, stored.status)
        assertNull(s.dialog)
    }

    @Test fun addDomain_invalid_showsErrorAndStaysOpen() {
        val s = stateFor()
        val a = actions(s)
        s.openEditor(RuleKind.DOMAIN)
        s.updateDraft { it.withInput("not a domain!") }
        join(a) { saveDraft() }
        assertEquals(R.string.cd_dialog_error_invalid_domain, s.draft!!.error)
        assertTrue(port.domains.isEmpty())
    }

    // ---- action and route are independent ----

    @Test fun setAction_keepsProxy_andRefreshesDetails() {
        val s = stateFor()
        val a = actions(s)
        runBlocking { port.addDomain("a.com", DomainRulesManager.Status.TRUST, DomainRulesManager.DomainType.DOMAIN, app, "wg1") }
        val item = RuleItem.of(port.domains.values.first())
        s.details = item
        join(a) { setAction(item, RuleAction.BLOCK) }
        assertEquals(DomainRulesManager.Status.BLOCK.id, port.domains[app to "a.com"]!!.status)
        assertEquals("wg1", port.domains[app to "a.com"]!!.proxyId)
        assertEquals(RuleAction.BLOCK, s.details!!.action)
        assertEquals("wg1", s.details!!.proxyId)
    }

    @Test fun setAction_sameAction_isANoOp() {
        val s = stateFor()
        val a = actions(s)
        runBlocking { port.addDomain("a.com", DomainRulesManager.Status.TRUST, DomainRulesManager.DomainType.DOMAIN, app, null) }
        assertNull(a.setAction(RuleItem.of(port.domains.values.first()), RuleAction.TRUST))
    }

    @Test fun setProxy_keepsAction_andClears() {
        val s = stateFor()
        val a = actions(s)
        runBlocking { port.addIp(app, IpRulesManager.getIpNetPort("1.1.1.1").first!!, 0, IpRuleStatus.TRUST, null) }
        val item = RuleItem.of(port.ips.values.first())
        s.details = item
        join(a) { setProxy(item, "wg1") }
        assertEquals("wg1", port.ips.values.first().proxyId)
        assertEquals(IpRuleStatus.TRUST.id, port.ips.values.first().status)
        assertEquals("wg1", s.details!!.proxyId)
        join(a) { setProxy(s.details!!, "") }
        assertEquals("", port.ips.values.first().proxyId)
    }

    // ---- delete ----

    @Test fun deleteAll_oneUid_onlyThatUid() {
        val s = stateFor(UID_EVERYBODY)
        val a = actions(s)
        join(a) { deleteAll(RuleKind.IP) }
        join(a) { deleteAll(RuleKind.DOMAIN) }
        assertEquals(listOf("deleteIpsByUid:$UID_EVERYBODY", "deleteDomainsByUid:$UID_EVERYBODY"), port.calls)
    }

    @Test fun deleteAll_groupedList_removesAppRulesOnly() {
        val s = stateFor(UID_EVERYBODY, allApps = true)
        val a = actions(s)
        runBlocking {
            port.addDomain("u.com", DomainRulesManager.Status.BLOCK, DomainRulesManager.DomainType.DOMAIN, UID_EVERYBODY, null)
            port.addDomain("a.com", DomainRulesManager.Status.BLOCK, DomainRulesManager.DomainType.DOMAIN, app, null)
        }
        join(a) { deleteAll(RuleKind.DOMAIN) }
        assertEquals(setOf(UID_EVERYBODY to "u.com"), port.domains.keys)
        join(a) { deleteAll(RuleKind.IP) }
        assertTrue("deleteAllAppIps" in port.calls)
    }

    @Test fun deleteSelected_removesSelectionAndClosesItsDetails() {
        val s = stateFor()
        val a = actions(s)
        runBlocking {
            port.addDomain("a.com", DomainRulesManager.Status.BLOCK, DomainRulesManager.DomainType.DOMAIN, app, null)
            port.addDomain("b.com", DomainRulesManager.Status.BLOCK, DomainRulesManager.DomainType.DOMAIN, app, null)
        }
        val first = RuleItem.of(port.domains[app to "a.com"]!!)
        s.tab(RuleKind.DOMAIN).toggle(first)
        s.details = first
        join(a) { deleteSelected(RuleKind.DOMAIN) }
        assertEquals(setOf(app to "b.com"), port.domains.keys)
        assertNull(s.details)
        assertTrue(s.tab(RuleKind.DOMAIN).selected.isEmpty())
        assertEquals(listOf(R.string.rules_deleted_n), notices)
    }

    @Test fun delete_one() {
        val s = stateFor()
        val a = actions(s)
        runBlocking { port.addIp(app, IpRulesManager.getIpNetPort("1.1.1.1").first!!, 0, IpRuleStatus.BLOCK, null) }
        val item = RuleItem.of(port.ips.values.first())
        s.dialog = RulesDialog.ConfirmDelete(item)
        join(a) { delete(item) }
        assertTrue(port.ips.isEmpty())
        assertNull(s.dialog)
    }

    // ---- lookups ----

    @Test fun refreshProxies_andAppName() {
        val s = stateFor()
        val a = actions(s)
        runBlocking { a.refreshProxies() }
        assertEquals("wg1", s.proxies!!.single().proxyId)
        a.loadAppName(app)
        assertEquals("App $app", s.appNames[app])
    }
}
