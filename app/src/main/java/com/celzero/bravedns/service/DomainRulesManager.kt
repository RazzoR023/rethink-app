/*
 * Copyright 2021 RethinkDNS and its authors
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

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_DNS
import com.celzero.bravedns.util.Logger.LOG_TAG_FIREWALL
import android.content.Context
import android.os.SystemClock.elapsedRealtime
import android.util.Patterns
import androidx.lifecycle.LiveData
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.database.CustomDomainRepository
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Utilities.isAtleastR
import com.celzero.firestack.backend.Backend
import com.celzero.firestack.backend.RadixTree
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.net.MalformedURLException
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

object DomainRulesManager : KoinComponent {

    private val injectedDb by inject<CustomDomainRepository>()
    private val nativeTrie: RadixTree by lazy { Backend.newRadixTree() }
    private val nativeTrustedTrie: RadixTree by lazy { Backend.newRadixTree() }

    // test seams: unit tests swap in a fake repository and in-memory tries (overrideForTest)
    @Volatile private var dbOverride: CustomDomainRepository? = null
    @Volatile private var trieOverride: RadixTree? = null
    @Volatile private var trustedTrieOverride: RadixTree? = null

    private val db: CustomDomainRepository get() = dbOverride ?: injectedDb

    private val trie: RadixTree get() = trieOverride ?: nativeTrie
    // fixme: find a better way to handle trusted domains without using two data structures
    // map to store the trusted domains with set of uids
    private val trustedMap = ConcurrentHashMap<String, Set<Int>>()
    // even though we have trustedMap, we need to keep the trie for wildcard matching
    private val trustedTrie: RadixTree get() = trustedTrieOverride ?: nativeTrustedTrie

    /** Unit tests only: use [repo] and the given in-memory tries; nulls restore the real ones. */
    @androidx.annotation.VisibleForTesting
    internal fun overrideForTest(repo: CustomDomainRepository?, trie: RadixTree?, trustedTrie: RadixTree?) {
        dbOverride = repo
        trieOverride = trie
        trustedTrieOverride = trustedTrie
        trustedMap.clear()
        selectedCCs.clear()
        mayHaveProxyRules = false
    }

    // regex to check if url is valid wildcard domain
    // valid wildcard domain: *.eu, *.com, *.example.com, *.example.co.in, *.do-main.com
    // RFC 1035: https://tools.ietf.org/html/rfc1035#section-2.3.4
    // Updated to support top-level domain wildcards (*.eu, *.ru, etc.)
    private val wcRegex = Pattern.compile("^(\\*\\.)?([a-zA-Z0-9-]+\\.)*[a-zA-Z0-9-]+$")

    private val selectedCCs: MutableSet<String> = mutableSetOf()

    private const val KV_SEP = ":"

    private val NO_PROXY = Pair("", "")

    // true once any rule in the trie may carry a proxyId / proxyCC; lets the per-connection
    // proxy look-up (getProxyForDomain) skip the trie when no rule routes via a proxy (the
    // common case). set on every insert of such a rule, recomputed on load(), reset when the
    // trie is cleared; never reset on single deletes (a stale true only costs a trie look-up)
    @Volatile
    private var mayHaveProxyRules = false

    enum class Status(val id: Int) {
        NONE(0),
        BLOCK(1),
        TRUST(2);

        companion object {

            fun getLabel(context: Context): Array<String> {
                return arrayOf(
                    context.getString(R.string.ci_no_rule),
                    context.getString(R.string.ci_block),
                    context.getString(R.string.ci_trust_rule)
                )
            }

            fun getStatus(statusId: Int?): Status {
                if (statusId == null) {
                    return NONE
                }

                return when (statusId) {
                    NONE.id -> NONE
                    TRUST.id -> TRUST
                    BLOCK.id -> BLOCK
                    else -> NONE
                }
            }
        }
    }

    enum class DomainType(val id: Int) {
        DOMAIN(0),
        WILDCARD(1);

        companion object {
            fun getType(id: Int): DomainType {
                return when (id) {
                    DOMAIN.id -> DOMAIN
                    WILDCARD.id -> WILDCARD
                    else -> DOMAIN
                }
            }
        }
    }

    suspend fun getObj(uid: Int, domain: String): CustomDomain? {
        return db.getCustomDomain(uid, domain)
    }

    // update the cache with the domain and its status based on the domain type
    fun updateTrie(cd: CustomDomain) {
        val key = mkTrieKey(cd.domain, cd.uid)
        val value = mkTrieValue(cd.status.toString(), cd.proxyId, cd.proxyCC)
        noteProxy(cd)
        trie.set(key, value)
    }

    private fun noteProxy(cd: CustomDomain) {
        if (cd.proxyId.isNotEmpty() || cd.proxyCC.isNotEmpty()) mayHaveProxyRules = true
    }

    private fun mkTrieKey(d: String, uid: Int): String {
        // *.google.co.uk -> .google.co.uk,<uid>
        // not supported by IpTrie: google.* -> google.,<uid>
        val domain = d.removePrefix("*")
        return (domain.lowercase(Locale.ROOT) + Backend.Ksep + uid)
    }

    private fun mkTrieKeyForTrustedMap(d: String): String? {
        // *.google.co.uk -> .google.co.uk
        val domain = d.removePrefix("*")
        return domain.lowercase(Locale.ROOT)
    }

    private fun mkTrieValue(status: String, proxyId: String, proxyCC: String): String? {
        return ("${status}$KV_SEP${proxyId}$KV_SEP${proxyCC}")
    }

    suspend fun load(): Long {
        trie.clear()
        trustedTrie.clear()
        trustedMap.clear()
        mayHaveProxyRules = false
        db.getAllCustomDomains().forEach { cd ->
            // adding as part of defensive programming, even adding these rules to cache will
            // not cause any issues, but to avoid unnecessary entries in the trie, skipping these
            // entries
            if (cd.uid < 0 && cd.uid != Constants.UID_EVERYBODY) {
                Logger.i(LOG_TAG_DNS, "skipping domain rule for uid: ${cd.uid}")
                return@forEach
            }
            val key = mkTrieKey(cd.domain, cd.uid)
            val value = mkTrieValue(cd.status.toString(), cd.proxyId, cd.proxyCC)
            noteProxy(cd)
            trie.set(key, value)
            maybeAddToTrustedMap(cd)
            if (cd.proxyCC.isNotEmpty()) selectedCCs.add(cd.proxyCC)
        }
        val trieLen = trie.len()
        Logger.i(LOG_TAG_DNS, "DomainRulesManager: loaded $trieLen rules from db")
        return trieLen
    }

    fun getAllUniqueCCs(): List<String> {
        Logger.v(LOG_TAG_DNS, "getAllUniqueCCs: $selectedCCs")
        return selectedCCs.toList()
    }

    private fun maybeAddToTrustedMap(cd: CustomDomain) {
        if (cd.status != Status.TRUST.id) return

        val domain = cd.domain.lowercase(Locale.ROOT)
        val key = mkTrieKeyForTrustedMap(domain)

        trustedTrie.set(key, cd.status.toString())

        trustedMap.compute(domain) { _, old ->
            (old ?: emptySet()) + cd.uid
        }
    }

    fun status(d: String, uid: Int): Status {
        val st = elapsedRealtime()
        val domain = d.lowercase(Locale.ROOT)
        // check if the domain is added in custom domain list
        when (val rule = getDomainRule(domain, uid)) {
            Status.TRUST -> {
                Logger.d(LOG_TAG_DNS, "DomainRulesManager.getDomainRule($domain, uid=$uid) exact-trusted, time: ${elapsedRealtime() - st} ms")
                return Status.TRUST
            }
            Status.BLOCK -> {
                Logger.d(LOG_TAG_DNS, "DomainRulesManager.getDomainRule($domain, uid=$uid) exact-blocked, time: ${elapsedRealtime() - st} ms")
                return Status.BLOCK
            }
            Status.NONE -> {
                Logger.d(LOG_TAG_DNS, "DomainRulesManager.getDomainRule($domain, uid=$uid) exact-none, time: ${elapsedRealtime() - st} ms")
            }
        }

        // check if the received domain is matching with the custom wildcard
        val wc = matchesWildcard(domain, uid)
        Logger.d(LOG_TAG_DNS, "DomainRulesManager.matchesWildcard($domain, uid=$uid) status=$wc, time: ${elapsedRealtime() - st} ms")
        return wc
    }

    private fun matchesWildcard(domain: String, uid: Int): Status {
        val key = mkTrieKey(domain, uid)
        val match = trie.getAny(key) // matches the longest prefix
        if (match.isNullOrEmpty()) {
            // no match found, return NONE
            if (DEBUG) Logger.vv(LOG_TAG_DNS, "matchesWildcard: $domain($uid), no match found")
            return Status.NONE
        }
        val status = match.split(KV_SEP)[0]
        val res = Status.getStatus(status.toIntOrNull())
        if (DEBUG) Logger.vv(LOG_TAG_DNS, "matchesWildcard: $domain($uid), res: $res")
        return res
    }

    fun getDomainRule(domain: String, uid: Int): Status {
        val key = mkTrieKey(domain, uid)
        val match = trie.get(key)
        if (match.isNullOrEmpty()) {
            // no match found, return NONE
            if (DEBUG) Logger.vv(LOG_TAG_DNS, "domain rule for $key, no match found")
            return Status.NONE
        }
        val status = match.split(KV_SEP)[0]
        val res = Status.getStatus(status.toIntOrNull())
        if (DEBUG) Logger.vv(LOG_TAG_DNS, "domain rule for $key, res: $res")
        return res
    }

    // evaluates domain rules for the given domain(s) and uid.
    // on Android R and above, only the first domain is considered, as the Go backend supplies it
    // as the accurate domain. Returns the resolved Status along with the matched domain (trust
    // takes precedence over block); NONE otherwise. Used by the tunnel managers.
    fun getAggregatedDomainRule(domain: String?, uid: Int): Pair<Status, String?> {
        if (domain.isNullOrEmpty()) {
            return Pair(Status.NONE, "")
        }

        val domains = if (isAtleastR()) {
            // on Android R and above, go will give the first domain as the accurate domain so
            // no need to check further domains
            val d = domain.lowercase(Locale.ROOT).split(",").firstOrNull()
            if (d.isNullOrEmpty()) return Pair(Status.NONE, "")
            listOf(d)
        } else {
            domain.lowercase(Locale.ROOT).split(",")
        }

        if (domains.isEmpty()) {
            return Pair(Status.NONE, "")
        }

        var hasTrustedDomain = false
        var trustedDomain = ""
        var hasBlockedDomain = false
        var blockedDomain = ""
        for (d in domains) {
            when (status(d, uid)) {
                Status.TRUST -> {
                    hasTrustedDomain = true
                    trustedDomain = d
                }

                Status.BLOCK -> {
                    hasBlockedDomain = true
                    blockedDomain = d
                }

                else -> {
                    // no-op
                }
            }
        }

        return when {
            hasTrustedDomain -> Pair(Status.TRUST, trustedDomain)
            hasBlockedDomain -> Pair(Status.BLOCK, blockedDomain)
            else -> Pair(Status.NONE, "")
        }
    }

    /**
     * Per-connection proxy look-up for [RuleProxyResolver]: (proxyId, proxyCC) of the most
     * specific rule of [uid] for [domain] that routes via a proxy (sets a proxyId, a proxyCC or
     * both); ("", "") if none. The exact domain is checked first, then the wildcard rules from
     * the most to the least specific (*.b.example.com, *.example.com, *.com) as status() does.
     * Rules without a proxy (status-only) do not hide a less specific proxy rule.
     */
    fun getProxyForDomain(uid: Int, domain: String): Pair<String, String> {
        if (!mayHaveProxyRules) return NO_PROXY
        return try {
            findProxyForDomain(uid, domain) { trie.get(it) }
        } catch (e: Exception) {
            Logger.w(LOG_TAG_DNS, "err getProxyForDomain($domain, $uid): ${e.message}")
            NO_PROXY
        }
    }

    // pure part of getProxyForDomain; [get] returns the trie value for a trie key or null / ""
    internal fun findProxyForDomain(
        uid: Int,
        domain: String,
        get: (String) -> String?
    ): Pair<String, String> {
        val d = domain.trim().trimEnd('.').lowercase(Locale.ROOT)
        if (d.isEmpty()) return NO_PROXY

        proxyOf(get(mkTrieKey(d, uid)))?.let { return it }

        // wildcard "*.example.com" is keyed as ".example.com" (see mkTrieKey); it matches
        // sub-domains of example.com, not example.com itself
        var i = d.indexOf('.')
        while (i >= 0 && i < d.length - 1) {
            proxyOf(get(mkTrieKey(d.substring(i), uid)))?.let { return it }
            i = d.indexOf('.', i + 1)
        }
        return NO_PROXY
    }

    // trie value is status:proxyId:proxyCC; null when the rule doesn't route via a proxy
    private fun proxyOf(v: String?): Pair<String, String>? {
        if (v.isNullOrEmpty()) return null
        val parts = v.split(KV_SEP)
        if (parts.size < 3) return null
        val proxyId = parts[1]
        val proxyCC = parts[2]
        if (proxyId.isEmpty() && proxyCC.isEmpty()) return null
        return Pair(proxyId, proxyCC)
    }

    fun isDomainTrusted(d: String?): Boolean {
        val st = elapsedRealtime()
        if (d.isNullOrEmpty()) {
            Logger.d(LOG_TAG_DNS, "DomainRulesManager.isDomainTrusted(empty) for $d, time: ${elapsedRealtime() - st} ms")
            return false
        }
        val domain = d.lowercase(Locale.ROOT)
        val res = trustedTrie.hasAny(domain)
        Logger.d(LOG_TAG_DNS, "DomainRulesManager.isDomainTrusted($domain) result=$res, time: ${elapsedRealtime() - st} ms")
        return res
    }

    /*
     * Status changes (trust / block / noRule / changeStatus / addDomainRule / updateDomainRule)
     * never touch a rule's proxy (proxyId / proxyCC): the stored proxy is kept, even when the
     * caller holds a stale CustomDomain or builds a new one. A proxy is set or cleared only
     * through setProxy / clearProxy / setCC, or by passing proxyId explicitly.
     */

    suspend fun trust(cd: CustomDomain) = setStatus(cd, Status.TRUST)

    suspend fun block(cd: CustomDomain) = setStatus(cd, Status.BLOCK)

    suspend fun noRule(cd: CustomDomain) = setStatus(cd, Status.NONE)

    private suspend fun setStatus(cd: CustomDomain, status: Status) {
        keepStoredProxy(cd)
        cd.status = status.id
        cd.modifiedTs = Calendar.getInstance().timeInMillis
        dbInsertOrUpdate(cd)
        updateTrie(cd)
        maybeUpdateTrustedMap(cd.uid, cd.domain, status)
    }

    // copies the stored rule's proxy onto [cd] (no-op when the rule isn't stored yet)
    private suspend fun keepStoredProxy(cd: CustomDomain) {
        val stored = db.getCustomDomain(cd.uid, cd.domain) ?: return
        cd.proxyId = stored.proxyId
        cd.proxyCC = stored.proxyCC
    }

    suspend fun changeStatus(
        domain: String,
        uid: Int,
        ips: String,
        type: DomainType,
        status: Status
    ) {
        upsert(domain, uid, ips, type, status, proxyId = null)
    }

    suspend fun block(domain: String, uid: Int, ips: String = "", type: DomainType) {
        upsert(domain, uid, ips, type, Status.BLOCK, proxyId = null)
    }

    /**
     * Adds (or overwrites) the rule for [d] / [uid] with [status]. [proxyId]: null keeps the
     * stored rule's proxy (none for a new rule), "" clears it, anything else sets it.
     */
    suspend fun addDomainRule(
        d: String,
        status: Status,
        type: DomainType,
        uid: Int,
        proxyId: String? = null
    ) {
        upsert(d, uid, "", type, status, proxyId)
    }

    private suspend fun upsert(
        domain: String,
        uid: Int,
        ips: String,
        type: DomainType,
        status: Status,
        proxyId: String?
    ): CustomDomain {
        val stored = db.getCustomDomain(uid, domain)
        val cd = mkCustomDomain(
            domain, uid, ips, type, status.id,
            proxyId = proxyId ?: stored?.proxyId.orEmpty(),
            proxyCC = stored?.proxyCC.orEmpty()
        )
        dbInsertOrUpdate(cd)
        updateTrie(cd)
        maybeUpdateTrustedMap(uid, domain, status)
        return cd
    }

    /**
     * Routes the rule of [cd] (its uid and domain) via [proxyId]; "" clears it (no override).
     * The stored status is kept; a domain without a rule gets one with [cd]'s status (so a
     * rule can route via a proxy without blocking or trusting). Takes effect immediately for
     * new connections. Returns the stored rule.
     */
    suspend fun setProxy(cd: CustomDomain, proxyId: String): CustomDomain {
        val stored = db.getCustomDomain(cd.uid, cd.domain)
        val next = CustomDomain(
            cd.domain,
            cd.uid,
            stored?.ips ?: cd.ips,
            stored?.type ?: cd.type,
            stored?.status ?: cd.status,
            proxyId,
            stored?.proxyCC ?: cd.proxyCC,
            Calendar.getInstance().timeInMillis,
            Constants.INIT_TIME_MS,
            CustomDomain.getCurrentVersion()
        )
        dbInsertOrUpdate(next)
        updateTrie(next)
        maybeUpdateTrustedMap(next.uid, next.domain, Status.getStatus(next.status))
        Logger.i(LOG_TAG_DNS, "setProxy: ${next.domain}(${next.uid}) -> '$proxyId'")
        return next
    }

    suspend fun clearProxy(cd: CustomDomain): CustomDomain = setProxy(cd, "")

    private fun maybeUpdateTrustedMap(uid: Int, domain: String, status: Status) {
        val d = domain.lowercase(Locale.ROOT)

        val result = trustedMap.compute(d) { _, old ->
            val updated = if (status == Status.TRUST) {
                (old ?: emptySet()) + uid
            } else {
                (old ?: emptySet()) - uid
            }

            updated.ifEmpty { null }
        }

        val key = mkTrieKeyForTrustedMap(d)

        if (result == null) {
            trustedTrie.del(key)
        } else {
            trustedTrie.set(key, status.id.toString())
        }
    }

    /**
     * Replaces [prevDomain] with a rule for [d]. [proxyId]: null keeps the previous rule's
     * proxy (its proxyCC is always kept), "" clears it, anything else sets it.
     */
    suspend fun updateDomainRule(
        d: String,
        status: Status,
        type: DomainType,
        prevDomain: CustomDomain,
        proxyId: String? = null
    ) {
        val stored = db.getCustomDomain(prevDomain.uid, prevDomain.domain) ?: prevDomain
        val cd = mkCustomDomain(
            d, prevDomain.uid, "", type, status.id,
            proxyId = proxyId ?: stored.proxyId,
            proxyCC = stored.proxyCC
        )
        dbUpdate(prevDomain, cd)
        removeFromTrie(prevDomain)
        removeIfInTrustedMap(prevDomain.uid, prevDomain.domain)
        updateTrie(cd)
        // was always Status.BLOCK, so an edited trusted rule dropped out of the trusted map
        maybeUpdateTrustedMap(cd.uid, cd.domain, status)
    }

    private suspend fun dbInsertOrUpdate(cd: CustomDomain) {
        db.insert(cd)
    }

    private suspend fun dbUpdate(prevDomain: CustomDomain, cd: CustomDomain) {
        db.update(prevDomain, cd)
    }

    private suspend fun dbDelete(cd: CustomDomain) {
        db.delete(cd)
    }

    suspend fun deleteDomain(cd: CustomDomain) {
        dbDelete(cd)
        removeFromTrie(cd)
        removeIfInTrustedMap(cd.uid, cd.domain)
    }

    private fun removeIfInTrustedMap(uid: Int, domain: String) {
        val d = domain.lowercase(Locale.ROOT)
        val result = trustedMap.compute(d) { _, old ->
            val updated = (old ?: emptySet()) - uid
            updated.ifEmpty { null }
        }
        val key = mkTrieKeyForTrustedMap(d)

        if (result == null) {
            trustedTrie.del(key)
        }
    }

    private fun clearTrustedMap(uid: Int) {
        trustedMap.keys.forEach { domain ->
            val result = trustedMap.compute(domain) { _, old ->
                val updated = (old ?: emptySet()) - uid
                updated.ifEmpty { null }
            }

            val key = mkTrieKeyForTrustedMap(domain)

            if (result == null) {
                trustedTrie.del(key)
            }
        }
    }

    suspend fun deleteRulesByUid(uid: Int) {
        db.deleteRulesByUid(uid)
        val rulesDeleted = trie.delAll(uid.toString())
        Logger.i(LOG_TAG_DNS, "rules deleted from trie for $uid: $rulesDeleted")
        clearTrustedMap(uid)
    }

    suspend fun deleteRules(list: List<CustomDomain>) {
        list.forEach { cd ->
            removeFromTrie(cd)
            removeIfInTrustedMap(cd.uid, cd.domain)
        }
        db.deleteRules(list)
    }

    /** Deletes every app-specific rule; the universal (all apps) rules stay. */
    suspend fun deleteAllAppsRules() {
        val apps = db.getAllCustomDomains().filter { it.uid != Constants.UID_EVERYBODY }
        deleteRules(apps)
    }

    suspend fun deleteAllRules() {
        db.deleteAllRules()
        trie.clear()
        mayHaveProxyRules = false
        trustedMap.clear()
        trustedTrie.clear()
    }

    private fun removeFromTrie(cd: CustomDomain) {
        val key = mkTrieKey(cd.domain, cd.uid)
        trie.del(key)
    }

    // Room's DAO returns a new LiveData instance on every call; cache it so
    // observers and value-reads share the same instance.
    private val cachedDomainCountLiveData: LiveData<Int> by lazy { db.getUniversalCustomDomainCount() }

    fun getUniversalCustomDomainCount(): LiveData<Int> {
        return cachedDomainCountLiveData
    }

    suspend fun getRulesCountByCC(cc: String): Int {
        return db.getRulesCountByCC(cc)
    }

    fun isValidDomain(url: String): Boolean {
        return try {
            Patterns.WEB_URL.matcher(url).matches() || Patterns.DOMAIN_NAME.matcher(url).matches()
        } catch (_: MalformedURLException) {
            false
        }
    }

    suspend fun updateUids(uids: List<Int>, newUids: List<Int>) {
        val dms = db.getAllCustomDomains()
        for (i in uids.indices) {
            val uid = uids[i]
            val newUid = newUids[i]
            if (dms.any { it.uid == uid }) {
                updateUid(uid, newUid)
            }
        }
        Logger.i(LOG_TAG_FIREWALL, "domain rules updated")
    }

    suspend fun updateUid(uid: Int, newUid: Int) {
        clearTrie(uid)
        clearTrustedMap(uid)
        db.updateUid(uid, newUid)
        rehydrateFromDB(newUid)
    }

    private suspend fun rehydrateFromDB(uid: Int) {
        val doms = db.getDomainsByUID(uid)
        if (doms.isEmpty()) {
            Logger.w(LOG_TAG_DNS, "rehydrate: zero domains for uid: $uid in db")
            return
        }

        Logger.i(LOG_TAG_DNS, "rehydrate: rehydrating ${doms.size} domains for uid: $uid")
        // process longer domains first
        val selector: (String) -> Int = { str -> str.length }
        val desc = doms.sortedByDescending { selector(it.domain) }
        desc.forEach { cd ->
            val key = mkTrieKey(cd.domain, cd.uid)
            val value = mkTrieValue(cd.status.toString(), cd.proxyId, cd.proxyCC)
            noteProxy(cd)
            trie.set(key, value)
            maybeAddToTrustedMap(cd)
        }
    }

    private fun clearTrie(uid: Int) {
        trie.delAll(uid.toString())
    }

    suspend fun setCC(cd: CustomDomain, cc: String) {
        val stored = db.getCustomDomain(cd.uid, cd.domain) ?: cd
        cd.proxyCC = cc
        val newCd = CustomDomain(stored.domain, stored.uid, stored.ips, stored.type, stored.status, stored.proxyId, cc, stored.modifiedTs, 0L, stored.version)
        Logger.d(LOG_TAG_DNS, "setCC: updating domain: ${cd.domain} to cc: $cc")
        dbInsertOrUpdate(newCd)
        updateTrie(newCd)
        if (cc.isNotEmpty()) selectedCCs.add(cc)
        Logger.i(LOG_TAG_DNS, "setCC: updated domain: ${cd.domain} to $cc")
    }

    @Deprecated("use setProxy", ReplaceWith("setProxy(cd, proxyId)"))
    suspend fun setProxyId(cd: CustomDomain, proxyId: String) {
        val next = setProxy(cd, proxyId)
        cd.proxyId = next.proxyId
        cd.status = next.status
    }

    suspend fun tombstoneRulesByUid(oldUid: Int) {
        Logger.i(LOG_TAG_FIREWALL, "tombstone rules for uid: $oldUid")
        // here tombstone means negating the uid of the rule
        // this is used when the app is uninstalled, so that the rules are not deleted
        // but the uid is set to (-1 * uid), so that the rules are not applied
        val newUid = if (oldUid > 0) -1 * oldUid else oldUid
        if (oldUid == newUid) {
            Logger.w(LOG_TAG_FIREWALL, "tombstone: same uids, old: $oldUid, new: $newUid, no-op")
            return
        }
        db.tombstoneRulesByUid(oldUid, newUid)
        load()
    }


    fun isWildCardEntry(url: String): Boolean {
        return wcRegex.matcher(url).matches()
    }

    /**
     * Extracts and validates the host from various input formats
     * Handles wildcards, URLs with schemas, and plain domains
     *
     * @param input The domain input string (e.g., "*.example.com", "https://example.com", "example.com")
     * @return The extracted host string, or null if invalid
     *
     * Supported formats:
     * - Wildcard domains: *.example.com, *.eu
     * - URLs with schema: https://www.example.com → example.com
     * - Plain domains: example.com
     *
     * Invalid formats:
     * - Wildcards with schema: https:// *.example.com
     */
    fun extractHost(input: String): String? {
        val trimmedInput = input.trim()

        return when {
            // case: valid wildcard input without schema, eg., *.example.com, *.eu
            trimmedInput.startsWith("*.") && !trimmedInput.contains("://") -> {
                trimmedInput
            }

            // case: invalid wildcard with schema, eg., https://*.example.com
            trimmedInput.contains("://") && trimmedInput.contains("*") -> {
                null // Invalid: Wildcards shouldn't appear in URLs
            }

            // case: standard URL input, eg., https://www.example.com
            trimmedInput.contains("://") -> {
                try {
                    // return the host part of the URL
                    // only www. is the common prefix you'd want to strip for cosmetic or
                    // standardization reasons (like www.google.com → google.com). Other subdomains
                    // (e.g., mail., api., m.) are actually part of the valid hostname and
                    // should not be removed
                    val uri = java.net.URI(trimmedInput)
                    uri.host?.removePrefix("www.") // remove 'www.' prefix if present
                } catch (e: Exception) {
                    null
                }
            }

            // case: plain domain (no schema, no wildcard), eg., example.com
            else -> trimmedInput
        }
    }

    // this is to create a custom domain entry where user want to add proxy without any
    // rules set, this is created for the new ui, should be made generic
    fun makeCustomDomain(uid: Int, domain: String): CustomDomain {
        return mkCustomDomain(domain, uid, "", DomainType.DOMAIN, Status.NONE.id)
    }

    private fun mkCustomDomain(
        domain: String,
        uid: Int,
        ips: String = "",
        type: DomainType,
        status: Int,
        proxyId: String = "",
        proxyCC: String = ""
    ): CustomDomain {
        return CustomDomain(
            domain,
            uid,
            ips,
            type.id,
            status,
            proxyId,
            proxyCC,
            Calendar.getInstance().timeInMillis,
            Constants.INIT_TIME_MS,
            CustomDomain.getCurrentVersion()
        )
    }

    suspend fun stats(): String {
        val sb = StringBuilder()
        sb.append("   Trie: ${trie.len()}\n")
        sb.append("   Trusted: ${trustedTrie.len()}\n")
        sb.append("   db: ${db.getCustomDomainCount()}\n")

        return sb.toString()
    }
}
