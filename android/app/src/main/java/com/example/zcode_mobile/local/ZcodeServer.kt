package com.example.zcode_mobile.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Runs the ORIGINAL ZCode distribution in web mode.
 *
 * E2 bionic mode (fully embedded runtime, no proot):
 *   <runtime>/usr/bin/node <runtime>/dist/zcode/bin/zcode.mjs --web --no-open \
 *     --host 127.0.0.1 --port 3030 --workspace <filesDir>/workspace
 *   plus the embedded offline mock provider (127.0.0.1:9999) when the offline
 *   agent kit is present — the agent loop then works with no network and no
 *   API key (model-service auth/billing NOT exercised; separate test).
 *
 * Legacy proot mode (APKs without the bionic bundle):
 *   proot -r <rootfs> -0 -w /root -b /dev -b /proc -b /sys -b <workspace>:/workspace \
 *     /opt/node/bin/node /opt/zcode/bin/zcode.mjs --web --no-open \
 *     --host 127.0.0.1 --port 3030 --workspace /workspace
 *
 * The web server binds loopback only.
 */
object ZcodeServer {

    @Volatile private var process: Process? = null
    @Volatile private var mockProcess: Process? = null
    @Volatile private var healthThread: Thread? = null

    val isRunning: Boolean
        get() = process?.isAlive == true

    suspend fun start(ctx: Context): Unit = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        if (RuntimeBus.state.value is RuntimeState.Running) return@withContext
        if (!Provisioner.isProvisioned(app)) {
            RuntimeBus.setState(RuntimeState.Failed("start", "runtime not provisioned — run setup first"))
            return@withContext
        }
        RuntimeBus.setState(RuntimeState.Starting)
        stopInternal()

        if (RuntimePaths.isBionic(app)) {
            startBionic(app)
            return@withContext
        }

        val rootfs = RuntimePaths.rootfs(app).absolutePath
        val workspace = RuntimePaths.workspace(app).absolutePath
        val nativeDir = RuntimePaths.nativeLibDir(app)

        fun command(withSdcard: Boolean): List<String> = buildList {
            add("$nativeDir/libproot.so")
            add("-r"); add(rootfs)
            add("-0")
            add("-w"); add("/root")
            add("-b"); add("/dev")
            add("-b"); add("/proc")
            add("-b"); add("/sys")
            add("-b"); add("$workspace:/workspace")
            if (withSdcard && File("/sdcard").isDirectory) {
                add("-b"); add("/sdcard:/sdcard")
            }
            add("/opt/node/bin/node")
            add("/opt/zcode/bin/zcode.mjs")
            add("--web"); add("--no-open")
            add("--host"); add("127.0.0.1")
            add("--port"); add(RuntimePaths.SERVER_PORT.toString())
            add("--workspace"); add("/workspace")
        }

