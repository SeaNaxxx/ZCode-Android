package com.example.zcode_mobile.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Provisions the local Linux ARM64 userland that runs the ORIGINAL ZCode
 * distribution (`zcode --web`):
 *
 *   downloaded (public, checksum-verified):
 *     - Ubuntu base 24.04.3 arm64 rootfs   (cdimage.ubuntu.com, SHA256SUMS)
 *     - Node.js 24.14.0 linux-arm64        (nodejs.org, SHASUMS256.txt)
 *   embedded in the APK at build time (CI):
 *     - zcode-<v>.tar.gz official CLI distribution + embedded sha256
 *     - proot + libtalloc binaries         (Termux packages, licenses in NOTICE)
 *
 * No credentials are handled here. Every failure is reported with its stage.
 */
object Provisioner {

    private const val NODE_SHASUMS = "https://nodejs.org/dist/v${RuntimePaths.NODE_VERSION}/SHASUMS256.txt"
    private const val UBUNTU_SHA_URL =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/SHA256SUMS"

    fun isProvisioned(ctx: Context): Boolean = RuntimePaths.provisioned(ctx)

    suspend fun provision(ctx: Context) = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        try {
            RuntimeBus.setState(RuntimeState.Provisioning("preparing directories", 2))
            val root = RuntimePaths.rootfs(app)
            root.deleteRecursively()
            root.mkdirs()
            RuntimePaths.runtimeDir(app).mkdirs()

            val work = File(RuntimePaths.runtimeDir(app), "downloads").apply { mkdirs() }

            // 1. Ubuntu rootfs ------------------------------------------------
            RuntimeBus.setState(RuntimeState.Provisioning("downloading Ubuntu base (arm64)", 5))
            val rootfsTar = File(work, "ubuntu-base.tar.gz")
            download(RuntimePaths.UBUNTU_BASE_URL, rootfsTar)
            RuntimeBus.setState(RuntimeState.Provisioning("verifying Ubuntu base sha256", 20))
            val expectedRootfs = fetchExpectedSha(UBUNTU_SHA_URL, rootfsTar.name)
            verifySha256(rootfsTar, expectedRootfs, "ubuntu-base")
            RuntimeBus.setState(RuntimeState.Provisioning("extracting Ubuntu rootfs", 25))
            extractTar(rootfsTar.inputStream().let { GzipCompressorInputStream(it) }, root)

            // 2. Node.js ------------------------------------------------------
            val nodeTarName = "node-v${RuntimePaths.NODE_VERSION}-linux-arm64.tar.xz"
            RuntimeBus.setState(RuntimeState.Provisioning("downloading Node.js ${RuntimePaths.NODE_VERSION}", 40))
            val nodeTar = File(work, nodeTarName)
            download("https://nodejs.org/dist/v${RuntimePaths.NODE_VERSION}/$nodeTarName", nodeTar)
            RuntimeBus.setState(RuntimeState.Provisioning("verifying Node.js sha256", 50))
            verifySha256(nodeTar, fetchExpectedSha(NODE_SHASUMS, nodeTarName), "node")
            RuntimeBus.setState(RuntimeState.Provisioning("extracting Node.js", 55))
            val opt = File(root, "opt").apply { mkdirs() }
            val nodeTmp = File(opt, "_node_tmp").apply { mkdirs() }
            extractTar(nodeTar.inputStream().let { XZCompressorInputStream(it) }, nodeTmp)
            val extractedNode = File(nodeTmp, "node-v${RuntimePaths.NODE_VERSION}-linux-arm64")
            if (!File(extractedNode, "bin/node").isFile) {
                throw IOException("node tarball layout unexpected (bin/node missing)")
            }
            val nodeDir = File(opt, "node")
            if (!extractedNode.renameTo(nodeDir)) {
                extractedNode.copyRecursively(nodeDir, overwrite = true)
                extractedNode.deleteRecursively()
            }
            nodeTmp.deleteRecursively()

            // 3. ZCode dist (embedded in APK by CI) ----------------------------
            RuntimeBus.setState(RuntimeState.Provisioning("staging official ZCode distribution", 65))
            val distSha = readAssetText(app, "runtime/zcode-dist.sha256").trim()
            val distTar = File(work, "zcode-dist.tar.gz")
            copyAsset(app, "runtime/zcode-dist.tar.gz", distTar)
            verifySha256(distTar, distSha, "zcode-dist")
            RuntimeBus.setState(RuntimeState.Provisioning("extracting ZCode distribution", 72))
            extractTar(distTar.inputStream().let { GzipCompressorInputStream(it) }, opt)
            if (!File(root, "opt/zcode/bin/zcode.mjs").isFile) {
                throw IOException("zcode dist layout unexpected (opt/zcode/bin/zcode.mjs missing)")
            }

            // 4. Rootfs configuration -----------------------------------------
            RuntimeBus.setState(RuntimeState.Provisioning("configuring rootfs", 85))
            File(root, "root").mkdirs()
            File(root, "tmp").mkdirs()
            File(root, "etc/resolv.conf").writeText(
                "nameserver 8.8.8.8\nnameserver 1.1.1.1\n"
            )

            // 5. Marker --------------------------------------------------------
            RuntimeBus.setState(RuntimeState.Provisioning("finalizing", 95))
            RuntimePaths.marker(app).writeText(
                """{"node":"${RuntimePaths.NODE_VERSION}","distSha256":"$distSha","provisionedAt":${System.currentTimeMillis()}}"""
            )
            work.deleteRecursively()
            RuntimeBus.setState(RuntimeState.Ready)
        } catch (t: Throwable) {
            RuntimeBus.append("provisioning failed: ${t.javaClass.simpleName}: ${t.message}")
            RuntimeBus.setState(RuntimeState.Failed("setup", "${t.message ?: t.javaClass.simpleName}"))
        }
    }

    // ------------------------------------------------------------------ HTTP

    private fun openConnection(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "ZCode-Android/0.1 (+local-runtime)")
        return c
    }

    private fun download(url: String, dest: File) {
        RuntimeBus.append("GET $url")
        val conn = openConnection(url)
        try {
            if (conn.responseCode !in 200..299) {
                throw IOException("HTTP ${conn.responseCode} for $url")
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var read: Int
                    var done = 0L
                    var lastPct = -1
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        done += read
                        if (total > 0) {
                            val pct = ((done * 100) / total).toInt()
                            if (pct != lastPct && pct % 5 == 0) {
                                lastPct = pct
                                RuntimeBus.append("  $pct% (${done / (1 shl 20)}MB / ${total / (1 shl 20)}MB)")
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        RuntimeBus.append("saved ${dest.name} (${dest.length() / (1 shl 20)}MB)")
    }

    private fun fetchText(url: String): String {
        val conn = openConnection(url)
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode} for $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Pull the expected sha256 for [fileName] out of a checksum listing. */
    private fun fetchExpectedSha(listUrl: String, fileName: String): String {
        val text = fetchText(listUrl)
        for (line in text.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size == 2 && parts[1].trimStart('*').let { it == fileName || it.endsWith("/$fileName") }) {
                return parts[0]
            }
        }
        throw IOException("checksum for $fileName not found in $listUrl")
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(1 shl 16)
            var r: Int
            while (input.read(buf).also { r = it } != -1) md.update(buf, 0, r)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun verifySha256(file: File, expected: String, label: String) {
        val got = sha256(file)
        if (!expected.equals(got, ignoreCase = true)) {
            throw IOException("$label sha256 mismatch: expected=$expected got=$got")
        }
        RuntimeBus.append("$label sha256 OK")
    }

    // -------------------------------------------------------------- archives

    private fun extractTar(raw: java.io.InputStream, dest: File) {
        TarArchiveInputStream(raw.buffered(1 shl 16)).use { tis ->
            val destCanon = dest.canonicalPath + File.separator
            while (true) {
                val entry: TarArchiveEntry = tis.nextTarEntry ?: break
                val target = File(dest, entry.name)
                if (!target.canonicalPath.startsWith(destCanon) && target.canonicalPath != dest.canonicalPath) {
                    throw IOException("archive entry escapes destination: ${entry.name}")
                }
                when {
                    entry.isDirectory -> target.mkdirs()
                    entry.isSymbolicLink -> {
                        target.parentFile?.mkdirs()
                        if (target.exists()) target.delete()
                        runCatching {
                            Files.createSymbolicLink(target.toPath(), Path.of(entry.linkName))
                        }.onFailure {
                            RuntimeBus.append("symlink skipped: ${entry.name} -> ${entry.linkName}")
                        }
                    }
                    entry.isLink -> {
                        target.parentFile?.mkdirs()
                        val linkTarget = File(dest, entry.linkName)
                        runCatching { Files.createLink(target.toPath(), linkTarget.toPath()) }
                            .onFailure { linkTarget.copyTo(target, overwrite = true) }
                    }
                    else -> {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { tis.copyTo(it) }
                        applyMode(target, entry.mode)
                    }
                }
            }
        }
    }

    private fun applyMode(f: File, mode: Int) {
        val user = (mode shr 6) and 7
        f.setReadable(true)
        if (user and 4 != 0) f.setReadable(true, false) else f.setReadable(true, true)
        if (user and 2 != 0) f.setWritable(true)
        if (user and 1 != 0) f.setExecutable(true, false)
    }

    // --------------------------------------------------------------- assets

    private fun copyAsset(ctx: Context, name: String, dest: File) {
        ctx.assets.open(name).use { input ->
            FileOutputStream(dest).use { input.copyTo(it) }
        }
    }

    private fun readAssetText(ctx: Context, name: String): String =
        ctx.assets.open(name).bufferedReader().use { it.readText() }
}
