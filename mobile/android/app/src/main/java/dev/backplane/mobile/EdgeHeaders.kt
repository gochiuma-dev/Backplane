package dev.backplane.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Fork (cf-access, docs/FORK-CF-ACCESS.md): headers an edge proxy in front of a hub asks for, such as a Cloudflare Access service token
// (CF-Access-Client-Id, CF-Access-Client-Secret). Kept per hub address (host:port of an https link), sealed with an Android Keystore key,
// and added only to an https request for that same host and port: a network interceptor, so it is decided again for every redirect,
// and a picture from another site in a message (DataMarkdown) never carries them.
object EdgeHeaders {
    data class H(val name: String, val value: String)

    const val CF_ID = "CF-Access-Client-Id"
    const val CF_SECRET = "CF-Access-Client-Secret"

    private const val ALIAS = "backplane.edge"
    private var ctx: Context? = null
    @Volatile private var byHub: Map<String, List<H>> = emptyMap()
    /** bumped on every change, so a screen that lists the names redraws */
    var version by mutableIntStateOf(0); private set
    /** typed on the pairing screen: given to the hub being paired (DataClient.pair), then dropped */
    @Volatile var pending: List<H> = emptyList()

    fun init(c: Context) {
        if (ctx != null) return
        ctx = c.applicationContext
        byHub = runCatching { decode(open(prefs().getString("sealed", null))) }.getOrElse { emptyMap() }
    }
    private fun prefs() = ctx!!.getSharedPreferences("edge", Context.MODE_PRIVATE)

    /** host:port for an https address (a link, an origin, a ws(s) socket address); null for plain http, where nothing is ever sent. */
    fun hubOf(link: String): String? {
        val s = link.trim().let { if (it.startsWith("wss://")) "https://" + it.drop(6) else it }
        val o = HubList.origin(s) ?: Pairing.http(s, "/", token = false)?.let { HubList.origin(it) } ?: return null
        return o.toHttpUrlOrNull()?.let { hubOf(it) }
    }
    fun hubOf(url: HttpUrl): String? = if (url.isHttps) url.host.lowercase() + ":" + url.port else null

    fun names(hub: String?): List<String> = hub?.let { byHub[it] }.orEmpty().map { it.name }
    fun forUrl(url: HttpUrl): List<H> = hubOf(url)?.let { byHub[it] }.orEmpty()

    /** A header name as RFC 9110 has it, and a value without control characters. */
    fun valid(h: H) = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+").matches(h.name) && h.value.isNotEmpty() && h.value.none { it < ' ' || it == '\u007f' }

    /** Replace a hub's headers. A row with an empty value keeps the value already saved under that name. False when a row is not valid. */
    fun put(hub: String, rows: List<H>): Boolean {
        val old = byHub[hub].orEmpty().associate { it.name.lowercase() to it.value }
        val next = rows.filter { it.name.isNotBlank() }.map { H(it.name.trim(), it.value.ifEmpty { old[it.name.trim().lowercase()].orEmpty() }) }
        if (next.any { !valid(it) }) return false
        store(if (next.isEmpty()) byHub - hub else byHub + (hub to next.distinctBy { it.name.lowercase() }))
        return true
    }
    fun forget(link: String) { hubOf(link)?.let { if (it in byHub) store(byHub - it) } }
    fun clear() { pending = emptyList(); if (byHub.isNotEmpty()) store(emptyMap()) }
    /** The pairing screen's headers go to the hub being paired (only an https one); none typed leaves what that hub already has.
     *  False when a row is not valid (they stay pending). */
    fun commitPending(link: String): Boolean {
        val p = pending
        val hub = hubOf(link) ?: return true
        if (p.none { it.name.isNotBlank() }) return true
        if (!put(hub, p)) return false
        pending = emptyList(); return true
    }

    /** Every OkHttpClient that talks to a hub: `.apply(EdgeHeaders::wire)`. */
    fun wire(b: OkHttpClient.Builder) { b.addInterceptor(socketInterceptor).addNetworkInterceptor(interceptor) }
    private fun with(r: Request): Request { val hs = forUrl(r.url); return if (hs.isEmpty()) r else r.newBuilder().apply { hs.forEach { header(it.name, it.value) } }.build() }
    // a network interceptor: decided again for each request a redirect makes
    private val interceptor = Interceptor { chain -> chain.proceed(with(chain.request())) }
    // OkHttp runs no network interceptor for a WebSocket's upgrade, so it is done here, before any redirect:
    // a client that opens sockets must not follow redirects (DataClient.wsHttp, Hub)
    private val socketInterceptor = Interceptor { chain ->
        val r = chain.request()
        chain.proceed(if (r.header("Upgrade").equals("websocket", ignoreCase = true)) with(r) else r)
    }
    /** The same rule for an HttpURLConnection (VoiceRecorder): it must not follow a redirect elsewhere with them. */
    fun apply(c: HttpURLConnection) {
        val hs = c.url.toString().toHttpUrlOrNull()?.let { forUrl(it) }.orEmpty()
        if (hs.isEmpty()) return
        c.instanceFollowRedirects = false
        hs.forEach { c.setRequestProperty(it.name, it.value) }
    }

    // ---- storage: one JSON object {hub: [[name, value], …]}, AES-GCM under a Keystore key that never leaves the device
    private fun store(next: Map<String, List<H>>) {
        byHub = next; version++
        // no context: a JVM test (EdgeHeadersTest), which keeps them in memory only
        if (ctx != null) prefs().edit().putString("sealed", seal(encode(next))).apply()
    }
    private fun encode(m: Map<String, List<H>>) = JSONObject().apply { m.forEach { (k, v) -> put(k, JSONArray(v.map { JSONArray(listOf(it.name, it.value)) })) } }.toString()
    private fun decode(s: String?): Map<String, List<H>> {
        val o = JSONObject(s ?: return emptyMap())
        return o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> H(p.getString(0), p.getString(1)) } } } }
    }
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    private fun seal(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val b64 = { b: ByteArray -> Base64.encodeToString(b, Base64.NO_WRAP) }
        return b64(c.iv) + ":" + b64(c.doFinal(plain.toByteArray()))
    }
    private fun open(sealed: String?): String? {
        val (iv, ct) = (sealed ?: return null).split(':').takeIf { it.size == 2 }?.map { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        return String(c.doFinal(ct))
    }
}
