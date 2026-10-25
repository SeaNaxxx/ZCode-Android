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
 * Runs the ORIGINAL ZCode distribution in web mode inside the provisioned
 * Linux ARM64 userland:
 *
 *   proot -r <rootfs> -0 -w /root -b /dev -b /proc -b /sys -b <workspace>:/workspace \
 *     /opt/node/bin/node /opt/zcode/bin/zcode.mjs --web --no-open \
 *     --host 127.0.0.1 --port 3030 --workspace /workspace
 *
 * proot and libtalloc come from jniLibs (executable by Android by construction);
 * Node and the dist live in the rootfs. The web server binds loopback only.
 */
object ZcodeServer {

    @Volatile private var process: Process? = null
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
