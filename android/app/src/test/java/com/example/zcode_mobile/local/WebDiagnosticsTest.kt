package com.example.zcode_mobile.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression guards for the device failure 3 evidence instrumentation
 * (black screen while the server runs, Vivo V2035, 2026-10-28):
 *
 *  - the UI-side evidence ring buffer must keep its own capacity so a chatty
 *    frontend boot cannot evict webview/js evidence from the export;
 *  - the outbound connectivity probe must stay credential-free, HEAD-only,
 *    time-bounded, and run under the embedded node (never the host JVM).
 */
class WebDiagnosticsTest {

    @Before
    fun reset() {
        DiagLog.clear()
    }

    @Test
    fun `ring buffer keeps its own capacity`() {
        repeat(600) { DiagLog.append("line-$it") }
        val snap = DiagLog.snapshot()
        assertEquals(500, snap.size)
        assertFalse(snap.first().contains("line-99"))
        assertTrue(snap.first().contains("line-100"))
        assertTrue(snap.last().contains("line-599"))
    }

    @Test
    fun `snapshot is ordered oldest to newest`() {
        DiagLog.append("first")
        DiagLog.append("second")
        val snap = DiagLog.snapshot()
        assertTrue(snap.indexOf(snap.first { it.contains("first") }) <
            snap.indexOf(snap.first { it.contains("second") }))
    }

    @Test
    fun `net probe script is credential-free and time-bounded`() {
        val s = LocalServerProbe.NET_PROBE_SCRIPT
        // No credentials may ever be attached by the probe itself.
        assertFalse(s.contains("Authorization"))
        assertFalse(s.contains("Bearer"))
        assertFalse(s.contains("apikey"))
        assertFalse(s.contains("apiKey"))
        assertFalse(s.contains("cookie"))
        // HEAD only, bounded timeout, per-target RESULT lines for evidence.
        assertTrue(s.contains("method: \"HEAD\""))
        assertTrue(s.contains("AbortSignal.timeout(8000)"))
        assertTrue(s.contains("RESULT"))
        // argv targets — endpoints are decided by the app, not baked into secrets.
        assertTrue(s.contains("process.argv.slice(2)"))
    }

    @Test
    fun `tls chain probe keeps verification on and stays credential-free`() {
        val s = LocalServerProbe.TLS_PROBE_SCRIPT
        // Verification must NEVER be disabled for the verdict connection.
        assertTrue(s.contains("rejectUnauthorized: true"))
        assertFalse(s.contains("rejectUnauthorized: false"))
        assertFalse(s.contains("NODE_TLS_REJECT"))
        // Metadata only, no credentials, bounded, argv targets.
        assertFalse(s.contains("Authorization"))
        assertFalse(s.contains("Bearer"))
        assertFalse(s.contains("apikey"))
        assertFalse(s.contains("cookie"))
        assertTrue(s.contains("timeout: 8000"))
        assertTrue(s.contains("TLS-CHAIN"))
        assertTrue(s.contains("process.argv.slice(2)"))
    }

    @Test
    fun `persistence marker file name is workspace-relative dotfile`() {
        assertEquals(".e2-persistence.json", LocalServerProbe.PERSISTENCE_FILE)
    }

    @Test
    fun `uniform black bitmap reports renderer produced nothing`() {
        // A captured bitmap that is 100% near-black is the "renderer produced
        // no pixels" verdict for the paint-evidence chain (failure-3 suite).
        val pixels = IntArray(400) { 0xFF060816.toInt() } // page dark bg #060816: r=6,g=8,b=22 -> NOT near-black
        val s = WebDiagnostics.analyzePixels(pixels)
        // b=22 is above the near-black threshold: dark THEME backgrounds must
        // not be misclassified as "black screen".
        assertFalse(s.contains("uniformBlack=100%"))
        assertTrue(s.contains("distinctColors=1"))
        assertTrue(s.contains("dominant=#060816"))
        val black = IntArray(400) { 0xFF000000.toInt() }
        val sb = WebDiagnostics.analyzePixels(black)
        assertTrue(sb.contains("uniformBlack=100%"))
        assertTrue(sb.contains("distinctColors=1"))
        assertTrue(sb.contains("dominant=#000000"))
    }

    @Test
    fun `painted content produces distinct color buckets`() {
        // Half white, half accent red: content pixels => distinctColors > 1
        // and the dominant share well below 100%.
        val pixels = IntArray(400) { if (it < 200) 0xFFFFFFFF.toInt() else 0xFFE11D48.toInt() }
        val s = WebDiagnostics.analyzePixels(pixels)
        assertTrue(s.contains("distinctColors=2"))
        assertFalse(s.contains("uniformBlack=100%"))
        assertTrue(s.contains("dominantShare=50%"))
    }

    @Test
    fun `empty sample set is reported without crashing`() {
        assertEquals("no-samples", WebDiagnostics.analyzePixels(IntArray(0)))
    }
}
