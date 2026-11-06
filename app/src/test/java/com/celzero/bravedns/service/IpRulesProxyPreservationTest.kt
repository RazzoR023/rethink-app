/*
 * Copyright 2026 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.service

import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.service.IpRulesManager.IpRuleStatus
import com.celzero.bravedns.shadows.ShadowBackend
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import inet.ipaddr.IPAddressString
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * IpRulesManager against an in-memory db and ip tree:
 *  - status changes keep a rule's proxy (also from a stale CustomIp copy);
 *  - setProxy / clearProxy set and clear it without touching the status;
 *  - add / replace keep the stored proxy unless one is passed;
 *  - proxy changes are visible to the per-connection look-up at once (caches invalidated);
 *  - deleteAllAppsRules removes only app rules, from the db and the tree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [ShadowBackend::class])
class IpRulesProxyPreservationTest {

    private val uid = 10042
    private val none = Pair("", "")
    private lateinit var store: FakeIpStore
    private lateinit var tree: FakeIpTree

    @Before
    fun setUp() {
        store = FakeIpStore()
        tree = FakeIpTree()
        IpRulesManager.overrideForTest(store.repo, tree)
    }

    @After
    fun tearDown() {
        IpRulesManager.overrideForTest(null, null)
    }

    private fun ip(s: String) = IPAddressString(s).address!!

    private fun add(u: Int, s: String, port: Int, status: IpRuleStatus, proxyId: String? = null): CustomIp =
        runBlocking { IpRulesManager.addIpRule(u, ip(s), port, status, proxyId) }

    private fun stored(u: Int, s: String, port: Int = 0) = store.get(u, s, port)

    @Test
    fun `status change keeps the proxy`() = runBlocking {
        val c = add(uid, "1.2.3.4", 0, IpRuleStatus.TRUST, "S5")
        IpRulesManager.updateBlock(c)
        assertEquals("S5", stored(uid, "1.2.3.4")!!.proxyId)
        assertEquals(IpRuleStatus.BLOCK.id, stored(uid, "1.2.3.4")!!.status)
        assertEquals(Pair("S5", ""), IpRulesManager.getProxyForIpPort(uid, "1.2.3.4", 0))
        assertEquals(IpRuleStatus.BLOCK, IpRulesManager.getMostSpecificRuleMatch(uid, "1.2.3.4"))
    }

    @Test
    fun `status change from a stale copy keeps the stored proxy`() = runBlocking {
        val stale = add(uid, "1.2.3.4", 443, IpRuleStatus.BLOCK).deepCopy() // no proxy yet
        IpRulesManager.setProxy(stored(uid, "1.2.3.4", 443)!!, "wg2")
        IpRulesManager.updateTrust(stale)
        IpRulesManager.updateNoRule(stale)
        IpRulesManager.updateBypass(stale)
        val s = stored(uid, "1.2.3.4", 443)!!
        assertEquals("wg2", s.proxyId)
        assertEquals(IpRuleStatus.BYPASS_UNIVERSAL.id, s.status)
        assertEquals(Pair("wg2", ""), IpRulesManager.getProxyForIpPort(uid, "1.2.3.4", 443))
    }

    @Test
    fun `setProxy keeps the status and clearProxy removes the proxy`() = runBlocking {
        val c = add(uid, "10.0.0.0/8", 0, IpRuleStatus.BLOCK)
        val staleStatus = c.deepCopy().apply { status = IpRuleStatus.NONE.id }
        IpRulesManager.setProxy(staleStatus, "S5")
        assertEquals(IpRuleStatus.BLOCK.id, stored(uid, c.ipAddress)!!.status)
        assertEquals("S5", stored(uid, c.ipAddress)!!.proxyId)
        assertEquals(Pair("S5", ""), IpRulesManager.getProxyForIpPort(uid, "10.1.2.3", 0))

        IpRulesManager.clearProxy(c)
        assertEquals("", stored(uid, c.ipAddress)!!.proxyId)
        assertEquals(IpRuleStatus.BLOCK.id, stored(uid, c.ipAddress)!!.status)
        assertEquals(none, IpRulesManager.getProxyForIpPort(uid, "10.1.2.3", 0))
    }

    @Test
    fun `proxy changes bypass the cached look-up`() = runBlocking {
        val c = add(uid, "1.1.1.1", 0, IpRuleStatus.NONE, "wg1")
        assertEquals(Pair("wg1", ""), IpRulesManager.getProxyForIpPort(uid, "1.1.1.1", 0)) // cached
        IpRulesManager.setProxy(c, "wg2")
        assertEquals(Pair("wg2", ""), IpRulesManager.getProxyForIpPort(uid, "1.1.1.1", 0))
        IpRulesManager.clearProxy(c)
        assertEquals(none, IpRulesManager.getProxyForIpPort(uid, "1.1.1.1", 0))
    }

    @Test
    fun `first proxy rule enables the proxy look-up`() = runBlocking {
        val c = add(uid, "1.1.1.1", 0, IpRuleStatus.BLOCK)
        assertEquals(none, IpRulesManager.getProxyForIpPort(uid, "1.1.1.1", 0))
        IpRulesManager.setProxy(c, "S5")
        assertEquals(Pair("S5", ""), IpRulesManager.getProxyForIpPort(uid, "1.1.1.1", 0))
    }

    @Test
    fun `re-adding a rule keeps its proxy unless one is given`() = runBlocking {
        add(uid, "8.8.8.8", 0, IpRuleStatus.BLOCK, "wg1")
        add(uid, "8.8.8.8", 0, IpRuleStatus.TRUST) // e.g. from the connection sheet
        assertEquals("wg1", stored(uid, "8.8.8.8")!!.proxyId)
        assertEquals(IpRuleStatus.TRUST.id, stored(uid, "8.8.8.8")!!.status)
        add(uid, "8.8.8.8", 0, IpRuleStatus.TRUST, "")
        assertEquals("", stored(uid, "8.8.8.8")!!.proxyId)
        assertEquals(none, IpRulesManager.getProxyForIpPort(uid, "8.8.8.8", 0))
    }

    @Test
    fun `replace keeps the proxy by default and can set or clear it`() = runBlocking {
        val c = add(uid, "8.8.8.8", 0, IpRuleStatus.BLOCK, "wg1")
        IpRulesManager.replaceIpRule(c.deepCopy().apply { proxyId = "" }, ip("8.8.4.4"), 53, IpRuleStatus.TRUST)
        assertNull(stored(uid, "8.8.8.8"))
        assertEquals("wg1", stored(uid, "8.8.4.4", 53)!!.proxyId)
        assertEquals(none, IpRulesManager.getProxyForIpPort(uid, "8.8.8.8", 0))
        assertEquals(Pair("wg1", ""), IpRulesManager.getProxyForIpPort(uid, "8.8.4.4", 53))

        IpRulesManager.replaceIpRule(stored(uid, "8.8.4.4", 53)!!, ip("8.8.4.4"), 53, IpRuleStatus.TRUST, "S5")
        assertEquals("S5", stored(uid, "8.8.4.4", 53)!!.proxyId)
        IpRulesManager.replaceIpRule(stored(uid, "8.8.4.4", 53)!!, ip("8.8.4.4"), 53, IpRuleStatus.BLOCK, "")
        assertEquals("", stored(uid, "8.8.4.4", 53)!!.proxyId)
        assertEquals(none, IpRulesManager.getProxyForIpPort(uid, "8.8.4.4", 53))
    }

    @Test
    fun `updateProxyCC keeps the proxyId and status`() = runBlocking {
        val c = add(uid, "8.8.8.8", 0, IpRuleStatus.BLOCK, "wg1")
        IpRulesManager.updateProxyCC(c.deepCopy().apply { proxyId = ""; status = 0 }, "US")
        val s = stored(uid, "8.8.8.8")!!
        assertEquals("wg1", s.proxyId)
        assertEquals("US", s.proxyCC)
        assertEquals(IpRuleStatus.BLOCK.id, s.status)
    }

    @Test
    fun `deleteAllAppsRules keeps universal rules in the db and the tree`() = runBlocking {
        add(UID_EVERYBODY, "1.1.1.1", 0, IpRuleStatus.BLOCK, "S5")
        add(UID_EVERYBODY, "9.9.9.9", 853, IpRuleStatus.BYPASS_UNIVERSAL)
        add(uid, "1.1.1.1", 0, IpRuleStatus.TRUST, "wg1")
        add(10043, "2.2.2.2", 0, IpRuleStatus.BLOCK)
        // warm the caches with the app rules
        assertEquals(IpRuleStatus.TRUST, IpRulesManager.hasRule(uid, "1.1.1.1", 0))
        assertEquals(Pair("wg1", ""), IpRulesManager.getProxyForIpPort(uid, "1.1.1.1", 0))

        IpRulesManager.deleteAllAppsRules()

        assertEquals(setOf(UID_EVERYBODY), store.rows.values.map { it.uid }.toSet())
        assertEquals(2, store.rows.size)
        assertTrue(tree.allValues().all { it.startsWith("$UID_EVERYBODY:") })
        assertEquals(2, tree.allValues().size)
        // app rules are gone (caches invalidated), universal ones still enforced
        assertEquals(IpRuleStatus.NONE, IpRulesManager.hasRule(uid, "1.1.1.1", 0))
        assertEquals(IpRuleStatus.NONE, IpRulesManager.hasRule(10043, "2.2.2.2", 0))
        assertEquals(none, IpRulesManager.getProxyForIpPort(uid, "1.1.1.1", 0))
        assertEquals(IpRuleStatus.BLOCK, IpRulesManager.hasRule(UID_EVERYBODY, "1.1.1.1", 0))
        assertEquals(IpRuleStatus.BYPASS_UNIVERSAL, IpRulesManager.hasRule(UID_EVERYBODY, "9.9.9.9", 853))
        assertEquals(Pair("S5", ""), IpRulesManager.getProxyForIpPort(UID_EVERYBODY, "1.1.1.1", 0))
    }

    @Test
    fun `removing a port rule does not drop a longer port of the same ip`() = runBlocking {
        add(uid, "1.2.3.4", 443, IpRuleStatus.BLOCK)
        add(uid, "1.2.3.4", 44, IpRuleStatus.TRUST)
        IpRulesManager.removeIpRule(uid, "1.2.3.4", 44)
        assertEquals(IpRuleStatus.BLOCK, IpRulesManager.getMostSpecificRuleMatch(uid, "1.2.3.4", 443))
        assertEquals(IpRuleStatus.NONE, IpRulesManager.getMostSpecificRuleMatch(uid, "1.2.3.4", 44))
    }

    @Test
    fun `deleteRules removes rules even from a stale copy`() = runBlocking {
        val stale = add(uid, "1.2.3.4", 0, IpRuleStatus.BLOCK).deepCopy()
        IpRulesManager.setProxy(stored(uid, "1.2.3.4")!!, "wg1")
        IpRulesManager.deleteRules(listOf(stale))
        assertNull(stored(uid, "1.2.3.4"))
        assertTrue(tree.allValues().isEmpty())
        assertNotNull(store.repo)
    }

    @Test
    fun `setProxy on an ip without a rule creates a route-only rule`() = runBlocking {
        IpRulesManager.setProxy(uid, ip("1.2.3.4"), 0, "S5")
        val s = stored(uid, "1.2.3.4")!!
        assertEquals("S5", s.proxyId)
        assertEquals(IpRuleStatus.NONE.id, s.status)
        assertEquals(Pair("S5", ""), IpRulesManager.getProxyForIpPort(uid, "1.2.3.4", 0))
        assertEquals(IpRuleStatus.NONE, IpRulesManager.getMostSpecificRuleMatch(uid, "1.2.3.4"))

        // clearing keeps the (status-less) rule row; the proxy is gone
        IpRulesManager.setProxy(uid, ip("1.2.3.4"), 0, "")
        assertEquals("", stored(uid, "1.2.3.4")!!.proxyId)
        assertEquals(none, IpRulesManager.getProxyForIpPort(uid, "1.2.3.4", 0))
    }

    @Test
    fun `setProxy with a CustomIp that is not stored inserts it`() = runBlocking {
        val c = IpRulesManager.mkCustomIp(uid, "5.6.7.8", 0)
        IpRulesManager.setProxy(c, "wg1")
        assertEquals("wg1", stored(uid, "5.6.7.8")!!.proxyId)
        assertEquals(Pair("wg1", ""), IpRulesManager.getProxyForIpPort(uid, "5.6.7.8", 0))
    }

    @Test
    fun `setProxy by ip keeps the stored status, and a later status change keeps the proxy`() = runBlocking {
        add(uid, "1.2.3.4", 0, IpRuleStatus.BLOCK)
        IpRulesManager.setProxy(uid, ip("1.2.3.4"), 0, "wg2")
        assertEquals(IpRuleStatus.BLOCK.id, stored(uid, "1.2.3.4")!!.status)
        add(uid, "1.2.3.4", 0, IpRuleStatus.TRUST)
        assertEquals("wg2", stored(uid, "1.2.3.4")!!.proxyId)
        assertEquals(IpRuleStatus.TRUST.id, stored(uid, "1.2.3.4")!!.status)
    }

    @Test
    fun `getRule finds a rule by the normalized form of the ip`() = runBlocking {
        val c = add(uid, "2001:db8::1", 0, IpRuleStatus.BLOCK, "S5")
        val found = IpRulesManager.getRule(uid, ip("2001:db8::1"))
        assertNotNull(found)
        assertEquals(c.ipAddress, found!!.ipAddress)
        assertEquals("S5", found.proxyId)
        assertNull(IpRulesManager.getRule(10043, ip("2001:db8::1")))
    }

    @Test
    fun `route-only rules are per uid`() = runBlocking {
        IpRulesManager.setProxy(UID_EVERYBODY, ip("9.9.9.9"), 0, "S5")
        IpRulesManager.setProxy(uid, ip("9.9.9.9"), 0, "wg1")
        assertEquals("S5", stored(UID_EVERYBODY, "9.9.9.9")!!.proxyId)
        assertEquals("wg1", stored(uid, "9.9.9.9")!!.proxyId)
        IpRulesManager.setProxy(uid, ip("9.9.9.9"), 0, "")
        assertEquals("S5", stored(UID_EVERYBODY, "9.9.9.9")!!.proxyId)
    }
}
