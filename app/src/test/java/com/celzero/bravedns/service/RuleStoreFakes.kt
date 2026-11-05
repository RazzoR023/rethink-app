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

import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.database.CustomDomainRepository
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.database.CustomIpRepository
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.firestack.backend.Backend
import com.celzero.firestack.backend.IpTree
import com.celzero.firestack.backend.RadixTree
import inet.ipaddr.IPAddressString
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk

/*
 * In-memory stand-ins for the rule stores, used with IpRulesManager.overrideForTest /
 * DomainRulesManager.overrideForTest: a Room-like repository (rows are copied in and out, so
 * callers' objects never alias stored rows) and the firestack ip tree / radix trie.
 */

/** CustomIp rows keyed by (uid, ip, port), backing a mocked [CustomIpRepository]. */
class FakeIpStore {
    val rows = linkedMapOf<Triple<Int, String, Int>, CustomIp>()

    private fun key(c: CustomIp) = Triple(c.uid, c.ipAddress, c.port)

    fun get(uid: Int, ip: String, port: Int): CustomIp? = rows[Triple(uid, ip, port)]?.deepCopy()

    val repo: CustomIpRepository = mockk(relaxed = true) {
        coEvery { insert(any()) } answers { val c = firstArg<CustomIp>(); rows[key(c)] = c.deepCopy() }
        coEvery { update(any()) } answers { val c = firstArg<CustomIp>(); if (rows.containsKey(key(c))) rows[key(c)] = c.deepCopy() }
        coEvery { getCustomIpDetail(any(), any(), any()) } answers { get(firstArg(), secondArg(), thirdArg()) }
        coEvery { deleteRule(any(), any(), any()) } answers {
            if (rows.remove(Triple(firstArg<Int>(), secondArg<String>(), thirdArg<Int>())) != null) 1 else 0
        }
        coEvery { getIpRules() } answers { rows.values.map { it.deepCopy() } }
        coEvery { getRulesByUid(any()) } answers { rows.values.filter { it.uid == firstArg<Int>() }.map { it.deepCopy() } }
        coEvery { deleteRulesByUid(any()) } answers { rows.values.removeAll { it.uid == firstArg<Int>() } }
        coEvery { deleteAllAppsRules() } answers { rows.values.removeAll { it.uid != UID_EVERYBODY } }
        coEvery { deleteRules(any()) } answers { firstArg<List<CustomIp>>().forEach { rows.remove(key(it)) } }
    }
}

/** CustomDomain rows keyed by (uid, domain), backing a mocked [CustomDomainRepository]. */
class FakeDomainStore {
    val rows = linkedMapOf<Pair<Int, String>, CustomDomain>()

    private fun key(c: CustomDomain) = Pair(c.uid, c.domain)

    fun get(uid: Int, domain: String): CustomDomain? = rows[Pair(uid, domain)]?.let(::copy)

    val repo: CustomDomainRepository = mockk(relaxed = true) {
        coEvery { insert(any()) } answers { val c = firstArg<CustomDomain>(); rows[key(c)] = copy(c) }
        coEvery { update(any<CustomDomain>()) } answers { val c = firstArg<CustomDomain>(); rows[key(c)] = copy(c) }
        coEvery { update(any(), any()) } answers {
            rows.remove(key(firstArg())); val c = secondArg<CustomDomain>(); rows[key(c)] = copy(c)
        }
        coEvery { delete(any()) } answers { rows.remove(key(firstArg())) }
        every { getCustomDomain(any(), any()) } answers { get(firstArg(), secondArg()) }
        every { getAllCustomDomains() } answers { rows.values.map(::copy) }
        every { getDomainsByUID(any()) } answers { rows.values.filter { it.uid == firstArg<Int>() }.map(::copy) }
        coEvery { deleteRules(any()) } answers { firstArg<List<CustomDomain>>().forEach { rows.remove(key(it)) } }
        coEvery { deleteRulesByUid(any()) } answers { rows.values.removeAll { it.uid == firstArg<Int>() } }
        every { deleteAllRules() } answers { rows.clear() }
    }

