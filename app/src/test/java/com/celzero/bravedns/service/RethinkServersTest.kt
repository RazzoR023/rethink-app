package com.celzero.bravedns.service

import com.celzero.bravedns.service.RethinkServers.Config
import com.celzero.bravedns.service.RethinkServers.Transport
import com.celzero.bravedns.util.Constants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RethinkServersTest {

    private val def = Config()
    private val custom = Config("https://dns.example.com")
    private val customDot = Config("https://dns.example.com", customDot = true)
    private val b32: (String) -> String = { if (it.isEmpty()) "" else "B32($it)" }

    // ---- normalize ----

    @Test fun normalize_blankIsDefault() {
        assertEquals("", RethinkServers.normalize(""))
        assertEquals("", RethinkServers.normalize("   "))
        assertEquals("", RethinkServers.normalize(null))
    }

    @Test fun normalize_addsSchemeStripsPathAndSlash() {
        assertEquals("https://dns.example.com", RethinkServers.normalize("dns.example.com"))
        assertEquals("https://dns.example.com", RethinkServers.normalize("https://dns.example.com/"))
        assertEquals("https://dns.example.com", RethinkServers.normalize(" https://DNS.Example.com/dns-query?x=1#f "))
        assertEquals("https://dns.example.com", RethinkServers.normalize("https://dns.example.com/1:IAAgAA=="))
        assertEquals("https://dns.example.com", RethinkServers.normalize("https://dns.example.com:443/"))
        assertEquals("https://dns.example.com:8443", RethinkServers.normalize("dns.example.com:8443/x"))
        assertEquals("https://[2001:db8::1]", RethinkServers.normalize("https://[2001:db8::1]/x"))
        assertEquals("https://1.2.3.4", RethinkServers.normalize("1.2.3.4"))
    }

    @Test fun normalize_rejectsInvalid() {
        assertNull(RethinkServers.normalize("http://dns.example.com"))
        assertNull(RethinkServers.normalize("tls://dns.example.com"))
        assertNull(RethinkServers.normalize("localhost"))
        assertNull(RethinkServers.normalize("dns..example.com"))
        assertNull(RethinkServers.normalize("-bad.example.com"))
        assertNull(RethinkServers.normalize("dns example.com"))
        assertNull(RethinkServers.normalize("https://user:pw@dns.example.com"))
        assertNull(RethinkServers.normalize("https://"))
    }

    @Test fun config_host() {
        assertEquals("dns.example.com", custom.customHost)
        assertEquals("dns.example.com", Config("https://dns.example.com:8443").customHost)
        assertEquals("", def.customHost)
        assertFalse(def.isCustom)
        assertFalse(Config("", customDot = true).useDot)
        assertTrue(customDot.useDot)
        assertFalse(custom.useDot)
    }

    // ---- url building ----

    @Test fun dohBase_defaultVsCustom() {
        assertEquals(Constants.RETHINK_BASE_URL_SKY, RethinkServers.dohBase(def, isMax = false))
        assertEquals(Constants.RETHINK_BASE_URL_MAX, RethinkServers.dohBase(def, isMax = true))
        assertEquals("https://dns.example.com/", RethinkServers.dohBase(custom, isMax = false))
        assertEquals("https://dns.example.com/", RethinkServers.dohBase(custom, isMax = true))
    }

    @Test fun blockFree_defaultVsCustom() {
        assertEquals(Constants.BLOCK_FREE_DNS_SKY, RethinkServers.blockFreeUrl(def, false))
        assertEquals(Constants.BLOCK_FREE_DNS_MAX, RethinkServers.blockFreeUrl(def, true))
        assertEquals("https://dns.example.com/dns-query", RethinkServers.blockFreeUrl(custom, true))
        assertEquals("https://dns.example.com:8443/dns-query", RethinkServers.blockFreeUrl(Config("https://dns.example.com:8443"), false))
    }

    @Test fun storedUrl_isAlwaysOfficial() {
        assertEquals("https://sky.rethinkdns.com/1:abc", RethinkServers.storedUrl(false, "1:abc"))
        assertEquals("https://max.rethinkdns.com/1:abc", RethinkServers.storedUrl(true, "1:abc"))
    }

    @Test fun dotUrl_custom() {
        assertEquals("tls://1-abc.dns.example.com", RethinkServers.dotUrl(customDot, "1-abc"))
        assertEquals("tls://dns.example.com", RethinkServers.dotUrl(customDot, ""))
        assertEquals("tls://dns.example.com", RethinkServers.dotUrl(Config("https://dns.example.com:8443", true), "dns-query"))
    }

    // ---- transport (what GoVpnAdapter dials) ----

    @Test fun transport_default_matchesOldBehaviour() {
        assertEquals(Transport("https://sky.rethinkdns.com/B32(1:IAAgAA==)", false),
            RethinkServers.transport(def, "https://sky.rethinkdns.com/1:IAAgAA==", b32))
        assertEquals(Transport("https://max.rethinkdns.com/B32(1:IAAgAA==)", false),
            RethinkServers.transport(def, "https://max.rethinkdns.com/1:IAAgAA==", b32))
        // non-max official hosts (basic) go to sky, as before
        assertEquals(Transport("https://sky.rethinkdns.com/B32(1:x)", false),
            RethinkServers.transport(def, "https://basic.rethinkdns.com/1:x", b32))
        // block-free urls pass through
        listOf(Constants.BLOCK_FREE_DNS_SKY, Constants.BLOCK_FREE_DNS_MAX, Constants.BLOCK_FREE_DNS_ZERO).forEach {
            assertEquals(Transport(it, false), RethinkServers.transport(def, it, b32))
        }
        // dot setting is ignored for the official servers
        assertEquals(Transport("https://max.rethinkdns.com/B32(1:x)", false),
            RethinkServers.transport(Config("", customDot = true), "https://max.rethinkdns.com/1:x", b32))
        // not a rethink url
        assertNull(RethinkServers.transport(def, "https://dns.google/dns-query", b32))
    }

    @Test fun transport_custom_doh() {
        assertEquals(Transport("https://dns.example.com/B32(1:IAAgAA==)", false),
            RethinkServers.transport(custom, "https://max.rethinkdns.com/1:IAAgAA==", b32))
        assertEquals(Transport("https://dns.example.com/B32(1:IAAgAA==)", false),
            RethinkServers.transport(custom, "https://sky.rethinkdns.com/1:IAAgAA==", b32))
        // block-free (official or custom) maps to <base>/dns-query
        assertEquals(Transport("https://dns.example.com/dns-query", false),
            RethinkServers.transport(custom, Constants.BLOCK_FREE_DNS_MAX, b32))
        assertEquals(Transport("https://dns.example.com/dns-query", false),
            RethinkServers.transport(custom, "https://dns.example.com/dns-query", b32))
        // stamp-less endpoint
        assertEquals(Transport("https://dns.example.com/dns-query", false),
            RethinkServers.transport(custom, "https://sky.rethinkdns.com/", b32))
        // a url already on the custom host
        assertEquals(Transport("https://dns.example.com/B32(1:abc)", false),
            RethinkServers.transport(custom, "https://dns.example.com/1:abc", b32))
    }

    @Test fun transport_custom_dot() {
        assertEquals(Transport("tls://B32(1:IAAgAA==).dns.example.com", true),
            RethinkServers.transport(customDot, "https://max.rethinkdns.com/1:IAAgAA==", b32))
        assertEquals(Transport("tls://dns.example.com", true),
            RethinkServers.transport(customDot, Constants.BLOCK_FREE_DNS_SKY, b32))
        assertEquals(Transport("tls://dns.example.com", true),
            RethinkServers.transport(customDot, "https://dns.example.com/dns-query", b32))
    }

    @Test fun displayUrl() {
        assertEquals("https://max.rethinkdns.com/1:x", RethinkServers.displayUrl(def, "https://max.rethinkdns.com/1:x"))
        assertEquals("https://dns.example.com/1:x", RethinkServers.displayUrl(custom, "https://max.rethinkdns.com/1:x"))
        assertEquals("https://dns.example.com/dns-query", RethinkServers.displayUrl(custom, Constants.BLOCK_FREE_DNS_SKY))
        assertEquals("https://dns.example.com/dns-query", RethinkServers.displayUrl(custom, "https://sky.rethinkdns.com/"))
        assertEquals("https://dns.google/dns-query", RethinkServers.displayUrl(custom, "https://dns.google/dns-query"))
    }

    // ---- predicate & stamp parsing ----

    @Test fun isRethinkUrl() {
        assertTrue(RethinkServers.isRethinkUrl(def, "https://sky.rethinkdns.com/1:x"))
        assertTrue(RethinkServers.isRethinkUrl(def, "1-abc.max.rethinkdns.com"))
        assertFalse(RethinkServers.isRethinkUrl(def, "https://dns.example.com/1:x"))
        assertTrue(RethinkServers.isRethinkUrl(custom, "https://dns.example.com/1:x"))
        assertTrue(RethinkServers.isRethinkUrl(custom, "https://DNS.example.com/1:x"))
        assertTrue(RethinkServers.isRethinkUrl(custom, "https://max.rethinkdns.com/1:x"))
        assertFalse(RethinkServers.isRethinkUrl(custom, "https://dns.google/dns-query"))
    }

    @Test fun stampOf() {
        assertEquals("1:IAAgAA==", RethinkServers.stampOf(def, "https://max.rethinkdns.com/1:IAAgAA=="))
        assertEquals("", RethinkServers.stampOf(def, "https://max.rethinkdns.com/"))
        assertEquals("dns-query", RethinkServers.stampOf(def, Constants.BLOCK_FREE_DNS_SKY))
        assertEquals("1:abc", RethinkServers.stampOf(custom, "https://dns.example.com/1:abc"))
        assertEquals("1:abc", RethinkServers.stampOf(Config("https://dns.example.com:8443"), "https://dns.example.com:8443/1:abc?x"))
        assertEquals("1:abc", RethinkServers.stampOf(custom, "https://sky.rethinkdns.com/1:abc"))
        assertEquals("", RethinkServers.stampOf(custom, "https://dns.google/1:abc"))
    }

    @Test fun extractStamp_official() {
        assertEquals("1:IAAgAA==", RethinkServers.extractStamp(def, "https://max.rethinkdns.com/1:IAAgAA=="))
        assertEquals("1:-AcHAL6d_5-Yxf7zMQAKAAAI", RethinkServers.extractStamp(def, "https://max.rethinkdns.com/1:-AcHAL6d_5-Yxf7zMQAKAAAI"))
        assertEquals("1-acaabaa", RethinkServers.extractStamp(def, "//1-acaabaa.max.rethinkdns.com"))
        assertEquals("1-acaabaa", RethinkServers.extractStamp(def, "1-acaabaa.rethinkdns.com"))
        assertNull(RethinkServers.extractStamp(def, "1-acaabaa.dns.example.com"))
        assertNull(RethinkServers.extractStamp(def, "https://max.rethinkdns.com/"))
    }

    @Test fun extractStamp_customHost() {
        assertEquals("1:IAAgAA==", RethinkServers.extractStamp(custom, "https://dns.example.com/1:IAAgAA=="))
        assertEquals("1-acaabaa", RethinkServers.extractStamp(custom, "1-acaabaa.dns.example.com"))
        assertEquals("1-acaabaa", RethinkServers.extractStamp(custom, "tls://1-acaabaa.dns.example.com"))
        assertEquals("1-acaabaa", RethinkServers.extractStamp(custom, "1-acaabaa.max.rethinkdns.com"))
        assertNull(RethinkServers.extractStamp(custom, "1-acaabaa.other.org"))
    }

    // ---- dns wire format ----

    @Test fun buildDnsQuery_wireFormat() {
        val q = RethinkServers.buildDnsQuery("example.com")
        val expected = byteArrayOf(0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0, 0, 1, 0, 1)
        assertArrayEquals(expected, q)
    }

    @Test fun isGoodDnsResponse() {
        fun header(flags2: Int, flags3: Int, an: Int) =
            byteArrayOf(0, 0, flags2.toByte(), flags3.toByte(), 0, 1, 0, an.toByte(), 0, 0, 0, 0)
        assertTrue(RethinkServers.isGoodDnsResponse(header(0x81, 0x80, 1)))
        assertFalse(RethinkServers.isGoodDnsResponse(header(0x81, 0x83, 0))) // nxdomain
        assertFalse(RethinkServers.isGoodDnsResponse(header(0x81, 0x80, 0))) // no answers
        assertFalse(RethinkServers.isGoodDnsResponse(header(0x01, 0x00, 1))) // a query, not a response
        assertFalse(RethinkServers.isGoodDnsResponse(byteArrayOf(1, 2, 3)))
        assertFalse(RethinkServers.isGoodDnsResponse(null))
    }

    // ---- access secret (serverless-dns ACCESS_KEYS) ----

    private val keyed = Config("https://dns.example.com", secret = "abc123")
    private val keyedDot = Config("https://dns.example.com", customDot = true, secret = "abc123")

    @Test fun secret_ignoredForDefaultServers() {
        val c = Config("", secret = "abc123")
        assertEquals("", c.activeSecret)
        assertEquals(Transport("https://max.rethinkdns.com/B32(1:x)", false),
            RethinkServers.transport(c, "https://max.rethinkdns.com/1:x", b32))
        assertEquals(Constants.BLOCK_FREE_DNS_SKY, RethinkServers.blockFreeUrl(c, false))
    }

    @Test fun secret_invalidIsIgnored() {
        assertEquals("", Config("https://dns.example.com", secret = "ab-c").activeSecret)
        assertEquals("", Config("https://dns.example.com", secret = "a b").activeSecret)
        assertTrue(RethinkServers.isValidSecret("Abc123"))
        assertFalse(RethinkServers.isValidSecret(""))
        assertFalse(RethinkServers.isValidSecret("abc_1"))
    }

    // forms verified against serverless-dns extractStamps / msgkeyFromUrl (node one-off)
    @Test fun secret_doh() {
        assertEquals(Transport("https://dns.example.com/B32(1:IAAgAA==):abc123", false),
            RethinkServers.transport(keyed, "https://max.rethinkdns.com/1:IAAgAA==", b32))
        // b32 stamps take the b32 delimiter: /1-<stamp>-<secret>
        assertEquals(Transport("https://dns.example.com/1-acaabaa-abc123", false),
            RethinkServers.transport(keyed, "https://max.rethinkdns.com/1:IAAgAA==", { "1-acaabaa" }))
        // block-free: empty blockstamp + secret
        assertEquals("https://dns.example.com/1::abc123", RethinkServers.blockFreeUrl(keyed, false))
        assertEquals(Transport("https://dns.example.com/1::abc123", false),
            RethinkServers.transport(keyed, RethinkServers.blockFreeUrl(keyed, false), b32))
        assertEquals(Transport("https://dns.example.com/1::abc123", false),
            RethinkServers.transport(keyed, Constants.BLOCK_FREE_DNS_MAX, b32))
        assertEquals(Transport("https://dns.example.com/1::abc123", false),
            RethinkServers.transport(keyed, "https://dns.example.com/dns-query", b32))
    }

    @Test fun secret_dot() {
        assertEquals(Transport("tls://1-acaabaa-abc123.dns.example.com", true),
            RethinkServers.transport(keyedDot, "https://max.rethinkdns.com/1:IAAgAA==", { "1-acaabaa" }))
        assertEquals(Transport("tls://1--abc123.dns.example.com", true),
            RethinkServers.transport(keyedDot, Constants.BLOCK_FREE_DNS_SKY, b32))
    }

    @Test fun secret_stampParsingStripsSecret() {
        assertEquals("1:IAAgAA==", RethinkServers.stampOf(keyed, "https://dns.example.com/1:IAAgAA==:abc123"))
        assertEquals("1-acaabaa", RethinkServers.stampOf(keyed, "https://dns.example.com/1-acaabaa-abc123"))
        assertEquals("1:-J8AEH8Dv73_8______-___z6f9eagBA",
            RethinkServers.stampOf(keyed, "https://dns.example.com/1:-J8AEH8Dv73_8______-___z6f9eagBA:abc123"))
        assertEquals("", RethinkServers.stampOf(keyed, "https://dns.example.com/1::abc123"))
        assertEquals("1:x", RethinkServers.stripSecret("1:x"))
        assertEquals("dns-query", RethinkServers.stripSecret("dns-query"))
        // pasted keyed urls still yield the blocklist stamp
        assertEquals("1:IAAgAA==", RethinkServers.extractStamp(keyed, "https://dns.example.com/1:IAAgAA==:abc123"))
        assertEquals("1-acaabaa", RethinkServers.extractStamp(keyed, "1-acaabaa-abc123.dns.example.com"))
        // a re-keyed stored url does not double the secret
        assertEquals(Transport("https://dns.example.com/1:x:abc123", false),
            RethinkServers.transport(keyed, "https://dns.example.com/1:x:abc123") { it })
    }

    @Test fun secret_displayUrl() {
        assertEquals("https://dns.example.com/1:x:abc123", RethinkServers.displayUrl(keyed, "https://sky.rethinkdns.com/1:x"))
        assertEquals("https://dns.example.com/1:x", RethinkServers.displayUrl(keyed, "https://sky.rethinkdns.com/1:x", withSecret = false))
        assertEquals("https://dns.example.com/dns-query", RethinkServers.displayUrl(keyed, Constants.BLOCK_FREE_DNS_SKY, withSecret = false))
    }

    @Test fun secret_redact() {
        assertEquals("https://dns.example.com/1::<secret>", RethinkServers.redact(keyed, "https://dns.example.com/1::abc123"))
        assertEquals("x abc123", RethinkServers.redact(custom, "x abc123"))
        assertFalse(keyed.toString().contains("abc123"))
    }

    @Test fun generateSecret() {
        val a = RethinkServers.generateSecret()
        val b = RethinkServers.generateSecret()
        assertEquals(RethinkServers.GENERATED_SECRET_LENGTH, a.length)
        assertTrue(RethinkServers.isValidSecret(a))
        assertTrue(a != b)
    }

    // reference values from serverless-dns's own hmackey + HMAC sign (auth-token.js genInternal,
    // run in node); the first matches env.js's default ACCESS_KEYS "localhost|1e84b3c687"
    @Test fun accessKey_matchesServerlessDns() {
        assertEquals("localhost|1e84b3c68759ce9c504a2cb5be8ef49efed4901459883a7de6dab4184557b272",
            RethinkServers.accessKey("1123213213", "localhost"))
        assertEquals("dns.example.com|5a5a768cf366df77da22ca9d3572d564259fd0b70207dbf07d533f4049d7e428",
            RethinkServers.accessKey("abc123xyz", "dns.example.com"))
        // gen() lowercases both msg and domain
        assertEquals("dns.example.com|5a5a768cf366df77da22ca9d3572d564259fd0b70207dbf07d533f4049d7e428",
            RethinkServers.accessKey("ABC123xyz", "DNS.Example.com"))
        assertEquals("dns.example.com|8d680f4af81896c1894253976f617d0656c396d841b06204ca99f0a8e578a2a6",
            RethinkServers.accessKey("k3y9secretvalue1", "dns.example.com"))
    }
}
