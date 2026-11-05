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

import com.celzero.bravedns.shadows.ShadowBackend
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.firestack.backend.Backend
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Per-rule proxy matching in [DomainRulesManager.findProxyForDomain] over a map standing in for
 * the native radix trie (keys "domain,uid"; wildcard "*.example.com" keyed ".example.com,uid";
 * values "status:proxyId:proxyCC").
 *
 * Regression: the old getProxyForDomain returned no proxy when the exact domain had no rule, so
 * wildcard proxy rules only applied to domains that also had an exact rule.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [ShadowBackend::class])
class DomainRulesProxyMatchTest {

    private val uid = 10042
    private val none = Pair("", "")
    private val trie = mutableMapOf<String, String>()

    private fun rule(domain: String, u: Int, status: Int, proxyId: String, proxyCC: String) {
        trie[domain.removePrefix("*") + Backend.Ksep + u] = "$status:$proxyId:$proxyCC"
    }

    private fun find(domain: String, u: Int = uid) =
        DomainRulesManager.findProxyForDomain(u, domain) { trie[it] }

    @Test
    fun `exact rule with proxyId only matches`() {
        rule("www.example.com", uid, 0, "wg2", "")
        assertEquals(Pair("wg2", ""), find("www.example.com"))
    }

    @Test
    fun `exact rule with proxyCC only matches (rpn country)`() {
        rule("www.example.com", uid, 0, "", "US")
        assertEquals(Pair("", "US"), find("www.example.com"))
    }

    @Test
    fun `exact rule with both matches`() {
        rule("www.example.com", uid, 1, "wg2", "US")
        assertEquals(Pair("wg2", "US"), find("www.example.com"))
    }

    @Test
    fun `rule with neither does not match`() {
        rule("www.example.com", uid, 1, "", "")
        assertEquals(none, find("www.example.com"))
        assertEquals(none, find("other.com"))
        assertEquals(none, find(""))
    }

    @Test
    fun `wildcard rule matches sub-domains without an exact rule`() {
        rule("*.example.com", uid, 0, "wg2", "")
        assertEquals(Pair("wg2", ""), find("www.example.com"))
        assertEquals(Pair("wg2", ""), find("a.b.example.com"))
    }

    @Test
    fun `wildcard rule does not match the apex domain`() {
        rule("*.example.com", uid, 0, "wg2", "")
        assertEquals(none, find("example.com"))
        assertEquals(none, find("notexample.com"))
    }

    @Test
    fun `exact rule beats wildcard`() {
        rule("*.example.com", uid, 0, "wg1", "")
        rule("www.example.com", uid, 0, "wg2", "")
        assertEquals(Pair("wg2", ""), find("www.example.com"))
        assertEquals(Pair("wg1", ""), find("api.example.com"))
    }

    @Test
    fun `most specific wildcard wins`() {
        rule("*.com", uid, 0, "wg1", "")
        rule("*.example.com", uid, 0, "wg2", "")
        rule("*.b.example.com", uid, 0, "wg3", "")
        assertEquals(Pair("wg3", ""), find("a.b.example.com"))
        assertEquals(Pair("wg2", ""), find("c.example.com"))
        assertEquals(Pair("wg1", ""), find("other.com"))
    }

    @Test
    fun `status-only rules do not hide a less specific proxy rule`() {
        rule("*.example.com", uid, 0, "wg2", "")
        rule("www.example.com", uid, 1, "", "")
        rule("*.b.example.com", uid, 2, "", "")
        assertEquals(Pair("wg2", ""), find("www.example.com"))
        assertEquals(Pair("wg2", ""), find("a.b.example.com"))
    }

    @Test
    fun `rules are per uid`() {
        rule("www.example.com", UID_EVERYBODY, 0, "wg1", "")
        rule("www.example.com", uid, 0, "wg2", "")
        assertEquals(Pair("wg2", ""), find("www.example.com"))
        assertEquals(Pair("wg1", ""), find("www.example.com", UID_EVERYBODY))
        assertEquals(none, find("www.example.com", 10043))
    }

    @Test
    fun `domain is matched case-insensitively and without trailing dot`() {
        rule("*.example.com", uid, 0, "wg2", "")
        assertEquals(Pair("wg2", ""), find("WWW.Example.COM."))
    }

    @Test
    fun `malformed values are skipped`() {
        trie["www.example.com" + Backend.Ksep + uid] = "1"
        rule("*.example.com", uid, 0, "wg2", "")
        assertEquals(Pair("wg2", ""), find("www.example.com"))
    }

    @Test
    fun `lookup without proxy rules returns no proxy`() {
        // no rule with a proxy was ever loaded into the (no-op) trie
        assertEquals(none, DomainRulesManager.getProxyForDomain(uid, "www.example.com"))
    }
}
