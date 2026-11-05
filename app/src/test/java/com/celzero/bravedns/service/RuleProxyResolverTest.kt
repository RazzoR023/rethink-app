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

import com.celzero.bravedns.service.ProxyManager.ID_HTTP_BASE
import com.celzero.bravedns.service.ProxyManager.ID_NONE
import com.celzero.bravedns.service.ProxyManager.ID_ORBOT_BASE
import com.celzero.bravedns.service.ProxyManager.ID_S5_BASE
import com.celzero.bravedns.service.ProxyManager.ID_WG_BASE
import com.celzero.bravedns.service.RuleProxyResolver.Decision
import com.celzero.bravedns.service.RuleProxyResolver.Endpoint
import com.celzero.bravedns.service.RuleProxyResolver.Level
import com.celzero.bravedns.service.RuleProxyResolver.RuleProxy
import com.celzero.bravedns.shadows.ShadowBackend
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.firestack.backend.Backend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [RuleProxyResolver]: the precedence of per-rule proxies
 * (domain -> ip:port -> ip, per-app before universal at each level) and the decision whether
 * the matched rule's proxy is used, falls through to app routing, or blocks.
 *
 * The rule tries are native (firestack), so look-ups go through a map-backed [FakeLookup];
 * the manager-side matching is covered by [IpRulesProxyMatchTest] / [DomainRulesProxyMatchTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [ShadowBackend::class])
class RuleProxyResolverTest {

    private val app = 10042
    private val ip = "1.2.3.4"
    private val port = 443
    private val domain = "www.example.com"
    private val wg1 = ID_WG_BASE + "1"
    private val wg2 = ID_WG_BASE + "2"
    private val wg3 = ID_WG_BASE + "3"
    private val wg4 = ID_WG_BASE + "4"
    private val wg5 = ID_WG_BASE + "5"
    private val wg6 = ID_WG_BASE + "6"

    /** (uid, domain) and (uid, ip, port) keyed rules; port 0 = any-port rules */
    private class FakeLookup : RuleProxyResolver.Lookup {
        val domains = mutableMapOf<Pair<Int, String>, Pair<String, String>>()
        val ips = mutableMapOf<Triple<Int, String, Int>, Pair<String, String>>()
        val calls = mutableListOf<String>()

        override fun domainProxy(uid: Int, domain: String): Pair<String, String> {
            calls.add("d:$uid:$domain")
            return domains[Pair(uid, domain)] ?: Pair("", "")
        }

        override fun ipProxy(uid: Int, ip: String, port: Int): Pair<String, String> {
            calls.add("i:$uid:$ip:$port")
            return ips[Triple(uid, ip, port)] ?: Pair("", "")
        }
    }

    private class FakeEnv(
        override val rpnIds: List<String> = emptyList(),
        var excluded: Boolean = false,
        var wgLockdown: Boolean = false,
        var wg: (String) -> List<String> = { listOf(it, Backend.Exit) },
        var endpoints: Map<String, Endpoint> = emptyMap(),
        var pkg: String? = "com.example.app"
    ) : RuleProxyResolver.Env {
        override suspend fun isAppExcludedFromProxy() = excluded
        override fun isAppInWgLockdown() = wgLockdown
        override fun wgIds(pid: String) = wg(pid)
        override suspend fun endpoint(pid: String) = endpoints[pid]
        override suspend fun packageName() = pkg
    }

    /** fails the test if the decision consults the environment */
    private class ThrowingEnv : RuleProxyResolver.Env {
        override val rpnIds: List<String> get() = throw AssertionError("env consulted")
        override suspend fun isAppExcludedFromProxy(): Boolean = throw AssertionError("env consulted")
        override fun isAppInWgLockdown(): Boolean = throw AssertionError("env consulted")
        override fun wgIds(pid: String): List<String> = throw AssertionError("env consulted")
        override suspend fun endpoint(pid: String): Endpoint? = throw AssertionError("env consulted")
        override suspend fun packageName(): String? = throw AssertionError("env consulted")
    }

    private fun find(l: FakeLookup, d: String? = domain, i: String? = ip, p: Int = port) =
        RuleProxyResolver.findRuleProxy(app, d, i, p, l)

