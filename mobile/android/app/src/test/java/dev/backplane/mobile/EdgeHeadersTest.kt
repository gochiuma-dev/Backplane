package dev.backplane.mobile

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Fork (cf-access): the headers reach the paired https hub and nothing else, through a real OkHttp and TLS.
class EdgeHeadersTest {
    private val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
    private val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
    private val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
    private val hub = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false) }
    private val other = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false) }
    private val plain = MockWebServer()
    private val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
        .apply(EdgeHeaders::wire).build()

    @Before fun up() {
        hub.start(); other.start(); plain.start()
        EdgeHeaders.clear()
        assertTrue(EdgeHeaders.put(EdgeHeaders.hubOf("https://localhost:${hub.port}/#token=abc")!!,
            listOf(EdgeHeaders.H(EdgeHeaders.CF_ID, "id.access"), EdgeHeaders.H(EdgeHeaders.CF_SECRET, "s3cret"))))
    }
    @After fun down() { hub.shutdown(); other.shutdown(); plain.shutdown(); EdgeHeaders.clear() }

    private fun get(url: String) = client.newCall(Request.Builder().url(url).build()).execute().close()

    @Test fun theHubGetsThem() {
        hub.enqueue(MockResponse())
        get("https://localhost:${hub.port}/auth/enroll")
        val r = hub.takeRequest()
        assertEquals("id.access", r.getHeader("CF-Access-Client-Id"))
        assertEquals("s3cret", r.getHeader("CF-Access-Client-Secret"))
    }

    @Test fun anotherPortOrHostGetsNone() {
        other.enqueue(MockResponse()); hub.enqueue(MockResponse())
        get("https://localhost:${other.port}/pic.png")
        assertNull(other.takeRequest().getHeader("CF-Access-Client-Id"))
        get("https://127.0.0.1:${hub.port}/pic.png")
        assertNull(hub.takeRequest().getHeader("CF-Access-Client-Secret"))
    }

    @Test fun aRedirectElsewhereGetsNone() {
        hub.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://localhost:${other.port}/away"))
        other.enqueue(MockResponse())
        get("https://localhost:${hub.port}/files")
        assertEquals("s3cret", hub.takeRequest().getHeader("CF-Access-Client-Secret"))
        val away = other.takeRequest()
        assertEquals("/away", away.path)
        assertNull(away.getHeader("CF-Access-Client-Secret"))
        assertNull(away.getHeader("CF-Access-Client-Id"))
    }

    @Test fun plainHttpGetsNone() {
        assertNull(EdgeHeaders.hubOf("http://localhost:${plain.port}/#token=abc"))
        assertFalse(EdgeHeaders.put("localhost:${plain.port}", listOf(EdgeHeaders.H("Bad Name", "x"))))
        assertTrue(EdgeHeaders.put("localhost:${plain.port}", listOf(EdgeHeaders.H(EdgeHeaders.CF_ID, "id.access"))))
        plain.enqueue(MockResponse())
        get("http://localhost:${plain.port}/healthz")
        assertNull(plain.takeRequest().getHeader("CF-Access-Client-Id"))
    }

    @Test fun theSocketUpgradeGetsThem() {
        hub.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        val open = CountDownLatch(1)
        val ws = client.newWebSocket(Request.Builder().url("wss://localhost:${hub.port}/ws?token=abc").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { open.countDown() }
        })
        assertTrue(open.await(5, TimeUnit.SECONDS))
        assertEquals("s3cret", hub.takeRequest().getHeader("CF-Access-Client-Secret"))
        ws.cancel()
    }

    // DataClient.wsHttp and Hub: a socket's client follows no redirect, so an upgrade bounced elsewhere goes no further
    @Test fun aSocketRedirectIsNotFollowed() {
        hub.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://localhost:${other.port}/ws"))
        val failed = CountDownLatch(1)
        client.newBuilder().followRedirects(false).followSslRedirects(false).build()
            .newWebSocket(Request.Builder().url("wss://localhost:${hub.port}/ws").build(), object : WebSocketListener() {
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { failed.countDown() }
            })
        assertTrue(failed.await(5, TimeUnit.SECONDS))
        assertEquals("s3cret", hub.takeRequest().getHeader("CF-Access-Client-Secret"))
        assertEquals(0, other.requestCount)
    }

    @Test fun aBlankValueKeepsTheSavedOne() {
        val k = EdgeHeaders.hubOf("wss://localhost:${hub.port}/ws")!!
        assertTrue(EdgeHeaders.put(k, listOf(EdgeHeaders.H(EdgeHeaders.CF_ID, ""), EdgeHeaders.H(EdgeHeaders.CF_SECRET, "new"))))
        hub.enqueue(MockResponse())
        get("https://localhost:${hub.port}/hub/status")
        val r = hub.takeRequest()
        assertEquals("id.access", r.getHeader("CF-Access-Client-Id"))
        assertEquals("new", r.getHeader("CF-Access-Client-Secret"))
    }
}
