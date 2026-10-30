package io.github.zcodeandroid.app.local

import java.io.File

/**
 * Environment for every Bionic process (provision probe, mock provider, web
 * server, agent children). Pure-JVM (no android.* imports) so the exact env
 * contract is unit-testable on the CI JVM — the OpenSSL variables below are
 * the device-failure-2 fix (Vivo V2035, 2026-10-28) and must never regress.
 *
 * DEVICE FAILURE 2 ROOT CAUSE (proven from the shipped .deb, not assumed):
 *   The Termux-built libcrypto.so.3 has its OpenSSL install paths COMPILED IN:
 *     OPENSSLDIR: "/data/data/com.termux/files/usr/etc/tls"
 *     MODULESDIR: "/data/data/com.termux/files/usr/lib/ossl-modules"
 *   At startup Node initializes OpenSSL with config loading; OpenSSL then
 *   fopen()s the compiled-in openssl.cnf path. For this app that path lives
 *   inside ANOTHER app's private storage -> EACCES -> fatal
 *   "OpenSSL configuration error" (node exit 13). The bundle ALREADY carries
 *   the real config files (openssl package: usr/etc/tls/openssl.cnf;
 *   ca-certificates package: usr/etc/tls/cert.pem) — they were simply never
 *   wired to the environment.
 *
 * RULES (owner constraints, 2026-10-28):
 *   - every variable points INSIDE the app-private runtime, never at Termux;
 *   - TLS certificate verification is NEVER disabled (no NODE_TLS_REJECT_*
 *     tampering, no SSL_CERT_* pointing at empty/fake stores);
 *   - variables are set only when their target actually exists in the bundle
 *     (or can be materialized by [ensureOpenSslConfig]).
 */
object BionicEnv {

    /** Relative layout inside the runtime prefix (Termux package layout). */
    const val OPENSSL_CONF_REL = "etc/tls/openssl.cnf"
    const val CERT_PEM_REL = "etc/tls/cert.pem"
    const val CERTS_DIR_REL = "etc/tls/certs"
    const val OSSL_MODULES_REL = "lib/ossl-modules"

    /**
     * App-shipped, build-current Mozilla CA bundle (assets
     * runtime/bionic/cacert.pem, materialized here). Supersedes the
     * ca-certificates deb's cert.pem when present: a device provisioned by an
     * OLDER APK keeps its original runtime directory across app updates, so
     * its on-disk store can lag behind the chain a provider serves. The
     * device MODEL_TLS_VALIDATION_FAILED report (2026-10-31) is the driver.
     */
    const val CA_BUNDLE_REL = "etc/tls/cacert-zcode.pem"

    /**
     * Minimal fallback config written ONLY when the bundled openssl.cnf is
     * absent (e.g. a future bundle change drops the openssl package). It must
     * exist and be readable because an UNREADABLE OPENSSL_CONF target is
     * fatal at node startup — the exact failure seen on the Vivo V2035.
     * Deliberately minimal: no provider sections (the default provider is
     * built into libcrypto), nothing that would weaken TLS verification.
     */
    val FALLBACK_OPENSSL_CNF = """
        # ZCode-Android embedded runtime — fallback OpenSSL configuration.
        # Written because the bundled openssl.cnf was missing. The Termux-built
        # libcrypto has OPENSSLDIR compiled in as /data/data/com.termux/...,
        # which is unreadable for this app (fatal at node startup), so
        # OPENSSL_CONF is always pointed at a file inside the app runtime.
        # Intentionally minimal: defaults apply and TLS verification stays on.
        [openssl_init]
    """.trimIndent() + "\n"

    /**
     * Returns a readable openssl.cnf inside [usr]: the bundled one when
     * present, otherwise writes the minimal fallback (idempotent) and returns
     * it. Throws only when neither is possible (that is a hard provisioning
     * error, reported with its stage by the caller).
     */
    fun ensureOpenSslConfig(usr: File): File {
        val bundled = File(usr, OPENSSL_CONF_REL)
        if (bundled.isFile && bundled.canRead()) return bundled
        val fallback = File(usr, OPENSSL_CONF_REL)
        fallback.parentFile?.mkdirs()
        if (!fallback.exists() || !fallback.canRead()) {
            fallback.writeText(FALLBACK_OPENSSL_CNF)
        }
        return fallback
    }