    private fun all(l: FakeLookup) {
        l.domains[Pair(app, domain)] = Pair(wg1, "")
        l.domains[Pair(UID_EVERYBODY, domain)] = Pair(wg2, "")
        l.ips[Triple(app, ip, port)] = Pair(wg3, "")
        l.ips[Triple(UID_EVERYBODY, ip, port)] = Pair(wg4, "")
        l.ips[Triple(app, ip, 0)] = Pair(wg5, "")
        l.ips[Triple(UID_EVERYBODY, ip, 0)] = Pair(wg6, "")
    }

    // ---- precedence --------------------------------------------------------

    @Test
    fun `no rule - nothing matched and env never consulted`() = runBlocking {
        val l = FakeLookup()
        assertNull(find(l))
        assertEquals(Decision.None, RuleProxyResolver.resolve(app, domain, ip, port, ThrowingEnv(), l))
    }

    @Test
    fun `precedence - app domain, universal domain, app ip-port, universal ip-port, app ip, universal ip`() {
        val l = FakeLookup()
        all(l)
        assertEquals(RuleProxy(wg1, Level.DOMAIN, app), find(l))
        l.domains.remove(Pair(app, domain))
        assertEquals(RuleProxy(wg2, Level.DOMAIN, UID_EVERYBODY), find(l))
        l.domains.remove(Pair(UID_EVERYBODY, domain))
        assertEquals(RuleProxy(wg3, Level.IP_PORT, app), find(l))
        l.ips.remove(Triple(app, ip, port))
        assertEquals(RuleProxy(wg4, Level.IP_PORT, UID_EVERYBODY), find(l))
        l.ips.remove(Triple(UID_EVERYBODY, ip, port))
        assertEquals(RuleProxy(wg5, Level.IP, app), find(l))
        l.ips.remove(Triple(app, ip, 0))
        assertEquals(RuleProxy(wg6, Level.IP, UID_EVERYBODY), find(l))
        l.ips.remove(Triple(UID_EVERYBODY, ip, 0))
        assertNull(find(l))
    }

    @Test
    fun `precedence - domain rule beats ip rules`() {
        val l = FakeLookup()
        l.ips[Triple(app, ip, port)] = Pair(wg3, "")
        l.domains[Pair(app, domain)] = Pair(wg1, "")
        assertEquals(RuleProxy(wg1, Level.DOMAIN, app), find(l))
    }

    @Test
    fun `precedence - universal domain rule beats per-app ip rule (level first)`() {
        val l = FakeLookup()
        l.ips[Triple(app, ip, port)] = Pair(wg3, "")
        l.domains[Pair(UID_EVERYBODY, domain)] = Pair(wg2, "")
        assertEquals(RuleProxy(wg2, Level.DOMAIN, UID_EVERYBODY), find(l))
    }

    @Test
    fun `precedence - ip-port rule beats any-port ip rule`() {
        val l = FakeLookup()
        l.ips[Triple(app, ip, 0)] = Pair(wg5, "")
        l.ips[Triple(UID_EVERYBODY, ip, port)] = Pair(wg4, "")
        assertEquals(RuleProxy(wg4, Level.IP_PORT, UID_EVERYBODY), find(l))
    }

    @Test
    fun `precedence - per-app rule beats universal at the same level`() {
        val l = FakeLookup()
        l.ips[Triple(UID_EVERYBODY, ip, 0)] = Pair(wg6, "")
        l.ips[Triple(app, ip, 0)] = Pair(wg5, "")
        assertEquals(RuleProxy(wg5, Level.IP, app), find(l, d = null))
    }

    @Test
    fun `precedence - universal rule applies when the app has none`() {
        val l = FakeLookup()
        l.domains[Pair(UID_EVERYBODY, domain)] = Pair(ID_S5_BASE, "")
        assertEquals(RuleProxy(ID_S5_BASE, Level.DOMAIN, UID_EVERYBODY), find(l))
    }

