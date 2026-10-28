package com.example.zcode_mobile.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Evidence probes for the E2 device protocol — run once after the server
 * reaches Running. They separate three evidence classes the owner requires:
 *
 *   [probe] local      — what http://127.0.0.1:3030 actually returns to a
 *                        client inside the app (status, content type, size,
 *                        HTML head). Proves the local server serves the real
 *                        frontend entry (not an empty 200), and that the JS
 *                        bundle referenced by index.html is itself served.
 *   [probe] external   — outbound HTTPS from the EMBEDDED node runtime (not
 *                        the JVM, not the host), with certificate verification
 *                        on, NO credentials sent. Separate evidence class:
 *                        local-server health says nothing about provider
 *                        reachability (device failure 3 log shows undici
 *                        "fetch failed" for one remote config endpoint).
 *   [probe] persistence— a marker file in the app-private workspace whose
 *                        firstWrittenAt stays constant across app restarts.
 *                        Proof of durable local file operations.
 *
 * All probes are read-only with respect to credentials; nothing here exports
 * provider config, tokens, or request headers.
 */
object LocalServerProbe {

    /**
     * Runs under the embedded node with the EXACT BionicEnv the web server
     * uses (same env = same evidence class). HEAD-only, short timeout, per-endpoint
     * error classification (DNS vs TLS vs HTTP). Endpoints are passed as argv:
     *  - a provider-adjacent host (any HTTP response proves TLS+connectivity;
     *    status is irrelevant),
     *  - a region-neutral control host.
     */
    const val NET_PROBE_SCRIPT = """// E2 outbound connectivity probe (no credentials, HEAD only).
// NOTE: plain string concatenation only — no JS template literals, because
// Kotlin raw strings interpolate the dollar-brace syntax as Kotlin templates.
const targets = process.argv.slice(2);
(async () => {
  for (const u of targets) {
    const t0 = Date.now();
    try {
      const res = await fetch(u, { method: "HEAD", redirect: "manual", signal: AbortSignal.timeout(8000) });
      const ms = Date.now() - t0;
      console.log("RESULT " + u + " -> HTTP " + res.status + " in " + ms + "ms (tls=" + (u.indexOf("https:") === 0) + ")");
    } catch (e) {
      const ms = Date.now() - t0;
      const cause = e && e.cause ? (e.cause.code || e.cause.message) : "";
      console.log(("RESULT " + u + " -> FAILED after " + ms + "ms " + (e && e.name ? e.name : "Error") + " " + cause).trimEnd());
    }
  }
})();"""

    const val PERSISTENCE_FILE = ".e2-persistence.json"

    suspend fun runAll(app: Context): Unit = withContext(Dispatchers.IO) {
        val a = app.applicationContext
        runCatching { probeLocal(a) }.onFailure {
            WebDiagnostics.log("probe", "local probe errored: ${it.message}")
        }
        runCatching { probeExternal(a) }.onFailure {
            WebDiagnostics.log("probe", "external probe errored: ${it.message}")
        }
        runCatching { probePersistence(a) }.onFailure {
            WebDiagnostics.log("probe", "persistence probe errored: ${it.message}")
        }
    }

