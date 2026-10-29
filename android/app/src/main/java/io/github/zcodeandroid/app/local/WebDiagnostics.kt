package io.github.zcodeandroid.app.local

import android.content.Context
import android.util.Log

/**
 * Web/UI-side evidence collection for the embedded device protocol (device failure 3:
 * black screen while the server runs, Vivo V2035, 2026-10-28).
 *
 * Why this exists: the server-side runtime log proves the Node process and the
 * RPC layer work, but says NOTHING about what the WebView did — page load
 * lifecycle, JavaScript console output, HTTP/resource errors, renderer death.
 * Those signals live on the UI side and were previously discarded (a bare
 * WebViewClient with no callbacks, no WebChromeClient).
 *
 * Every line goes to three sinks:
 *  1. [DiagLog] — own ring buffer, so a chatty frontend boot can NOT evict
 *     these lines from the exported diagnostics (RuntimeBus keeps only 400
 *     lines and the export shows the last 150);
 *  2. [RuntimeBus] — the live "Runtime log" console on the status screen;
 *  3. logcat tag "ZCodeWeb" — so CI emulator stages and adb users can grep it.
 *
 * Privacy: no cookies, no tokens, no authorization headers, no page content
 * beyond a short plain-text snapshot of the UI shell — see the DOM-state probe.
 */
object WebDiagnostics {

    const val LOGCAT_TAG = "ZCodeWeb"

    fun log(tag: String, message: String) {
        val line = "[$tag] ${message.replace("\n", " ⏎ ")}"
        DiagLog.append(line)
        RuntimeBus.append(line)
        runCatching { Log.i(LOGCAT_TAG, line) }
    }

    /**
     * Identify the system WebView implementation and version. A very old
     * Chromium (vendor-shipped WebView without updates) is one of the black
     * screen hypotheses; this line makes that visible in every export.
     */
    fun webViewProviderInfo(ctx: Context): String {
        if (android.os.Build.VERSION.SDK_INT < 26) return "unavailable (< API 26)"
        return runCatching {
            val pkg = android.webkit.WebView.getCurrentWebViewPackage()
            if (pkg == null) "unknown (getCurrentWebViewPackage null)"
            else "${pkg.packageName} ${pkg.versionName ?: "?"}"
        }.getOrDefault("lookup failed")
    }

    /**
     * Pure-JVM pixel statistics for the WebView paint-capture evidence
     * (failure-3 follow-up: "content exists in the DOM, background paints,
     * content pixels never appear"). The caller samples the captured WebView
     * bitmap on a grid and hands the ARGB ints here; the returned string is
     * what lands in the shareable diagnostics export, so the on-device
     * verdict needs no image viewing:
     *
     *  - `uniformBlack=100% distinctColors=1`  -> renderer produced NOTHING
     *    (paint pipeline dead for this page);
     *  - content shares > 0 with sane colors   -> the bitmap HAS the UI, so a
     *    black SCREEN with a non-black bitmap is an occlusion/compositing
     *    problem above the WebView, not a renderer one.
     *
     * Deliberately quantized (4 bits per channel): JPEG-free, tiny, and no
     * page content leaks — only aggregate color statistics.
     */
    fun analyzePixels(pixels: IntArray): String {
        if (pixels.isEmpty()) return "no-samples"
        var nearBlack = 0
        val buckets = HashMap<Int, IntArray>() // bucket -> [count, representative ARGB]
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (r < 14 && g < 14 && b < 14) nearBlack++
            val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            val entry = buckets.getOrPut(key) { intArrayOf(0, p) }
            entry[0]++
        }
        val n = pixels.size
        val dominant = buckets.entries.maxByOrNull { it.value[0] }!!
        val dominantHex = String.format(
            java.util.Locale.US, "#%02x%02x%02x",
            (dominant.value[1] shr 16) and 0xFF,
            (dominant.value[1] shr 8) and 0xFF,
            dominant.value[1] and 0xFF,
        )
        return "uniformBlack=${nearBlack * 100 / n}% distinctColors=${buckets.size} " +
            "dominant=$dominantHex dominantShare=${dominant.value[0] * 100 / n}% samples=$n"
    }
}

/**
 * Pure-JVM timestamped ring buffer for UI-side evidence. Kept free of any
 * android.* imports so the eviction/cap behavior is unit-testable on the CI JVM.
 */
object DiagLog {

    private const val CAP = 500
    private val lines = ArrayDeque<String>(CAP)
    private val lock = Any()

    fun append(line: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        synchronized(lock) {
            lines.addLast("[$ts] $line")
            while (lines.size > CAP) lines.removeFirst()
        }
    }

    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    /** Test hook: deterministic reset between unit tests. */
    fun clear() = synchronized(lock) { lines.clear() }
}