    @Test
    fun `cc-only rule is skipped, a lower precedence proxyId rule applies`() {
        val l = FakeLookup()
        l.domains[Pair(app, domain)] = Pair("", "US")
        l.ips[Triple(app, ip, 0)] = Pair(wg5, "")
        assertEquals(RuleProxy(wg5, Level.IP, app), find(l))
    }

    @Test
    fun `rule with proxyId and proxyCC matches on its proxyId`() {
        val l = FakeLookup()
        l.domains[Pair(app, domain)] = Pair(wg1, "US")
        assertEquals(RuleProxy(wg1, Level.DOMAIN, app), find(l))
    }

    @Test
    fun `port 0 skips the ip-port level`() {
        val l = FakeLookup()
        l.ips[Triple(app, ip, 0)] = Pair(wg5, "")
        assertEquals(RuleProxy(wg5, Level.IP, app), find(l, p = 0))
        assertEquals(listOf("d:$app:$domain", "d:$UID_EVERYBODY:$domain", "i:$app:$ip:0"), l.calls)
    }

    @Test
    fun `empty or unspecified ip skips ip rules`() {
        val l = FakeLookup()
        l.ips[Triple(app, "0.0.0.0", 0)] = Pair(wg5, "")
        assertNull(find(l, d = null, i = "0.0.0.0"))
        assertNull(find(l, d = null, i = "::"))
        assertNull(find(l, d = null, i = ""))
        assertNull(find(l, d = null, i = null))
        assertEquals(emptyList<String>(), l.calls)
    }

    @Test
    fun `csv domain and ip - only the first entry is looked up`() {
        val l = FakeLookup()
        l.domains[Pair(app, "b.example.com")] = Pair(wg1, "")
        l.ips[Triple(app, "5.6.7.8", 0)] = Pair(wg5, "")
        assertNull(find(l, d = "a.example.com,b.example.com", i = "1.2.3.4,5.6.7.8"))
        l.ips[Triple(app, ip, 0)] = Pair(wg6, "")
        assertEquals(RuleProxy(wg6, Level.IP, app), find(l, d = "a.example.com,b.example.com", i = "1.2.3.4,5.6.7.8"))
    }

    @Test
    fun `universal uid is looked up once`() {
        val l = FakeLookup()
        RuleProxyResolver.findRuleProxy(UID_EVERYBODY, domain, ip, port, l)
        assertEquals(
            listOf(
                "d:$UID_EVERYBODY:$domain",
                "i:$UID_EVERYBODY:$ip:$port",
                "i:$UID_EVERYBODY:$ip:0"
            ),
            l.calls
        )
    }

    // ---- decision ----------------------------------------------------------

    private fun rule(pid: String) = RuleProxy(pid, Level.DOMAIN, app)

    @Test
    fun `wireguard rule usable - routes via its ids with rpn ids prepended`() = runBlocking {
        val env = FakeEnv(rpnIds = listOf("rpn1"), wg = { listOf(it, "wg9", Backend.Exit) })
        assertEquals(Decision.Route(listOf("rpn1", wg1, "wg9", Backend.Exit)), RuleProxyResolver.decide(rule(wg1), env))
    }

    @Test
    fun `wireguard rule unusable - falls through to app routing`() = runBlocking {
        val env = FakeEnv(wg = { emptyList() })
        assertEquals(Decision.None, RuleProxyResolver.decide(rule(wg1), env))
    }

    @Test
    fun `wireguard lockdown rule not eligible - blocks`() = runBlocking {
        val env = FakeEnv(rpnIds = listOf("rpn1"), wg = { listOf(Backend.Block) })
        assertEquals(Decision.Block, RuleProxyResolver.decide(rule(wg1), env))
    }

    @Test
    fun `socks5, orbot and http rules route when enabled`() = runBlocking {
        val env = FakeEnv(
            rpnIds = listOf("rpn1"),
            endpoints = mapOf(
                ID_S5_BASE to Endpoint(true, "com.socks.app"),
                ID_ORBOT_BASE to Endpoint(true, "org.torproject.android"),
                ID_HTTP_BASE to Endpoint(true, null)
            )
        )
        assertEquals(Decision.Route(listOf("rpn1", ID_S5_BASE)), RuleProxyResolver.decide(rule(ID_S5_BASE), env))
        assertEquals(Decision.Route(listOf("rpn1", ID_ORBOT_BASE)), RuleProxyResolver.decide(rule(ID_ORBOT_BASE), env))
        assertEquals(Decision.Route(listOf("rpn1", ID_HTTP_BASE)), RuleProxyResolver.decide(rule(ID_HTTP_BASE), env))
    }

