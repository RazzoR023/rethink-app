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
 * Per-rule proxy matching in [IpRulesManager.pickProxy], which selects the proxy from the
 * values the native ip tree returns for an ip (valuesLike: every rule on the exact ip or a cidr
 * containing it, least to most specific, filtered by a "uid:port" prefix).
 *
 * Regression: the old hasProxy / getMostSpecific*Proxies only matched when BOTH proxyId and
 * proxyCC were set, so a plain "route via wg2" rule never matched; and a status-only rule (or a
 * malformed value) ended the search instead of being skipped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [ShadowBackend::class])
class IpRulesProxyMatchTest {

    private val uid = 10042
    private val none = Pair("", "")

    // uid:port:status:proxyId:proxyCC
    private fun v(uid: Int, port: Int, status: Int, proxyId: String, proxyCC: String) =
        "$uid:$port:$status:$proxyId:$proxyCC"

    private fun values(vararg vs: String) = vs.joinToString(Backend.Vsep)

    @Test
    fun `proxyId only matches`() {
        assertEquals(Pair("wg2", ""), IpRulesManager.pickProxy(values(v(uid, 0, 0, "wg2", "")), uid, 0))
    }

    @Test
    fun `proxyCC only matches (rpn country)`() {
        assertEquals(Pair("", "US"), IpRulesManager.pickProxy(values(v(uid, 0, 0, "", "US")), uid, 0))
    }

    @Test
    fun `proxyId and proxyCC both match`() {
        assertEquals(Pair("wg2", "US"), IpRulesManager.pickProxy(values(v(uid, 0, 2, "wg2", "US")), uid, 0))
    }

    @Test
    fun `neither proxyId nor proxyCC does not match`() {
        assertEquals(none, IpRulesManager.pickProxy(values(v(uid, 0, 1, "", "")), uid, 0))
        assertEquals(none, IpRulesManager.pickProxy(null, uid, 0))
        assertEquals(none, IpRulesManager.pickProxy("", uid, 0))
    }

    @Test
    fun `status is independent of the proxy`() {
        // blocked, trusted, bypass-universal and no-status rules all route via their proxy
        for (status in 0..3) {
            assertEquals(Pair("S5", ""), IpRulesManager.pickProxy(values(v(uid, 0, status, "S5", "")), uid, 0))
        }
    }

    @Test
    fun `most specific (last) value wins`() {
        // e.g. 0.0.0.0/0 -> wg1, 1.2.3.0/24 -> wg2, 1.2.3.4 -> wg3
        val x = values(v(uid, 0, 0, "wg1", ""), v(uid, 0, 0, "wg2", ""), v(uid, 0, 0, "wg3", ""))
        assertEquals(Pair("wg3", ""), IpRulesManager.pickProxy(x, uid, 0))
    }

    @Test
    fun `status-only rule does not hide a less specific proxy rule`() {
        // 1.2.3.0/24 -> wg2; 1.2.3.4 blocked without a proxy
        val x = values(v(uid, 0, 0, "wg2", ""), v(uid, 0, 1, "", ""))
        assertEquals(Pair("wg2", ""), IpRulesManager.pickProxy(x, uid, 0))
    }

    @Test
    fun `malformed and legacy values are skipped, not fatal`() {
        val x = values(v(uid, 0, 0, "wg2", ""), "garbage", "$uid:0:1")
        assertEquals(Pair("wg2", ""), IpRulesManager.pickProxy(x, uid, 0))
    }

    @Test
    fun `port-specific and any-port rules are separate levels`() {
        val x = values(v(uid, 0, 0, "wg1", ""), v(uid, 443, 0, "wg2", ""))
        assertEquals(Pair("wg2", ""), IpRulesManager.pickProxy(x, uid, 443))
        assertEquals(Pair("wg1", ""), IpRulesManager.pickProxy(x, uid, 0))
        assertEquals(none, IpRulesManager.pickProxy(x, uid, 80))
    }

    @Test
    fun `prefix look-alikes of other ports and uids are skipped`() {
        // the tree filters by prefix: "10042:44" is like "10042:443", "1004:0" like "10042:0"
        val x = values(v(uid, 443, 0, "wg2", ""), v(10042, 0, 0, "wg3", ""))
        assertEquals(none, IpRulesManager.pickProxy(x, uid, 44))
        assertEquals(none, IpRulesManager.pickProxy(x, 1004, 0))
    }

    @Test
    fun `per-app and universal rules are matched by their own uid`() {
        val x = values(v(UID_EVERYBODY, 0, 0, "wg1", ""), v(uid, 0, 0, "wg2", ""))
        assertEquals(Pair("wg2", ""), IpRulesManager.pickProxy(x, uid, 0))
        assertEquals(Pair("wg1", ""), IpRulesManager.pickProxy(x, UID_EVERYBODY, 0))
        assertEquals(none, IpRulesManager.pickProxy(x, 10043, 0))
    }

    @Test
    fun `lookups without proxy rules return no proxy`() {
        // the no-op ip tree (ShadowBackend) holds no rules
        assertEquals(none, IpRulesManager.hasProxy(uid, "1.2.3.4", 443))
        assertEquals(none, IpRulesManager.getProxyForIpPort(uid, "1.2.3.4", 443))
        assertEquals(none, IpRulesManager.getProxyForIpPort(uid, "", 0))
    }
}