    /**
     * Pure-filesystem env contract. The Context overload in Provisioner
     * delegates here with [RuntimePaths] values so JVM tests exercise the
     * exact same code the device runs. [caBundle] is the materialized
     * app-shipped CA store (see [CA_BUNDLE_REL]); when present it supersedes
     * the deb's cert.pem for every CA-aware variable and additionally drives
     * NODE_EXTRA_CA_CERTS (Node appends these roots to whatever store mode it
     * was built with) and ZCODE_AGENT_CA_CERT (the official dist's fetch
     * client then builds its https agent from THIS bundle explicitly).
     */
    fun apply(
        env: MutableMap<String, String>,
        usr: File,
        home: File,
        dataDir: File,
        bashBin: File,
        caBundle: File? = null,
    ) {
        env["PREFIX"] = usr.absolutePath
        env["HOME"] = home.absolutePath
        env["TMPDIR"] = File(home, "tmp").absolutePath
        env["PATH"] = "${usr.absolutePath}/bin:/system/bin:/system/xbin"
        env["LD_LIBRARY_PATH"] = "${usr.absolutePath}/lib"
        env["SHELL"] = bashBin.absolutePath
        env["LANG"] = "C.UTF-8"
        env["NODE_OPTIONS"] = ""
        env["PAGER"] = "cat"
        env["GIT_PAGER"] = "cat"
        env["GIT_EXEC_PATH"] = "${usr.absolutePath}/libexec/git-core"
        env["GIT_CONFIG_NOSYSTEM"] = "1"
        env["ZCODE_DATA_BASE_DIR"] = dataDir.absolutePath

        // --- OpenSSL / TLS wiring (device failure 2 fix) -------------------
        // OPENSSL_CONF must ALWAYS point at a readable file inside the
        // runtime; ensureOpenSslConfig materializes the fallback if needed.
        val cnf = runCatching { ensureOpenSslConfig(usr) }.getOrNull()
        if (cnf != null && cnf.isFile) {
            env["OPENSSL_CONF"] = cnf.absolutePath
        }
        // Overrides MODULESDIR compiled into libcrypto (Termux path).
        val modules = File(usr, OSSL_MODULES_REL)
        if (modules.isDirectory) {
            env["OPENSSL_MODULES"] = modules.absolutePath
        }
        // CA store: only ever a bundled, real certificate file/directory.
        // Node ships its own compiled-in root store; these variables exist so
        // OpenSSL-based children (git/libcurl) resolve CAs inside the runtime.
        // The app-shipped bundle, when materialized, wins: it is refreshed per
        // APK build, while a device provisioned months earlier keeps the deb
        // it was installed with.
        val bundle = caBundle?.takeIf { it.isFile && it.length() > 0 }
        val cert = bundle ?: File(usr, CERT_PEM_REL).takeIf { it.isFile }
        if (cert != null) {
            env["SSL_CERT_FILE"] = cert.absolutePath
            env["GIT_SSL_CAINFO"] = cert.absolutePath
        }
        if (bundle != null) {
            // Belt and braces for the node process itself: NODE_EXTRA_CA_CERTS
            // is honored by node core in every store mode (appends roots, never
            // removes), and ZCODE_AGENT_CA_CERT makes the official dist's own
            // fetch client trust exactly this bundle. Verification stays ON in
            // both paths — this widens the trusted set with a full Mozilla
            // store, it never weakens it.
            env["NODE_EXTRA_CA_CERTS"] = bundle.absolutePath
            env["ZCODE_AGENT_CA_CERT"] = bundle.absolutePath
        }
        val certsDir = File(usr, CERTS_DIR_REL)
        if (certsDir.isDirectory) {
            env["SSL_CERT_DIR"] = certsDir.absolutePath
        }
        // git's compiled-in template path also points at the Termux prefix
        // (observed on-device: "warning: templates not found in
        // /data/data/com.termux/.../git-core/templates" — commit still
        // succeeded, but init/clone should get proper templates).
        val templates = File(usr, "share/git-core/templates")
        if (templates.isDirectory) {
            env["GIT_TEMPLATE_DIR"] = templates.absolutePath
        }
        // NOTE: verification is deliberately left enabled — this function
        // never sets NODE_TLS_REJECT_UNAUTHORIZED, never empties CA paths.
    }
}
