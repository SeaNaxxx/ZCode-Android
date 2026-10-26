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
 * Provisions the local Linux userland that runs the ORIGINAL ZCode
 * distribution (`zcode --web`).
 *
 * E2 bionic mode (this APK embeds `runtime/bionic/`, built by CI):
 *   everything is embedded in the APK at build time — Termux Bionic userspace
 *   closure (node 24.22.1, git, bash + shared-lib closure, sha256-verified
 *   per deb by the E2 bundle builder) + the official ZCode distribution + the
 *   offline agent kit (mock provider + NDJSON driver). ZERO network access —
 *   provisioning must succeed in airplane mode.
 *
 * Legacy proot mode (kept unchanged for APKs built without the bundle):
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
            // E2 bionic mode: fully embedded, zero network, no proot.
            if (RuntimePaths.hasBionicAssets(app)) {
                provisionBionic(app)
                return@withContext
            }
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
            // .tgz (not .gz): AAPT2 decompresses + strips .gz assets at build time.
            val distTar = File(work, "zcode-dist.tgz")
            copyAsset(app, "runtime/zcode-dist.tgz", distTar)
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

    // ------------------------------------------------------------ E2 bionic

    /** True when the APK embeds the offline agent kit (mock provider + driver). */
    fun hasAsset(ctx: Context, name: String): Boolean = runCatching {
        ctx.assets.open(name).use { true }
    }.getOrDefault(false)

    /**
     * Fully offline provisioning from APK-embedded assets:
     *   1. verify + extract the Bionic prefix bundle (usr/bin/node, git, bash, libs)
     *   2. verify + extract the official ZCode distribution (dist/zcode/...)
     *   3. stage the offline agent kit (mock provider + NDJSON driver)
     *   4. GATE: run the embedded node on THIS device (proves Bionic exec works;
     *      records version/platform/arch in the marker and the log)
     *   5. seed provider config (mock) + git identity
     * Any failure reports its stage — nothing is faked.
     */
    private fun provisionBionic(app: Context) {
        val rt = RuntimePaths.runtimeDir(app)
        RuntimeBus.setState(RuntimeState.Provisioning("preparing directories (embedded bionic)", 2))
        val usr = RuntimePaths.usrDir(app); usr.deleteRecursively()
        val dist = RuntimePaths.distDir(app); dist.deleteRecursively()
        val home = RuntimePaths.homeDir(app)
        val work = File(rt, "downloads").apply { mkdirs() }

        // 1. Bionic prefix bundle (embedded) --------------------------------
        RuntimeBus.setState(RuntimeState.Provisioning("verifying embedded Bionic bundle", 10))
        val bundleSha = readAssetText(app, "runtime/bionic/prefix.sha256").trim()
        val bundleTar = File(work, "prefix.tgz")
        copyAsset(app, "runtime/bionic/prefix.tgz", bundleTar)
        verifySha256(bundleTar, bundleSha, "bionic-prefix")
        RuntimeBus.setState(RuntimeState.Provisioning("extracting Bionic runtime (node/git/bash)", 25))
        // tar root is usr/ -> lands at <runtime>/usr
        extractTar(bundleTar.inputStream().let { GzipCompressorInputStream(it) }, rt)
        File(usr, "bin").listFiles()?.forEach { f ->
            if (f.isFile && !java.nio.file.Files.isSymbolicLink(f.toPath())) {
                f.setExecutable(true, false)
            }
        }
        if (!RuntimePaths.nodeBin(app).isFile || !RuntimePaths.bashBin(app).isFile) {
            throw IOException("bionic bundle layout unexpected (usr/bin/node or usr/bin/bash missing)")
        }

        // 2. Official ZCode distribution (embedded) -------------------------
        RuntimeBus.setState(RuntimeState.Provisioning("staging official ZCode distribution", 45))
        val distSha = readAssetText(app, "runtime/zcode-dist.sha256").trim()
        val distTar = File(work, "zcode-dist.tgz")
        copyAsset(app, "runtime/zcode-dist.tgz", distTar)
        verifySha256(distTar, distSha, "zcode-dist")
        RuntimeBus.setState(RuntimeState.Provisioning("extracting ZCode distribution", 55))
        dist.mkdirs()
        // tar root is zcode/ -> lands at <runtime>/dist/zcode
        extractTar(distTar.inputStream().let { GzipCompressorInputStream(it) }, dist)
        if (!RuntimePaths.distAgentEntry(app).isFile || !RuntimePaths.distWebEntry(app).isFile) {
            throw IOException("zcode dist layout unexpected (agent/zcode.cjs or bin/zcode.mjs missing)")
        }

        // 3. Offline agent kit (mock provider + driver), if embedded --------
        val hasMockKit = hasAsset(app, "runtime/bionic/e1-mock-server.cjs")
        if (hasMockKit) {
            RuntimeBus.setState(RuntimeState.Provisioning("staging offline agent kit", 65))
            val kit = RuntimePaths.bionicKitDir(app).apply { mkdirs() }
            for (n in listOf("e1-mock-server.cjs", "e1-driver.cjs", "e1-provider-config.json")) {
                copyAsset(app, "runtime/bionic/$n", File(kit, n))
            }
        }

        // 4. ARM64/Bionic gate: the embedded node must run on THIS device ---
        RuntimeBus.setState(RuntimeState.Provisioning("checking Node.js on this device", 75))
        val probe = runNodeProbe(app)
        RuntimeBus.append("device-side node probe: $probe")

        // 5. Provider config (mock) + git identity --------------------------
        File(home, "tmp").mkdirs()
        RuntimePaths.dataDir(app).mkdirs()
        if (hasMockKit) {
            val pc = RuntimePaths.providerConfig(app)
            pc.parentFile?.mkdirs()
            copyAsset(app, "runtime/bionic/e1-provider-config.json", pc)
            RuntimeBus.append("offline mock provider config seeded at ${pc.absolutePath}")
            RuntimeBus.append("NOTE: model-service auth/billing is NOT exercised (mock); real provider is a separate test")
        }
        File(home, ".gitconfig").writeText(
            "[user]\n\tname = ZCode Device\n\temail = device@localhost\n"
        )

        // 6. Marker -----------------------------------------------------------
        RuntimeBus.setState(RuntimeState.Provisioning("finalizing", 95))
        RuntimePaths.marker(app).writeText(
            """{"mode":"bionic","nodeProbe":"$probe","distSha256":"$distSha",""" +
                """"bundleSha256":"$bundleSha","provisionedAt":${System.currentTimeMillis()}}"""
        )
        work.deleteRecursively()
        RuntimeBus.setState(RuntimeState.Ready)
    }

    /**
     * Runs the embedded node ON THIS DEVICE and returns
     * "v24.22.1 linux <arch>". This is the ARM64/Bionic compatibility gate:
     * a failure here is classified as exec/arch incompatibility, not as a
     * network or test-setup error.
     */
    private fun runNodeProbe(app: Context): String {
        val pb = ProcessBuilder(
            RuntimePaths.nodeBin(app).absolutePath, "-e",
            "console.log(process.version + ' ' + process.platform + ' ' + process.arch)"
        )
        pb.redirectErrorStream(true)
        applyBionicEnv(pb.environment(), app)
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        val code = proc.waitFor()
        if (code != 0) {
            throw IOException("embedded node failed on this device (exit $code): ${out.take(300)}")
        }
        return out.take(200)
    }

    /**
     * Environment for every Bionic process (provision probe, mock provider,
     * web server, agent children). Mirrors the proven E0/E1 wrapper
     * (emu-run.sh) adapted to the app-owned non-canonical prefix.
     */
    internal fun applyBionicEnv(env: MutableMap<String, String>, app: Context) {
        val usr = RuntimePaths.usrDir(app)
        val home = RuntimePaths.homeDir(app)
        env["PREFIX"] = usr.absolutePath
        env["HOME"] = home.absolutePath
        env["TMPDIR"] = File(home, "tmp").absolutePath
        env["PATH"] = "${usr.absolutePath}/bin:/system/bin:/system/xbin"
        env["LD_LIBRARY_PATH"] = "${usr.absolutePath}/lib"
        env["SHELL"] = RuntimePaths.bashBin(app).absolutePath
        env["LANG"] = "C.UTF-8"
        env["NODE_OPTIONS"] = ""
        env["PAGER"] = "cat"
        env["GIT_PAGER"] = "cat"
        env["GIT_EXEC_PATH"] = "${usr.absolutePath}/libexec/git-core"
        env["GIT_CONFIG_NOSYSTEM"] = "1"
        val cert = File(usr, "etc/tls/cert.pem")
        if (cert.isFile) {
            env["SSL_CERT_FILE"] = cert.absolutePath
        }
        env["ZCODE_DATA_BASE_DIR"] = RuntimePaths.dataDir(app).absolutePath
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
