/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.rules

import com.celzero.bravedns.R
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.FakeDomainStore
import com.celzero.bravedns.service.FakeIpStore
import com.celzero.bravedns.service.FakeIpTree
import com.celzero.bravedns.service.FakeRadixTree
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.IpRulesManager.IpRuleStatus
import com.celzero.bravedns.service.ProxyChoices
import com.celzero.bravedns.shadows.ShadowBackend
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import inet.ipaddr.IPAddressString
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rule sheets end to end, minus Compose: RuleSheetActions + ManagerRuleSheetPort over the
 * real IpRulesManager / DomainRulesManager with in-memory stores. Trust / Block / None never
 * touch the proxy and Route via never touches the status; set / clear work for IPs and domains
 * with and without an existing rule, per app and for all apps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [ShadowBackend::class])
class RuleSheetActionsTest {

    private val app = 10042
    private lateinit var ips: FakeIpStore
    private lateinit var domains: FakeDomainStore
    private lateinit var state: RuleSheetState
    private val notified = mutableListOf<Int>()
    private val logged = mutableListOf<String>()

    private val proxies = listOf(ProxyChoices.Choice("S5", ProxyChoices.Kind.SOCKS5, "", enabled = true, health = ProxyChoices.Health.UP))

    private lateinit var actions: RuleSheetActions

