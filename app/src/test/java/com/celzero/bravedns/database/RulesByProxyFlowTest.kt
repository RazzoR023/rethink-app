/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celzero.bravedns.ui.compose.proxy.ProxyRulesLogic
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The proxy pages' "IP & domain rules" queries: rules of any uid (All apps included) whose
 * proxyId is the page's proxy, newest first, re-emitted when a rule is routed or unrouted.
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class RulesByProxyFlowTest {

    private lateinit var db: AppDatabase
    private lateinit var ipDao: CustomIpDao
    private lateinit var domainDao: CustomDomainDAO

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        ipDao = db.customIpEndpointDao()
        domainDao = db.customDomainEndpointDAO()
    }

    @After fun tearDown() {
        db.close()
    }

    private fun ip(uid: Int, addr: String, proxyId: String, ts: Long) = CustomIp().apply {
        this.uid = uid; ipAddress = addr; port = 0; this.proxyId = proxyId; modifiedDateTime = ts
    }

    private fun domain(uid: Int, d: String, proxyId: String, ts: Long) =
        CustomDomain(d, uid, "", 0, 0, proxyId, "", ts, 0L, 1L)

    @Test fun ipFlow_onlyThisProxy_anyUid_newestFirst() = runBlocking {
        ipDao.insert(ip(10042, "1.1.1.1", "S5", 1))
        ipDao.insert(ip(UID_EVERYBODY, "8.8.8.8", "S5", 3))
        ipDao.insert(ip(10042, "9.9.9.9", "wg1", 5))
        ipDao.insert(ip(10043, "4.4.4.4", "", 7))
        val rows = ipDao.getRulesByProxyIdFlow("S5").first()
        assertEquals(listOf("8.8.8.8", "1.1.1.1"), rows.map { it.ipAddress })
        assertEquals(setOf(UID_EVERYBODY, 10042), rows.map { it.uid }.toSet())
    }

    @Test fun domainFlow_onlyThisProxy_newestFirst() = runBlocking {
        domainDao.insert(domain(10042, "a.example", "wg2", 2))
        domainDao.insert(domain(UID_EVERYBODY, "b.example", "wg2", 4))
        domainDao.insert(domain(10042, "c.example", "wg20", 6))
        val rows = domainDao.getRulesByProxyIdFlow("wg2").first()
        // "wg20" must not match "wg2"
        assertEquals(listOf("b.example", "a.example"), rows.map { it.domain })
    }

    @Test fun flows_reEmitWhenARuleIsRoutedOrUnrouted() = runBlocking {
        val r = ip(10042, "1.1.1.1", "", 1)
        ipDao.insert(r)
        assertTrue(ipDao.getRulesByProxyIdFlow("HTTP").first().isEmpty())

        ipDao.update(r.apply { proxyId = "HTTP"; modifiedDateTime = 2 })
        assertEquals(1, ipDao.getRulesByProxyIdFlow("HTTP").first().size)

        ipDao.update(r.apply { proxyId = ""; modifiedDateTime = 3 })
        assertTrue(ipDao.getRulesByProxyIdFlow("HTTP").first().isEmpty())
    }

    @Test fun mergedSection_ipAndDomain_newestFirst() = runBlocking {
        ipDao.insert(ip(10042, "1.1.1.1", "ORBOT", 10))
        domainDao.insert(domain(UID_EVERYBODY, "tor.example", "ORBOT", 20))
        domainDao.insert(domain(10042, "other.example", "S5", 30))
        val merged = combine(ipDao.getRulesByProxyIdFlow("ORBOT"), domainDao.getRulesByProxyIdFlow("ORBOT")) { i, d ->
            ProxyRulesLogic.merge("ORBOT", i, d)
        }.first()
        assertEquals(listOf("tor.example", "1.1.1.1"), merged.map { it.value })
        assertTrue(merged.all { it.proxyId == "ORBOT" })
    }
}
