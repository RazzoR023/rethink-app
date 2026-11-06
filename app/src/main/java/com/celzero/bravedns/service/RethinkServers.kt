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

import com.celzero.bravedns.util.Constants
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.regex.Pattern

/**
 * Where Rethink DNS (the serverless-dns blocklist resolver) is reached.
 *
 * By default the official hosts are used (sky / max.rethinkdns.com). The user may point the
 * app at a self-hosted serverless-dns instance ([PersistentState.customRethinkDnsUrl]); then
 * every Rethink DNS URL (blocklist stamps and block-free) is built on that host instead.
 *
 * The RethinkDnsEndpoint table keeps storing the official (canonical) URLs, so the stamp and
 * the sky/max choice survive switching servers; the URL actually dialled is derived here at
 * transport time. All functions taking a [Config] are pure (unit tested in RethinkServersTest).
 */
object RethinkServers {

    /** Shown in the UI for the default servers. */
    const val DEFAULT_HOST_LABEL = Constants.RETHINKDNS_DOMAIN

    private const val HTTPS = "https://"
    private const val TLS = "tls://"
    private const val DNS_QUERY = "dns-query"

    /**
     * @param customBase normalized base ("https://host[:port]", no trailing slash), or "" for
     *   the official servers.
     * @param customDot dial the custom server over DoT (`<stamp>.<host>`, needs a wildcard
     *   certificate on the server). Ignored for the official servers, which always use DoH.
     * @param secret serverless-dns access secret ("msg key", alphanumeric) sent as the 4th
     *   stamp field (`1:<stamp>:<secret>` / `1-<stamp>-<secret>`) to a server with ACCESS_KEYS
     *   set. Ignored for the official servers. Never log it; see [redact].
     */
    data class Config(
        val customBase: String = "",
        val customDot: Boolean = false,
        val secret: String = "",
    ) {
        val isCustom: Boolean get() = customBase.isNotEmpty()
        val customHost: String get() = if (isCustom) hostOf(customBase) ?: "" else ""
        val useDot: Boolean get() = isCustom && customDot
        val activeSecret: String get() = if (isCustom && isValidSecret(secret)) secret else ""

        override fun toString(): String =
            "Config(customBase=$customBase, customDot=$customDot, secret=${if (secret.isEmpty()) "" else "***"})"
    }

    /** The URL to dial and its protocol. */
    data class Transport(val url: String, val dot: Boolean)

    fun config(ps: PersistentState): Config =
        Config(normalize(ps.customRethinkDnsUrl) ?: "", ps.customRethinkDnsDot, ps.customRethinkDnsSecret)

    // ---- normalization ----

    private val hostLabel = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

    /**
     * Normalizes user input to "https://host[:port]". Adds a missing scheme, drops any path,
     * query or fragment and lowercases the host. Returns "" for blank input (= default
     * servers) and null when the input is not a usable https host.
     */
    fun normalize(input: String?): String? {
        val raw = input?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val withScheme = if (raw.contains("://")) raw else HTTPS + raw
        val uri = try { URI(withScheme) } catch (_: Exception) { return null }
        if (!"https".equals(uri.scheme, ignoreCase = true)) return null
        if (uri.rawUserInfo != null) return null
        val host = uri.host?.lowercase()?.trimEnd('.')?.removeSurrounding("[", "]") ?: return null
        if (!isValidHost(host)) return null
        val port = uri.port
        if (port == 0 || port > 65535) return null
        val portPart = if (port > 0 && port != 443) ":$port" else ""
        val hostPart = if (host.contains(':')) "[$host]" else host
        return "$HTTPS$hostPart$portPart"
    }

    private fun isValidHost(host: String): Boolean {
        if (host.isEmpty() || host.length > 253) return false
        if (host.contains(':')) return true // ipv6 literal, already validated by URI
        val labels = host.split('.')
        if (labels.size < 2) return false
        return labels.all { hostLabel.matches(it) }
    }

    /** Host (no port) of a URL, or null. */
    fun hostOf(url: String): String? {
        return try {
            URI(if (url.contains("://")) url else HTTPS + url).host?.lowercase()?.removeSurrounding("[", "]")
        } catch (_: Exception) {
            null
        }
    }