    @Before
    fun setUp() {
        ips = FakeIpStore()
        domains = FakeDomainStore()
        IpRulesManager.overrideForTest(ips.repo, FakeIpTree())
        DomainRulesManager.overrideForTest(domains.repo, FakeRadixTree(), FakeRadixTree())
        state = RuleSheetState()
        val choices = mockk<ProxyChoices> { coEvery { list() } returns proxies }
        actions = RuleSheetActions(
            state = state,
            scope = CoroutineScope(Dispatchers.Unconfined),
            port = ManagerRuleSheetPort(choices),
            notify = { notified += it },
            log = { logged += it },
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        IpRulesManager.overrideForTest(null, null)
        DomainRulesManager.overrideForTest(null, null, null)
    }

    private fun join(f: RuleSheetActions.() -> Job?) = runBlocking { actions.f()?.join() }

    private fun load(rows: List<RuleSheetRow>) = join { load(rows) }

    private fun row(key: String) = state.row(key)!!

    private fun ipRow(uid: Int = app, ip: String = "1.2.3.4") = row("IP:$uid:$ip")

    private fun domainRow(uid: Int = app, d: String = "example.com") = row("DOMAIN:$uid:$d")

    private fun addr(s: String) = IPAddressString(s).address!!

    @Test
    fun `load reads the stored action and proxy of each row`() {
        runBlocking {
            IpRulesManager.addIpRule(app, addr("1.2.3.4"), 0, IpRuleStatus.BLOCK, "S5")
            DomainRulesManager.addDomainRule("example.com", DomainRulesManager.Status.TRUST, DomainRulesManager.DomainType.DOMAIN, app, "wg1")
        }
        load(RuleSheetLogic.ipSheetRows(app, "1.2.3.4", "example.com,other.com"))
        assertEquals(RuleAction.BLOCK, ipRow().action)
        assertEquals("S5", ipRow().proxyId)
        assertEquals(RuleAction.TRUST, domainRow().action)
        assertEquals("wg1", domainRow().proxyId)
        assertEquals(RuleAction.NONE, domainRow(d = "other.com").action)
        assertEquals("", domainRow(d = "other.com").proxyId)
        assertTrue(state.rows.all { it.loaded })
    }

    @Test
    fun `route via on an ip without a rule creates a route-only rule`() {
        load(RuleSheetLogic.ipSheetRows(app, "1.2.3.4", null))
        join { setProxy(ipRow(), "S5") }
        val s = ips.get(app, "1.2.3.4", 0)!!
        assertEquals("S5", s.proxyId)
        assertEquals(IpRuleStatus.NONE.id, s.status)
        assertEquals(Pair("S5", ""), IpRulesManager.getProxyForIpPort(app, "1.2.3.4", 0))
        assertEquals("S5", ipRow().proxyId)
        assertEquals(RuleAction.NONE, ipRow().action)
    }

    @Test
    fun `ip status changes keep the proxy and proxy changes keep the status`() {
        load(RuleSheetLogic.ipSheetRows(app, "1.2.3.4", null))
        join { setProxy(ipRow(), "S5") }
        join { setAction(ipRow(), RuleAction.BLOCK) }
        assertEquals("S5", ips.get(app, "1.2.3.4", 0)!!.proxyId)
        assertEquals(IpRuleStatus.BLOCK.id, ips.get(app, "1.2.3.4", 0)!!.status)
        join { setAction(ipRow(), RuleAction.TRUST) }
        join { setAction(ipRow(), RuleAction.NONE) }
        assertEquals("S5", ips.get(app, "1.2.3.4", 0)!!.proxyId)

        join { setAction(ipRow(), RuleAction.TRUST) }
        join { setProxy(ipRow(), "") }
        val s = ips.get(app, "1.2.3.4", 0)!!
        assertEquals("", s.proxyId)
        assertEquals(IpRuleStatus.TRUST.id, s.status)
        assertEquals(RuleAction.TRUST, ipRow().action)
        assertEquals(Pair("", ""), IpRulesManager.getProxyForIpPort(app, "1.2.3.4", 0))
    }

    @Test
    fun `route via on a domain without a rule creates a route-only rule`() {
        load(RuleSheetLogic.domainSheetRows(app, "example.com"))
        join { setProxy(domainRow(), "S5") }
        val s = domains.get(app, "example.com")!!
        assertEquals("S5", s.proxyId)
        assertEquals(DomainRulesManager.Status.NONE.id, s.status)
        assertEquals(Pair("S5", ""), DomainRulesManager.getProxyForDomain(app, "example.com"))
    }

    @Test
    fun `domain status changes keep the proxy and proxy changes keep the status`() {
        load(RuleSheetLogic.ipSheetRows(app, "1.2.3.4", "example.com"))
        join { setAction(domainRow(), RuleAction.BLOCK) }
        join { setProxy(domainRow(), "wg1") }
        assertEquals(DomainRulesManager.Status.BLOCK.id, domains.get(app, "example.com")!!.status)
        assertEquals("wg1", domains.get(app, "example.com")!!.proxyId)

        join { setAction(domainRow(), RuleAction.TRUST) }
        assertEquals("wg1", domains.get(app, "example.com")!!.proxyId)
        join { setProxy(domainRow(), "") }
        assertEquals(DomainRulesManager.Status.TRUST.id, domains.get(app, "example.com")!!.status)
        assertEquals("", domains.get(app, "example.com")!!.proxyId)
        // the ip row was not touched
        assertNull(ips.get(app, "1.2.3.4", 0))
    }

    @Test
    fun `app and universal rules are separate`() {
        runBlocking { IpRulesManager.addIpRule(UID_EVERYBODY, addr("1.2.3.4"), 0, IpRuleStatus.BLOCK, "wg9") }
        load(RuleSheetLogic.ipSheetRows(app, "1.2.3.4", "example.com") + RuleSheetLogic.ipSheetRows(UID_EVERYBODY, "1.2.3.4", "example.com"))
        // the universal rule is not the app's
        assertEquals(RuleAction.NONE, ipRow().action)
        assertEquals("", ipRow().proxyId)
        assertEquals(RuleAction.BLOCK, ipRow(UID_EVERYBODY).action)
        assertEquals("wg9", ipRow(UID_EVERYBODY).proxyId)

        join { setProxy(ipRow(), "S5") }
        join { setProxy(domainRow(UID_EVERYBODY), "S5") }
        join { setAction(ipRow(UID_EVERYBODY), RuleAction.BYPASS_UNIVERSAL) }
        assertEquals("S5", ips.get(app, "1.2.3.4", 0)!!.proxyId)
        assertEquals("wg9", ips.get(UID_EVERYBODY, "1.2.3.4", 0)!!.proxyId)
        assertEquals(IpRuleStatus.BYPASS_UNIVERSAL.id, ips.get(UID_EVERYBODY, "1.2.3.4", 0)!!.status)
        assertEquals("S5", domains.get(UID_EVERYBODY, "example.com")!!.proxyId)
        assertNull(domains.get(app, "example.com"))
    }

    @Test
    fun `an ip that is not CIDR-able is refused with a message and nothing is stored`() {
        load(listOf(RuleSheetRow(RuleKind.IP, app, "1.2.*.4")))
        join { setProxy(ipRow(ip = "1.2.*.4"), "S5") }
        join { setAction(ipRow(ip = "1.2.*.4"), RuleAction.BLOCK) }
        assertTrue(ips.rows.isEmpty())
        assertEquals(listOf(R.string.ci_dialog_error_invalid_cidr, R.string.ci_dialog_error_invalid_cidr), notified)
        // the row shows what is stored again
        assertEquals(RuleAction.NONE, ipRow(ip = "1.2.*.4").action)
        assertEquals("", ipRow(ip = "1.2.*.4").proxyId)
    }

    @Test
    fun `locked bypass universal on the connection sheet is not changed, its proxy is`() {
        runBlocking { IpRulesManager.addIpRule(app, addr("1.2.3.4"), 0, IpRuleStatus.BYPASS_UNIVERSAL) }
        load(RuleSheetLogic.connectionRows(app, "1.2.3.4", null))
        assertNull(actions.setAction(ipRow(), RuleAction.BLOCK))
        join { setProxy(ipRow(), "S5") }
        val s = ips.get(app, "1.2.3.4", 0)!!
        assertEquals(IpRuleStatus.BYPASS_UNIVERSAL.id, s.status)
        assertEquals("S5", s.proxyId)
    }

    @Test
    fun `writes are logged, no-ops are not`() {
        load(RuleSheetLogic.domainSheetRows(app, "example.com"))
        assertNull(actions.setAction(domainRow(), RuleAction.NONE))
        assertNull(actions.setProxy(domainRow(), ""))
        assertTrue(logged.isEmpty())
        join { setAction(domainRow(), RuleAction.BLOCK) }
        join { setProxy(domainRow(), "S5") }
        assertEquals(2, logged.size)
    }

    @Test
    fun `proxies are read for names and status chips`() = runBlocking {
        assertNull(state.proxies)
        actions.refreshProxies()
        assertEquals(proxies, state.proxies)
    }
}
