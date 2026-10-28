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
    fun `persistence marker file name is workspace-relative dotfile`() {
        assertEquals(".e2-persistence.json", LocalServerProbe.PERSISTENCE_FILE)
    }
}