    @Test
    fun `socks5, orbot and http rules fall through when disabled`() = runBlocking {
        val env = FakeEnv(
            endpoints = mapOf(
                ID_S5_BASE to Endpoint(false, null),
                ID_ORBOT_BASE to Endpoint(false, null),
                ID_HTTP_BASE to Endpoint(false, null)
            )
        )
        assertEquals(Decision.None, RuleProxyResolver.decide(rule(ID_S5_BASE), env))
        assertEquals(Decision.None, RuleProxyResolver.decide(rule(ID_ORBOT_BASE), env))
        assertEquals(Decision.None, RuleProxyResolver.decide(rule(ID_HTTP_BASE), env))
    }

    @Test
    fun `forwarder app is never routed into its own proxy`() = runBlocking {
        val fwd = "com.forwarder.app"
        val env = FakeEnv(
            pkg = fwd,
            endpoints = mapOf(
                ID_S5_BASE to Endpoint(true, fwd),
                ID_ORBOT_BASE to Endpoint(true, fwd),
                ID_HTTP_BASE to Endpoint(true, fwd)
            )
        )
        assertEquals(Decision.None, RuleProxyResolver.decide(rule(ID_S5_BASE), env))
        assertEquals(Decision.None, RuleProxyResolver.decide(rule(ID_ORBOT_BASE), env))
        assertEquals(Decision.None, RuleProxyResolver.decide(rule(ID_HTTP_BASE), env))
    }

    @Test
    fun `forwarder app may be routed via a wireguard rule`() = runBlocking {
        val fwd = "com.forwarder.app"
        val env = FakeEnv(pkg = fwd, endpoints = mapOf(ID_S5_BASE to Endpoint(true, fwd)))
        assertEquals(Decision.Route(listOf(wg1, Backend.Exit)), RuleProxyResolver.decide(rule(wg1), env))
    }

    @Test
    fun `app excluded from proxy wins over a rule proxy`() = runBlocking {
        val env = FakeEnv(excluded = true, endpoints = mapOf(ID_S5_BASE to Endpoint(true, null)))
        assertEquals(Decision.None, RuleProxyResolver.decide(rule(wg1), env))
        assertEquals(Decision.None, RuleProxyResolver.decide(rule(ID_S5_BASE), env))
    }

    @Test
    fun `app-level wireguard lockdown wins over a rule proxy`() = runBlocking {
        val env = FakeEnv(wgLockdown = true, endpoints = mapOf(ID_S5_BASE to Endpoint(true, null)))
        assertEquals(Decision.None, RuleProxyResolver.decide(rule(wg1), env))
        assertEquals(Decision.None, RuleProxyResolver.decide(rule(ID_S5_BASE), env))
    }

    @Test
    fun `none, rpn and unknown proxy ids fall through`() = runBlocking {
        val env = FakeEnv()
        assertEquals(Decision.None, RuleProxyResolver.decide(rule(ID_NONE), env))
        assertEquals(Decision.None, RuleProxyResolver.decide(rule(Backend.RpnWin + "srv-us"), env))
        assertEquals(Decision.None, RuleProxyResolver.decide(rule("bogus"), env))
        assertEquals(Decision.None, RuleProxyResolver.decide(rule(ID_WG_BASE), env))
    }

    @Test
    fun `resolve - matched rule is decided`() = runBlocking {
        val l = FakeLookup()
        l.ips[Triple(UID_EVERYBODY, ip, 0)] = Pair(wg6, "")
        val env = FakeEnv(wg = { listOf(it) })
        assertEquals(Decision.Route(listOf(wg6)), RuleProxyResolver.resolve(app, null, ip, port, env, l))
    }
}
