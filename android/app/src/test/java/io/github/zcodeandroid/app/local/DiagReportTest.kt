package io.github.zcodeandroid.app.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guards for the P1a diagnostics bridge (self-diagnostics study,
 * 2026-11): the persisted diag report must be valid JSON, must never leak
 * secret-shaped strings (external review note 4), the shared rotated file
 * sink must keep the webconsole behavior, and the process stats must report
 * both RSS and PSS semantics (review note 2).
 */
class DiagReportTest {

    @Test
    fun `redaction removes github PATs and zai key shapes`() {
        val s = DiagReport.redact("token github_pat_11B5NZWTI0abcdefghij_KLMNOPQRSTUVWXYZ1234567890 end")
        assertFalse(s.contains("github_pat_11B5"))
        assertTrue(s.contains("[REDACTED]"))
        val key = DiagReport.redact("key 8cc9a492a27c42a7ac11ef0d3af50a70.sa9q2WZvKhb8KeSV")
        assertFalse(key.contains("sa9q2WZvKhb8KeSV"))
        assertFalse(key.contains("8cc9a492"))
        assertTrue(key.contains("[REDACTED]"))
    }

    @Test
    fun `redaction removes bearer and credential assignments but keeps plain lines`() {
        val s = DiagReport.redact("Authorization: Bearer sk-abcdef1234567890")
        assertFalse(s.contains("sk-abcdef1234567890"))
        assertFalse(s.contains("Bearer abcdef"))
        val kv = DiagReport.redact("api_key = \"supersecret123\"")
        assertFalse(kv.contains("supersecret123"))
        assertTrue(kv.contains("api_key = "))
        val plain = DiagReport.redact("shim applied: rulesFixed=63 htmlPx=941x436")
        assertEquals("shim applied: rulesFixed=63 htmlPx=941x436", plain)
    }

    @Test
    fun `build produces parseable JSON with required fields`() {
        val json = DiagReport.build(
            state = "Running(url=http://127.0.0.1:3030)",
            webViewProvider = "com.google.android.webview 119.0.0.0",
            runtimeLogTail = listOf("[00:00:01] state -> Running", "agent| server listening"),
            webTail = listOf("[2026-11-01 20:00:00] [js] [zcode-shim] applied: rulesFixed=63"),
            processes = listOf("pid=1071 comm=node rssKb=210000 pssKb=90000"),
            memAvailableKb = "1800000",
            workspaceFreeBytes = 53_000_000_000L,
        )
        // org.json is stubbed (throws) on the CI JVM, so the JSON shape is
        // asserted structurally by hand here; CI on-device stages parse it.
        assertTrue(json.startsWith("{"))
        assertTrue(json.trimEnd().endsWith("}"))
        assertTrue(json.contains("\"state\": \"Running(url=http://127.0.0.1:3030)\""))
        assertTrue(json.contains("\"memAvailableKb\": \"1800000\""))
        assertTrue(json.contains("\"workspaceFreeBytes\": 53000000000"))
        assertTrue(json.contains("pid=1071 comm=node rssKb=210000 pssKb=90000"))
        assertTrue(json.contains("rulesFixed=63"))
        // quotes inside values must be escaped, not break the document
        val tricky = DiagReport.build(
            state = "Failed(stage, message with \"quotes\" and \\ backslash)",
            webViewProvider = "",
            runtimeLogTail = emptyList(),
            webTail = emptyList(),
            processes = emptyList(),
            memAvailableKb = "?",
            workspaceFreeBytes = 0L,
        )
        assertTrue(tricky.contains("\\\"quotes\\\""))
        assertTrue(tricky.contains("\\\\"))
    }

    @Test
    fun `node process stats report rss and pss semantics`() {
        val stats = DiagReport.nodeProcessStats()
        // On the CI JVM there are no node processes: must be EMPTY, not an error.
        // On a runtime with node processes every line carries BOTH counters.
        for (line in stats) {
            assertTrue(line, line.contains("rssKb="))
            assertTrue(line, line.contains("pssKb="))
        }
    }

    @Test
    fun `shared rotated sink keeps one previous generation and bounded size`() {
        val dir = java.nio.file.Files.createTempDirectory("diagfile-test").toFile()
        try {
            val sink = DiagFileSink(java.io.File(dir, "server.log"), 4096L)
            repeat(400) { sink.append("agent| pad-" + "x".repeat(60)) }
            assertTrue(java.io.File(dir, "server.log.old").isFile)
            assertTrue(java.io.File(dir, "server.log").isFile)
            assertTrue(java.io.File(dir, "server.log").length() <= 4096 + 200)
            val tail = sink.tail(10)
            assertEquals(10, tail.size)
            assertTrue(tail.last().startsWith("agent| pad-"))
            assertTrue(sink.sizeBytes() > 0)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `shared sink tail is empty when file absent`() {
        val dir = java.nio.file.Files.createTempDirectory("diagfile-test2").toFile()
        try {
            val sink = DiagFileSink(java.io.File(dir, "missing.log"), 1024L)
            assertTrue(sink.tail(5).isEmpty())
            assertEquals(0L, sink.sizeBytes())
        } finally {
            dir.deleteRecursively()
        }
    }
}