    /** GET / on the loopback server + the JS entry referenced by index.html. */
    private fun probeLocal(app: Context) {
        val url = RuntimePaths.SERVER_URL
        val (status, type, bytes, head) = httpGet(url)
        WebDiagnostics.log(
            "probe",
            "local GET $url -> HTTP $status, content-type=$type, ${bytes}B"
        )
        if (head.isNotEmpty()) {
            WebDiagnostics.log("probe", "local head: ${head.take(220)}")
        }
        // The single most black-screen-relevant asset: the JS bundle named by
        // index.html. A 404/HTML-here would leave the boot shell on screen.
        val src = Regex("""<script[^>]+src="([^"]+)"""").find(head)?.groupValues?.get(1)
        if (src != null) {
            val abs = when {
                src.startsWith("http") -> src
                src.startsWith("/") -> "$url$src".removeSuffix("//")
                else -> "$url/$src"
            }
            val (s2, t2, b2, _) = httpGet(abs)
            WebDiagnostics.log("probe", "local GET js-entry $abs -> HTTP $s2, content-type=$t2, ${b2}B")
        } else {
            WebDiagnostics.log("probe", "local: index.html head contains no <script src=...> (unexpected for the official web UI)")
        }
    }

    /** Outbound HTTPS from the embedded node — separate from JVM evidence. */
    private fun probeExternal(app: Context) {
        val rt = RuntimePaths.runtimeDir(app)
        val script = File(rt, "probe-net.cjs")
        script.writeText(NET_PROBE_SCRIPT)
        val targets = listOf("https://api.z.ai", "https://www.baidu.com")
        val pb = ProcessBuilder(
            listOf(
                RuntimePaths.nodeBin(app).absolutePath,
                script.absolutePath
            ) + targets
        )
        pb.redirectErrorStream(true)
        Provisioner.applyBionicEnv(pb.environment(), app)
        val proc = pb.start()
        val out = StringBuilder()
        Thread {
            try {
                proc.inputStream.bufferedReader().use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        out.appendLine(line)
                        if (line.startsWith("RESULT")) WebDiagnostics.log("probe", "external $line")
                    }
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true }.start()
        val done = proc.waitFor(25, java.util.concurrent.TimeUnit.SECONDS)
        if (!done) {
            proc.destroyForcibly()
            WebDiagnostics.log("probe", "external probe timed out after 25s (partial results above)")
        }
        val code = runCatching { proc.exitValue() }.getOrDefault(-1)
        if (out.lines().none { it.startsWith("RESULT") }) {
            WebDiagnostics.log("probe", "external probe produced no RESULT lines (exit=$code) — runtime network stack unusable?")
        }
        WebDiagnostics.log("probe", "external probe done (no credentials sent; verification left enabled)")
    }

    /**
     * Workspace durability marker: written once, then only read. If the file
     * still shows its ORIGINAL firstWrittenAt after app restarts, local file
     * operations are durable (owner check: "simple file operation ... persists
     * after restarting the app").
     */
    private fun probePersistence(app: Context) {
        val f = File(RuntimePaths.workspace(app), PERSISTENCE_FILE)
        val now = System.currentTimeMillis()
        if (f.isFile) {
            val prev = runCatching { f.readText() }.getOrDefault("")
            val first = Regex(""""firstWrittenAt":\s*(\d+)""").find(prev)?.groupValues?.get(1)
            WebDiagnostics.log(
                "probe",
                "persistence: ${f.name} exists (firstWrittenAt=${first ?: "?"}, now=$now) — " +
                    "if firstWrittenAt is older than this boot, workspace writes ARE durable"
            )
        } else {
            f.writeText("""{"firstWrittenAt":$now,"note":"E2 persistence probe; written once, never rewritten"}""")
            WebDiagnostics.log("probe", "persistence: marker created at $now (check it again after an app restart)")
        }
    }

    private data class HttpEvidence(
        val status: Int,
        val contentType: String?,
        val bytes: Int,
        val head: String,
    )

    private fun httpGet(url: String): HttpEvidence {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 3_000
            conn.readTimeout = 5_000
            conn.instanceFollowRedirects = false
            val status = conn.responseCode
            val type = conn.contentType
            val body = if (status in 200..299) {
                conn.inputStream.use { it.readBytes() }
            } else {
                runCatching { conn.errorStream?.use { it.readBytes() } }.getOrNull() ?: ByteArray(0)
            }
            HttpEvidence(status, type, body.size, String(body, Charsets.UTF_8))
        } catch (e: Exception) {
            HttpEvidence(-1, null, 0, "ERROR ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }
}
