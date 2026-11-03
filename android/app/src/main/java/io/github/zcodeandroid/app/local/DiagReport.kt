package io.github.zcodeandroid.app.local

import java.io.File

/**
 * Agent-facing diagnostics bridge (P1a of the self-diagnostics study, 2026-11):
 * periodically writes `files/logs/diag-report.json` so the embedded agent —
 * same uid, same files — can see the APP side of the runtime without any new
 * IPC. Before this file existed, everything the Kotlin layer knew (runtime
 * state, WebView provider, render evidence, process stats) was invisible to
 * the agent, while the agent's own tooling was fully visible to the app.
 *
 * Security (external review note 4): the report is written for the agent to
 * READ, and its inputs (runtime log lines, web console lines) may contain
 * untrusted page text. Every string passes [redact] before it is written, and
 * only bounded diagnostic tails are included — never raw page content, never
 * credentials. The report is plain JSON built by hand: org.json is not
 * available on the CI JVM unit tests.
 */
object DiagReport {

    private const val MAX_LINE = 300

    fun reportFile(filesDir: File): File = File(File(filesDir, "logs"), "diag-report.json")

    /**
     * Redact secret-shaped substrings. Covers: GitHub PATs (fine-grained and
     * classic), OpenAI-style sk- keys, Bearer/Authorization values, the
     * z.ai API-key shape (32 hex . secret), and key=value / key:value pairs
     * whose key names a credential. Order matters: PAT/key shapes first, then
     * the generic credential-named assignments.
     */
    fun redact(s: String): String {
        var out = s
        out = out.replace(Regex("github_pat_[A-Za-z0-9_]+"), "[REDACTED]")
        out = out.replace(Regex("gh[posr]_[A-Za-z0-9]{16,}"), "[REDACTED]")
        out = out.replace(Regex("sk-[A-Za-z0-9_-]{8,}"), "[REDACTED]")
        out = out.replace(Regex("\\b[0-9a-f]{32}\\.[A-Za-z0-9_-]{8,}\\b"), "[REDACTED]")
        out = out.replace(
            Regex("(?i)((api[_-]?key|token|authorization|secret|password)[\"']?\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;&\"]+)"),
            "$1[REDACTED]"
        )
        return out
    }

    private fun clip(s: String): String {
        val oneLine = s.replace("\n", " ").replace("\r", " ")
        return if (oneLine.length <= MAX_LINE) oneLine else oneLine.take(MAX_LINE) + "…"
    }

    private fun jsonStr(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun jsonArray(lines: List<String>): String =
        lines.joinToString(",", "[", "]") { "\"${jsonStr(clip(it))}\"" }

    /**
     * Same-uid node/zcode process stats. RSS and PSS are BOTH reported (review
     * note 2: RSS double-counts shared pages across the node process family;
     * PSS from smaps_rollup is the honest per-process figure when readable).
     * Values are kB as reported by the kernel.
     */
    fun nodeProcessStats(): List<String> {
        val out = ArrayList<String>()
        val procDir = File("/proc")
        val dirs = procDir.listFiles() ?: return out
        for (d in dirs) {
            val pid = d.name.toIntOrNull() ?: continue
            val comm = runCatching { File(d, "comm").readText().trim() }.getOrDefault("")
            if (comm != "node" && comm != "zcode-cli") continue
            val status = runCatching { File(d, "status").readText() }.getOrDefault("")
            val rss = Regex("VmRSS:\\s+(\\d+)\\s+kB").find(status)?.groupValues?.get(1) ?: "?"
            val pss = runCatching {
                val rollup = File(d, "smaps_rollup").readText()
                Regex("Pss:\\s+(\\d+)\\s+kB").find(rollup)?.groupValues?.get(1) ?: "?"
            }.getOrDefault("?")
            out.add("pid=$pid comm=$comm rssKb=$rss pssKb=$pss")
        }
        return out
    }

    private fun memAvailableKb(): String = runCatching {
        val m = Regex("MemAvailable:\\s+(\\d+)\\s+kB").find(File("/proc/meminfo").readText())
        m?.groupValues?.get(1) ?: "?"
    }.getOrDefault("?")

    /**
     * Build the report JSON (pure function of its inputs; JVM-testable).
     * webViewProvider, runtimeLog/webTails and battery may be empty on the
     * CI JVM.
     */
    fun build(
        state: String,
        webViewProvider: String,
        runtimeLogTail: List<String>,
        webTail: List<String>,
        processes: List<String>,
        memAvailableKb: String,
        workspaceFreeBytes: Long,
        battery: List<String> = emptyList(),
    ): String {
        val sb = StringBuilder(4096)
        sb.append("{\n")
        sb.append("  \"ts\": \"").append(jsonStr(ts())).append("\",\n")
        sb.append("  \"state\": \"").append(jsonStr(clip(state))).append("\",\n")
        sb.append("  \"webviewProvider\": \"").append(jsonStr(clip(webViewProvider))).append("\",\n")
        sb.append("  \"memAvailableKb\": \"").append(jsonStr(memAvailableKb)).append("\",\n")
        sb.append("  \"workspaceFreeBytes\": ").append(workspaceFreeBytes.coerceAtLeast(0)).append(",\n")
        sb.append("  \"processes\": ").append(jsonArray(processes)).append(",\n")
        sb.append("  \"battery\": ").append(jsonArray(battery)).append(",\n")
        sb.append("  \"runtimeLogTail\": ").append(jsonArray(runtimeLogTail)).append(",\n")
        sb.append("  \"webConsoleTail\": ").append(jsonArray(webTail)).append("\n")
        sb.append("}\n")
        return sb.toString()
    }

    /**
     * Write the report for the current runtime. Never throws; on any failure
     * the previous report stays on disk (its `ts` shows its age).
     */
    fun write(filesDir: File, webViewProvider: String, battery: List<String> = emptyList()) {
        try {
            val ws = File(filesDir, "workspace")
            val json = build(
                state = RuntimeBus.state.value.toString(),
                webViewProvider = webViewProvider,
                runtimeLogTail = RuntimeBus.log.value.takeLast(60).map { redact(it) },
                webTail = WebDiagnostics.fileSinkTail(60).map { redact(it) },
                processes = nodeProcessStats(),
                memAvailableKb = memAvailableKb(),
                workspaceFreeBytes = runCatching { ws.usableSpace }.getOrDefault(0L),
                battery = battery,
            )
            val tmp = File(reportFile(filesDir).parentFile, "diag-report.json.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(reportFile(filesDir))) {
                reportFile(filesDir).writeText(json)
                tmp.delete()
            }
        } catch (_: Throwable) {
        }
    }

    private fun ts(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
}