    companion object {
        fun copy(c: CustomDomain) = CustomDomain(
            c.domain, c.uid, c.ips, c.type, c.status, c.proxyId, c.proxyCC, c.modifiedTs, c.deletedTs, c.version
        )
    }
}

/**
 * Behaves like firestack's ip tree for the calls IpRulesManager makes: several values per
 * cidr key; *Like calls match values by prefix; valuesLike returns the values of every key
 * covering the ip, least to most specific.
 */
class FakeIpTree : IpTree {
    val map = linkedMapOf<String, MutableList<String>>()

    private fun addr(k: String) = IPAddressString(k).address

    private fun join(vs: List<String>) = if (vs.isEmpty()) null else vs.joinToString(Backend.Vsep)

    /** every value in the tree, for assertions */
    fun allValues(): List<String> = map.values.flatten()

    override fun add(key: String, value: String) {
        val l = map.getOrPut(key) { mutableListOf() }
        if (value !in l) l.add(value)
    }
    override fun clear() = map.clear()
    override fun del(key: String): Boolean = map.remove(key) != null
    override fun delAll(prefix: String): Int {
        val ks = map.keys.filter { it.startsWith(prefix) }
        ks.forEach { map.remove(it) }
        return ks.size
    }
    override fun esc(key: String, value: String): Boolean {
        val l = map[key] ?: return false
        val r = l.remove(value)
        if (l.isEmpty()) map.remove(key)
        return r
    }
    override fun escLike(key: String, value: String): Int {
        val l = map[key] ?: return 0
        val n = l.count { it.startsWith(value) }
        l.removeAll { it.startsWith(value) }
        if (l.isEmpty()) map.remove(key)
        return n
    }
    override fun get(key: String): String? = map[key]?.let(::join)
    override fun getAll(key: String): String? = get(key)
    override fun getAny(key: String): String? = get(key)
    override fun getLike(key: String, value: String): String? = map[key]?.filter { it.startsWith(value) }?.let(::join)
    override fun has(key: String): Boolean = map.containsKey(key)
    override fun hasAny(key: String): Boolean = map.containsKey(key)
    override fun len(): Long = map.size.toLong()
    override fun routes(key: String): String? = null
    override fun routesLike(key: String, value: String): String? = null
    override fun set(key: String, value: String) { map[key] = mutableListOf(value) }
    override fun values(key: String): String? = valuesLike(key, "")
    override fun valuesLike(key: String, value: String): String? {
        val ip = addr(key) ?: return null
        val covering = map.keys.mapNotNull { k ->
            val a = addr(k) ?: return@mapNotNull null
            if (a.toPrefixBlock().contains(ip)) a to k else null
        }.sortedBy { (a, _) -> a.networkPrefixLength ?: a.bitCount }
        return join(covering.flatMap { (_, k) -> map[k].orEmpty().filter { it.startsWith(value) } })
    }
}

/** A map standing in for firestack's radix trie (exact keys; hasAny: key or a suffix of it). */
class FakeRadixTree : RadixTree {
    val map = linkedMapOf<String, String>()

    override fun add(key: String): Boolean = map.put(key, "") == null
    override fun clear() = map.clear()
    override fun del(key: String): Boolean = map.remove(key) != null
    override fun delAll(prefix: String): Int {
        val ks = map.keys.filter { it.startsWith(prefix) }
        ks.forEach { map.remove(it) }
        return ks.size
    }
    override fun get(key: String): String? = map[key]
    override fun getAny(key: String): String? = map[key]
    override fun has(key: String): Boolean = map.containsKey(key)
    override fun hasAny(key: String): Boolean = map.keys.any { key == it || key.endsWith(it) }
    override fun len(): Long = map.size.toLong()
    override fun set(key: String, value: String) { map[key] = value }
}
