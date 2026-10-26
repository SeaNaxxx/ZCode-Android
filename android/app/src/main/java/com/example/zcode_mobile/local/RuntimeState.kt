package com.example.zcode_mobile.local

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * Local ZCode runtime state machine. Every state is real: nothing is faked.
 * FAILED carries the exact stage + message so failures are diagnosable.
 */
sealed interface RuntimeState {
    /** No provision-complete marker found; user must run setup. */
    data object NotProvisioned : RuntimeState

    /** Downloads / extraction in progress. step is human-readable, percent 0..100. */
    data class Provisioning(val step: String, val percent: Int) : RuntimeState

    /** Provisioned and idle. */
    data object Ready : RuntimeState

    /** proot/node process launched, waiting for HTTP health on 127.0.0.1. */
    data object Starting : RuntimeState

    /** Official ZCode web UI is served and answering. */
    data class Running(val url: String) : RuntimeState

    /** Server was running and then stopped (by user or exit). */
    data object Stopped : RuntimeState

    /** Provisioning or startup failed; stage + message must be actionable. */
    data class Failed(val stage: String, val message: String) : RuntimeState
}

/**
 * Single source of runtime state for the UI and the foreground service.
 * Kept deliberately simple (process-wide singleton object) for v1.
 */
object RuntimeBus {
    val state = MutableStateFlow<RuntimeState>(RuntimeState.NotProvisioned)
    val log = MutableStateFlow<List<String>>(emptyList())

    fun setState(s: RuntimeState) {
        state.value = s
        append("state -> $s")
    }

    fun append(line: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        log.update { (it + "[$ts] $line").takeLast(LOG_LINES) }
    }

    private const val LOG_LINES = 400
}

/** Filesystem layout of the embedded Linux userland (all under app-private storage). */
object RuntimePaths {
    const val SERVER_URL = "http://127.0.0.1:3030"
    const val SERVER_PORT = 3030
    const val NODE_VERSION = "24.14.0"
    const val UBUNTU_BASE_URL =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"

    fun runtimeDir(ctx: Context): File = File(ctx.filesDir, "runtime")
    fun rootfs(ctx: Context): File = File(runtimeDir(ctx), "rootfs")
    fun workspace(ctx: Context): File = File(ctx.filesDir, "workspace").apply { mkdirs() }
    fun marker(ctx: Context): File = File(runtimeDir(ctx), "provision-complete.json")

    /** proot + libtalloc ship as native libs so Android keeps them executable (jniLibs). */
    fun nativeLibDir(ctx: Context): String = ctx.applicationInfo.nativeLibraryDir

    // ------------------------------------------------------------- E2 bionic
    // Layout of the fully embedded Bionic runtime (no downloads, no proot):
    //   <runtime>/usr/...       Termux-prefix bundle (node, git, bash, libs)
    //   <runtime>/dist/zcode/   official ZCode distribution
    //   <runtime>/bionic/       offline agent kit (mock provider + NDJSON driver)
    //   <runtime>/home/         HOME (tmp/, .gitconfig)
    //   <runtime>/zcode-data/   ZCODE_DATA_BASE_DIR (provider config, sessions)
    fun usrDir(ctx: Context): File = File(runtimeDir(ctx), "usr")
    fun distDir(ctx: Context): File = File(runtimeDir(ctx), "dist")
    fun bionicKitDir(ctx: Context): File = File(runtimeDir(ctx), "bionic")
    fun homeDir(ctx: Context): File = File(runtimeDir(ctx), "home").apply { mkdirs() }
    fun dataDir(ctx: Context): File = File(runtimeDir(ctx), "zcode-data")
    fun nodeBin(ctx: Context): File = File(usrDir(ctx), "bin/node")
    fun bashBin(ctx: Context): File = File(usrDir(ctx), "bin/bash")
    fun gitBin(ctx: Context): File = File(usrDir(ctx), "bin/git")
    fun distWebEntry(ctx: Context): File = File(distDir(ctx), "zcode/bin/zcode.mjs")
    fun distAgentEntry(ctx: Context): File = File(distDir(ctx), "zcode/agent/zcode.cjs")
    fun providerConfig(ctx: Context): File =
        File(File(dataDir(ctx), ".zcode/v2"), "provider_config.json")

    /** Bionic runtime staged on disk? (node + bash + git + dist entries) */
    fun isBionic(ctx: Context): Boolean = runCatching {
        nodeBin(ctx).isFile && bashBin(ctx).isFile && gitBin(ctx).isFile &&
            distAgentEntry(ctx).isFile
    }.getOrDefault(false)

    /** Does this APK embed the Bionic bundle (built by the E2 workflow)? */
    fun hasBionicAssets(ctx: Context): Boolean = runCatching {
        ctx.assets.list("runtime/bionic")?.contains("manifest.json") == true
    }.getOrDefault(false)

    /** Simple, never-throwing provisioned check: marker + layout entries. */
    fun provisioned(ctx: Context): Boolean = runCatching {
        if (!marker(ctx).isFile) return@runCatching false
        if (hasBionicAssets(ctx)) {
            isBionic(ctx)
        } else {
            rootfs(ctx).isDirectory &&
                File(rootfs(ctx), "opt/zcode/bin/zcode.mjs").isFile &&
                File(rootfs(ctx), "opt/node/bin/node").isFile
        }
    }.getOrDefault(false)

    fun distShaFile(ctx: Context): File = File(rootfs(ctx), "opt/zcode-dist.sha256")
}