        for (withSdcard in listOf(true, false)) {
            try {
                RuntimeBus.append(
                    "spawning proot (sdcard bind = $withSdcard) " +
                        command(withSdcard).joinToString(" ")
                )
                val pb = ProcessBuilder(command(withSdcard))
                pb.redirectErrorStream(true)
                val env = pb.environment()
                env["LD_LIBRARY_PATH"] = nativeDir
                env["PROOT_NO_SECCOMP"] = "1"
                env["HOME"] = "/root"
                env["PATH"] = "/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                env["TMPDIR"] = "/tmp"
                val proc = pb.start()
                process = proc

                Thread {
                    try {
                        proc.inputStream.bufferedReader().use { r: BufferedReader ->
                            while (true) {
                                val line = r.readLine() ?: break
                                RuntimeBus.append("agent| $line")
                            }
                        }
                    } catch (_: Exception) { }
                    if (!RuntimeBus.state.value.let { it is RuntimeState.Running || it is RuntimeState.Stopped }) {
                        RuntimeBus.append("runtime process exited")
                    }
                }.apply { isDaemon = true }.start()

                // health loop: official web UI must answer before RUNNING
                val url = RuntimePaths.SERVER_URL
                val deadline = System.currentTimeMillis() + 120_000L
                var healthy = false
                while (System.currentTimeMillis() < deadline) {
                    if (!proc.isAlive) {
                        RuntimeBus.append("process died during startup (exit=${proc.exitValue()})")
                        break
                    }
                    if (httpOk(url)) { healthy = true; break }
                    Thread.sleep(2_000)
                }
                if (healthy) {
                    RuntimeBus.setState(RuntimeState.Running(url))
                    return@withContext
                }
                if (!proc.isAlive && withSdcard) {
                    RuntimeBus.append("retrying without /sdcard bind")
                    continue
                }
                val tail = "see log console"
                throw IOException("server did not become healthy within 120s ($tail)")
            } catch (t: Throwable) {
                stopInternal()
                RuntimeBus.append("start failed: ${t.message}")
                if (withSdcard) {
                    RuntimeBus.append("retrying without /sdcard bind")
                } else {
                    RuntimeBus.setState(RuntimeState.Failed("start", "${t.message}"))
                    return@withContext
                }
            }
        }
    }

    /**
     * Bionic start path: (1) offline mock provider on 127.0.0.1:9999 when the
     * offline agent kit is staged, (2) the official dist's web entry via the
     * embedded Bionic node, real workspace path (no proot mapping).
     */
    private fun startBionic(app: Context) {
        val workspace = RuntimePaths.workspace(app).absolutePath
        val kit = RuntimePaths.bionicKitDir(app)
        val mockScript = File(kit, "e1-mock-server.cjs")
        if (mockScript.isFile && RuntimePaths.providerConfig(app).isFile) {
            RuntimeBus.append("starting embedded mock provider :9999 (offline agent loop; auth/billing NOT exercised)")
            val mpb = ProcessBuilder(
                RuntimePaths.nodeBin(app).absolutePath, mockScript.absolutePath,
                "--port", "9999",
                "--log", File(RuntimePaths.runtimeDir(app), "mock.log").absolutePath,
                "--workspace", workspace
            )
            mpb.redirectErrorStream(true)
            Provisioner.applyBionicEnv(mpb.environment(), app)
            val mp = mpb.start()
            mockProcess = mp
            Thread {
                try {
                    mp.inputStream.bufferedReader().use { r: BufferedReader ->
                        while (true) { val line = r.readLine() ?: break; RuntimeBus.append("mock| $line") }
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true }.start()
            var mockUp = false
            val mockDeadline = System.currentTimeMillis() + 15_000L
            while (System.currentTimeMillis() < mockDeadline) {
                if (!mp.isAlive) break
                if (httpOk("http://127.0.0.1:9999/healthz")) { mockUp = true; break }
                Thread.sleep(500)
            }
            if (!mockUp) {
                stopInternal()
                throw IOException("embedded mock provider did not become healthy (offline agent loop disabled)")
            }
            RuntimeBus.append("mock provider healthy (loopback only)")
        } else {
            RuntimeBus.append("no offline agent kit — web server only")
        }

        try {
            val command = listOf(
                RuntimePaths.nodeBin(app).absolutePath,
                RuntimePaths.distWebEntry(app).absolutePath,
                "--web", "--no-open",
                "--host", "127.0.0.1",
                "--port", RuntimePaths.SERVER_PORT.toString(),
                "--workspace", workspace
            )
            RuntimeBus.append("spawning bionic runtime: ${command.joinToString(" ")}")
            val pb = ProcessBuilder(command)
            pb.redirectErrorStream(true)
            Provisioner.applyBionicEnv(pb.environment(), app)
            val proc = pb.start()
            process = proc

            Thread {
                try {
                    proc.inputStream.bufferedReader().use { r: BufferedReader ->
                        while (true) { val line = r.readLine() ?: break; RuntimeBus.append("agent| $line") }
                    }
                } catch (_: Exception) {}
                if (!RuntimeBus.state.value.let { it is RuntimeState.Running || it is RuntimeState.Stopped }) {
                    RuntimeBus.append("runtime process exited")
                }
            }.apply { isDaemon = true }.start()

            val url = RuntimePaths.SERVER_URL
            val startedAt = System.currentTimeMillis()
            val deadline = startedAt + 120_000L
            var healthy = false
            while (System.currentTimeMillis() < deadline) {
                if (!proc.isAlive) {
                    RuntimeBus.append("process died during startup (exit=${proc.exitValue()})")
                    break
                }
                if (httpOk(url)) { healthy = true; break }
                Thread.sleep(2_000)
            }
            if (healthy) {
                // Owner check 1+2 evidence: WHICH process owns the port, and how
                // long the official web entry took to answer on this device.
                val pid = runCatching { proc.pid() }.getOrDefault(-1L)
                WebDiagnostics.log(
                    "probe",
                    "local: node pid=$pid spawned as: ${RuntimePaths.nodeBin(app).name} " +
                        "${RuntimePaths.distWebEntry(app).name} --web (args in runtime log); " +
                        "GET $url health OK after ${(System.currentTimeMillis() - startedAt) / 1000}s"
                )
                RuntimeBus.setState(RuntimeState.Running(url))
                return
            }
            throw IOException(
                if (!proc.isAlive) "server exited during startup (see log console)"
                else "server did not become healthy within 120s (see log console)"
            )
        } catch (t: Throwable) {
            stopInternal()
            RuntimeBus.append("start failed: ${t.message}")
            RuntimeBus.setState(RuntimeState.Failed("start", "${t.message}"))
        }
    }

    fun stop() {
        stopInternal()
        if (RuntimeBus.state.value is RuntimeState.Running ||
            RuntimeBus.state.value is RuntimeState.Starting
        ) {
            RuntimeBus.setState(RuntimeState.Stopped)
        }
    }

    private fun stopInternal() {
        healthThread?.interrupt()
        healthThread = null
        runCatching { mockProcess?.destroy() }
        runCatching { mockProcess?.destroyForcibly() }
        mockProcess = null
        runCatching { process?.destroy() }
        Thread.sleep(150)
        runCatching { process?.destroyForcibly() }
        process = null
    }

    private fun httpOk(url: String): Boolean = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 1_500
        c.readTimeout = 1_500
        val ok = c.responseCode in 200..299
        c.disconnect()
        ok
    }.getOrDefault(false)
}
