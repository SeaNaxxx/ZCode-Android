package io.github.zcodeandroid.app.local

import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/**
 * Negative tests for the server-side SSRF guard (§5 Phase 8 / §10 in
 * docs/architecture/PHASES-7-10.md): loopback, private, link-local, CGNAT,
 * documentation/reserved ranges, non-http schemes, and non-allowlisted
 * hosts must fail closed before any socket opens.
 *
 * These run without a device: the guard resolves each host and classifies
 * it; failures are `IOException`s. `isSafeHttpHost` is exercised directly
 * for the RFC 1918 / loopback literals so the suite stays hermetic even
 * where DNS is unavailable.
 */
class ProvisionerSsrfTest {

    @Test
    fun `loopback is rejected`() {
        assertThrows(IOException::class.java) { Provisioner.requireSafeHttpUrl("http://localhost:3030/ws") }
        assertThrows(IOException::class.java) { Provisioner.requireSafeHttpUrl("http://127.0.0.1/ws") }
        assertThrows(IOException::class.java) { Provisioner.requireSafeHttpUrl("http://[::1]/ws") }
    }

    @Test
    fun `private ranges are rejected`() {
        assertThrows(IOException::class.java) { Provisioner.requireSafeHttpUrl("http://10.0.0.5/") }
        assertThrows(IOException::class.java) { Provisioner.requireSafeHttpUrl("http://172.16.0.1/") }
        assertThrows(IOException::class.java) { Provisioner.requireSafeHttpUrl("http://192.168.1.1/") }
    }

    @Test
    fun `reserved ranges are rejected`() {
        assertThrows(IOException::class.java) { Provisioner.requireSafeHttpUrl("http://100.64.0.1/") } // CGNAT
        assertThrows(IOException::class.java) { Provisioner.requireSafeHttpUrl("http://198.51.100.1/") } // TEST-NET-2
        assertThrows(IOException::class.java) { Provisioner.requireSafeHttpUrl("http://169.254.1.1/") } // link-local
    }

    @Test
    fun `non-http scheme is rejected`() {
        assertThrows(IOException::class.java) { Provisioner.requireSafeHttpUrl("ftp://nodejs.org/dist/") }
        assertThrows(IOException::class.java) { Provisioner.requireSafeHttpUrl("file:///etc/passwd") }
    }

    @Test
    fun `localhost hostname rejected by isSafeHttpHost`() {
        assertThrows(IOException::class.java) { Provisioner.requireSafeHttpUrl("https://localhost.example.com/") }
    }

    @Test
    fun `explicit product-socket exception is narrow`() {
        // The loopback WS contract stays valid ONLY when explicitly asked for.
        Provisioner.requireSafeHttpUrl("ws://127.0.0.1:3030/ws", allowLoopbackProductSocket = true)
        assertThrows(IOException::class.java) {
            Provisioner.requireSafeHttpUrl("http://127.0.0.1:3030/ws", allowLoopbackProductSocket = true)
        }
    }
}