    /** Label for the settings row: the custom host, or null when using the defaults. */
    fun customHostLabel(cfg: Config): String? = if (cfg.isCustom) cfg.customHost else null

    // ---- url building ----

    /**
     * Canonical (stored) URL for a stamp: the official sky/max base + stamp. This is what the
     * RethinkDnsEndpoint table holds regardless of the custom server.
     */
    fun storedUrl(isMax: Boolean, stamp: String): String =
        (if (isMax) Constants.RETHINK_BASE_URL_MAX else Constants.RETHINK_BASE_URL_SKY) + stamp

    // ---- access secret (serverless-dns ACCESS_KEYS, see src/plugins/users/auth-token.js) ----

    private const val AUTH_INFO = "sdns-public-auth-info"
    private const val SECRET_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    const val GENERATED_SECRET_LENGTH = 16

    /** serverless-dns accepts only alphanumeric secrets (util.isAlphaNumeric). */
    fun isValidSecret(s: String): Boolean =
        s.isNotEmpty() && s.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }

    /**
     * A random lowercase alphanumeric secret. Kept short (16) so a DoT label
     * `1-<stamp>-<secret>` stays within 63 chars for typical stamps.
     */
    fun generateSecret(rnd: SecureRandom = SecureRandom()): String =
        (1..GENERATED_SECRET_LENGTH).map { SECRET_ALPHABET[rnd.nextInt(SECRET_ALPHABET.length)] }.joinToString("")

    /**
     * The ACCESS_KEYS entry the server needs for [secret] on [host]:
     * `host|hex(hmac-sha256(key = "<secret>|<host>", msg = "sdns-public-auth-info"))`, both
     * lowercased, as serverless-dns's auth-token gen() computes it. The server tries every
     * suffix of the request host (util.domains), so the full host works for DoH and DoT.
     */
    fun accessKey(secret: String, host: String): String {
        val m = secret.lowercase()
        val d = host.lowercase()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("$m|$d".toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val hex = mac.doFinal(AUTH_INFO.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return "$d|$hex"
    }

    /** Hides the secret in strings that may be logged. */
    fun redact(cfg: Config, s: String): String {
        val k = cfg.activeSecret
        return if (k.isEmpty()) s else s.replace(k, "<secret>")
    }

    /** The stamp delimiter (":" base64, "-" base32), as serverless-dns's isB32Stamp decides. */
    private fun stampDelim(s: String): String {
        val i32 = s.indexOf('-')
        val i64 = s.indexOf(':')
        return if (i32 >= 0 && (i64 < 0 || i32 < i64)) "-" else ":"
    }

    private val stampPrefix = Regex("^[0-9]+[:-]")

    /** Drops a 4th (access secret) field: "1:abc:key" -> "1:abc"; "1::key" -> "". */
    fun stripSecret(stamp: String): String {
        if (!stampPrefix.containsMatchIn(stamp)) return stamp
        val d = stampDelim(stamp)
        val parts = stamp.split(d)
        if (parts.size < 3) return stamp
        return if (parts[1].isEmpty()) "" else parts[0] + d + parts[1]
    }

    /** Appends the access secret to a stamp for the custom server ("" = block-free). */
    private fun keyed(cfg: Config, stamp: String, b32: Boolean): String {
        val k = cfg.activeSecret
        if (k.isEmpty()) return stamp
        if (stamp.isEmpty()) {
            // empty blockstamp + secret: serverless-dns extractStamps("1::k") = [":", "1", "", "k"]
            val v = Constants.RETHINK_STAMP_VERSION
            return if (b32) "$v--$k" else "$v::$k"
        }
        return stamp + stampDelim(stamp) + k
    }

    /** DoH base (with trailing slash) that stamps are appended to. */
    fun dohBase(cfg: Config, isMax: Boolean): String =
        if (cfg.isCustom) "${cfg.customBase}/"
        else if (isMax) Constants.RETHINK_BASE_URL_MAX else Constants.RETHINK_BASE_URL_SKY

    /**
     * Block-free (no blocklist) DoH URL: `<base>/dns-query`, or `<base>/1::<secret>` when the
     * custom server needs an access secret.
     */
    fun blockFreeUrl(cfg: Config, isMax: Boolean): String =
        if (cfg.isCustom) {
            if (cfg.activeSecret.isEmpty()) "${cfg.customBase}/$DNS_QUERY"
            else "${cfg.customBase}/${keyed(cfg, "", b32 = false)}"
        } else if (isMax) Constants.BLOCK_FREE_DNS_MAX else Constants.BLOCK_FREE_DNS_SKY

    /** DoH URL for a stamp on the custom server: `<base>/<stamp>[:<secret>]`. */
    fun dohUrl(cfg: Config, stamp: String): String =
        if (stamp.isEmpty()) blockFreeUrl(cfg, false) else dohBase(cfg, false) + keyed(cfg, stamp, b32 = false)

    /**
     * DoT host for a stamp on the custom server: `tls://<stamp>[-<secret>].<host>`; block-free
     * is the bare host (`tls://1--<secret>.<host>` with a secret).
     */
    fun dotUrl(cfg: Config, stamp: String): String {
        val host = cfg.customHost
        val s = if (stamp == DNS_QUERY) "" else stamp
        val label = keyed(cfg, s, b32 = true)
        return if (label.isEmpty()) "$TLS$host" else "$TLS$label.$host"
    }

    /**
     * The URL a user would paste into another client (shown / copied in the UI). With
     * [withSecret] false the access secret is left out (for values that get persisted / logged).
     */
    fun displayUrl(cfg: Config, storedUrl: String, withSecret: Boolean = true): String {
        if (!cfg.isCustom || !isRethinkUrl(cfg, storedUrl)) return storedUrl
        val c = if (withSecret) cfg else cfg.copy(secret = "")
        val stamp = if (isBlockFree(cfg, storedUrl)) "" else stampOf(cfg, storedUrl)
        return dohUrl(c, stamp)
    }

    // ---- predicates & parsing ----

    /** True for URLs on the official Rethink hosts or on the custom server. */
    fun isRethinkUrl(cfg: Config, url: String): Boolean {
        if (url.contains(Constants.RETHINKDNS_DOMAIN)) return true
        val h = cfg.customHost
        return h.isNotEmpty() && url.lowercase().contains(h)
    }

    fun isBlockFree(cfg: Config, url: String): Boolean {
        return url == Constants.BLOCK_FREE_DNS_SKY ||
            url == Constants.BLOCK_FREE_DNS_MAX ||
            url == Constants.BLOCK_FREE_DNS_ZERO ||
            (cfg.isCustom && (url == blockFreeUrl(cfg, false) || url == "${cfg.customBase}/$DNS_QUERY"))
    }

    /**
     * The stamp path of a Rethink URL ("https://max.rethinkdns.com/1:abc" -> "1:abc"), "" when
     * there is none. For the official domain this matches the old split-on-domain behaviour.
     */
    fun stampOf(cfg: Config, url: String): String {
        val h = cfg.customHost
        val lower = url.lowercase()
        val rest = when {
            h.isNotEmpty() && lower.contains(h) -> {
                val i = lower.indexOf(h) + h.length
                var r = url.substring(i)
                if (r.startsWith("]")) r = r.substring(1)
                if (r.startsWith(":")) r = r.substring(1).dropWhile { it.isDigit() }
                r.substringBefore('?').substringBefore('#')
            }
            url.contains(Constants.RETHINKDNS_DOMAIN) -> url.substringAfter(Constants.RETHINKDNS_DOMAIN)
            else -> return ""
        }
        return stripSecret(if (rest.startsWith("/")) rest.substring(1) else rest)
    }

    /**
     * The URL to dial for a stored Rethink URL, or null if [url] is not a Rethink URL.
     * [toBase32] converts a stamp to its base32 form (falls back to the input).
     *
     * Default servers: exactly the previous behaviour (DoH on sky, or max when the stored URL
     * is on max; block-free URLs pass through). Custom server: same stamp on the custom host,
     * over DoH, or DoT when enabled.
     */
    fun transport(cfg: Config, url: String, toBase32: (String) -> String): Transport? {
        if (!isRethinkUrl(cfg, url)) return null
        val isMax = url.contains(Constants.MAX_ENDPOINT)
        if (!cfg.isCustom) {
            if (isBlockFree(cfg, url)) return Transport(url, dot = false)
            return Transport(dohBase(cfg, isMax) + toBase32(stampOf(cfg, url)), dot = false)
        }
        val stamp = if (isBlockFree(cfg, url)) "" else stampOf(cfg, url)
        if (stamp.isEmpty() || stamp == DNS_QUERY) {
            return if (cfg.useDot) Transport(dotUrl(cfg, ""), dot = true)
            else Transport(blockFreeUrl(cfg, isMax), dot = false)
        }
        val b32 = toBase32(stamp)
        return if (cfg.useDot) Transport(dotUrl(cfg, b32), dot = true)
        else Transport(dohUrl(cfg, b32), dot = false)
    }

    private val base64StampRegex = Pattern.compile("/1:([A-Za-z0-9+/=_-]+)(?:[.?#:]|$)")

    /**
     * Extracts a blocklist stamp from a pasted Rethink URL:
     * https://max.rethinkdns.com/1:IAAgAA== (base64 path) or 1-acaabaa.max.rethinkdns.com
     * (base32 subdomain); the custom host is accepted in place of rethinkdns.com.
     */
    fun extractStamp(cfg: Config, t: String): String? {
        val base64Match = base64StampRegex.matcher(t)
        if (base64Match.find()) {
            return "${Constants.RETHINK_STAMP_VERSION}:${base64Match.group(1)}"
        }
        val hosts = buildList {
            add(Constants.RETHINKDNS_DOMAIN)
            if (cfg.customHost.isNotEmpty()) add(cfg.customHost)
        }.joinToString("|") { Pattern.quote(it) }
        // an optional subdomain (max/sky) may sit between the stamp and the host
        val base32 = Pattern.compile("(?:^|//)1-([a-z2-7]+)(?:-[a-zA-Z0-9]+)?(?:\\.[^/]+)?\\.(?:$hosts)")
        val m = base32.matcher(t)
        if (m.find()) {
            return "${Constants.RETHINK_STAMP_VERSION}-${m.group(1)}"
        }
        return null
    }

    // ---- connectivity test ----

    /** RFC 8484 wire-format query for [name] (type A, RD set, id 0). */
    fun buildDnsQuery(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        name.trimEnd('.').split('.').forEach { label ->
            val b = label.toByteArray(Charsets.US_ASCII)
            out.write(b.size)
            out.write(b)
        }
        out.write(byteArrayOf(0, 0, 1, 0, 1))
        return out.toByteArray()
    }

    /** True when [resp] is a DNS response with rcode NOERROR and at least one answer. */
    fun isGoodDnsResponse(resp: ByteArray?): Boolean {
        if (resp == null || resp.size < 12) return false
        val isResponse = (resp[2].toInt() and 0x80) != 0
        val rcode = resp[3].toInt() and 0x0f
        val answers = ((resp[6].toInt() and 0xff) shl 8) or (resp[7].toInt() and 0xff)
        return isResponse && rcode == 0 && answers > 0
    }

    /**
     * Sends a DoH POST for example.com to the block-free URL (`<base>/dns-query`, or
     * `<base>/1::<secret>` with an access secret). Blocking: call off the main
     * thread. Returns null on success, else a short error message.
     */
    fun testServer(base: String, secret: String = "", timeoutMs: Int = 8000): String? {
        val url = blockFreeUrl(Config(base, secret = secret), false)
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/dns-message")
            conn.setRequestProperty("Accept", "application/dns-message")
            val q = buildDnsQuery("example.com")
            conn.outputStream.use { it.write(q) }
            val code = conn.responseCode
            if (code != 200) return "HTTP $code"
            val body = conn.inputStream.use { it.readBytes() }
            if (isGoodDnsResponse(body)) null else "bad DNS response"
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        } finally {
            conn?.disconnect()
        }
    }
}
