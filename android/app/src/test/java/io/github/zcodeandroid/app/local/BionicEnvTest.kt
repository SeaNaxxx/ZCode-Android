package io.github.zcodeandroid.app.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Regression coverage for device failure 2 (Vivo V2035, Android 13, 2026-10-28):
 * the Termux-built libcrypto has OPENSSLDIR compiled in as
 * /data/data/com.termux/files/usr/etc/tls, which is unreadable for the app
 * and FATAL at node startup (exit 13). The env contract here is the fix.
 *
 * Rules verified (owner constraints):
 *  - every OpenSSL/CA variable points INSIDE the app runtime, never Termux;
 *  - OPENSSL_CONF is always set and its target always exists and is readable;
 *  - TLS certificate verification is never weakened;
 *  - CA variables are set only when their bundled targets exist.
 */
class BionicEnvTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var usrSeq = 0

    private fun makeUsr(
        withCnf: Boolean = true,
        withCert: Boolean = true,
        withModules: Boolean = true,
        withCertsDir: Boolean = false,
    ): File {
        val usr = tmp.newFolder("usr-${++usrSeq}")
        File(usr, "etc/tls").mkdirs()
        if (withCnf) {
            File(usr, "etc/tls/openssl.cnf").writeText("[openssl_init]\n# bundled\n")
        }
        if (withCert) {
            File(usr, "etc/tls/cert.pem").writeText(
                "-----BEGIN CERTIFICATE-----\nBUNDLE\n-----END CERTIFICATE-----\n"
            )
        }
        if (withModules) {
            File(usr, "lib/ossl-modules").mkdirs()
            File(usr, "lib/ossl-modules/legacy.so").writeText("module")
        }
        if (withCertsDir) {
            File(usr, "etc/tls/certs").mkdirs()
        }
        return usr
    }

    private fun apply(usr: File): MutableMap<String, String> {
        val env = mutableMapOf<String, String>()
        BionicEnv.apply(
            env,
            usr = usr,
            home = File(usr.parentFile!!, "home"),
            dataDir = File(usr.parentFile!!, "zcode-data"),
            bashBin = File(usr, "bin/bash"),
        )
        return env
    }

    @Test
    fun `openssl conf points inside runtime when bundled`() {
        val usr = makeUsr()
        val env = apply(usr)
        val conf = env["OPENSSL_CONF"]
        assertNotNull("OPENSSL_CONF must be set (device failure 2 fix)", conf)
        assertEquals(File(usr, "etc/tls/openssl.cnf").absolutePath, conf)
        assertTrue("OPENSSL_CONF target must exist", File(conf!!).isFile)
    }

    @Test
    fun `ca and module variables set only when targets exist`() {
        val withAll = apply(makeUsr(withCertsDir = true))
        assertNotNull(withAll["SSL_CERT_FILE"])
        assertNotNull(withAll["GIT_SSL_CAINFO"])
        assertNotNull(withAll["OPENSSL_MODULES"])
        assertNotNull(withAll["SSL_CERT_DIR"])

        val bare = apply(makeUsr(withCnf = false, withCert = false, withModules = false))
        assertNull("SSL_CERT_FILE must be absent without a bundled store", bare["SSL_CERT_FILE"])
        assertNull(bare["GIT_SSL_CAINFO"])
        assertNull(bare["OPENSSL_MODULES"])
        assertNull(bare["SSL_CERT_DIR"])
        // OPENSSL_CONF is ALWAYS present (fallback materialized) — an unset or
        // unreadable config target is fatal at node startup.
        assertNotNull("OPENSSL_CONF must be set even without the bundled cnf", bare["OPENSSL_CONF"])
        assertTrue(File(bare["OPENSSL_CONF"]!!).isFile)
    }

    @Test
    fun `fallback openssl cnf is materialized and readable when bundle lacks it`() {
        val usr = makeUsr(withCnf = false)
        val cnf = BionicEnv.ensureOpenSslConfig(usr)
        assertTrue(cnf.isFile)
        assertTrue(cnf.canRead())
        assertTrue(
            "fallback cnf must identify itself for on-device diagnostics",
            cnf.readText().contains("fallback OpenSSL configuration"),
        )
        // idempotent: second call returns the same file without failing
        assertEquals(cnf.absolutePath, BionicEnv.ensureOpenSslConfig(usr).absolutePath)
    }

    @Test
    fun `no env value ever references termux paths`() {
        val env = apply(makeUsr(withCertsDir = true))
        for ((k, v) in env) {
            assertFalse("env $k leaks a Termux path: $v", v.contains("com.termux"))
        }
    }

    @Test
    fun `tls verification is never disabled`() {
        val env = apply(makeUsr(withCertsDir = true))
        assertFalse(env.containsKey("NODE_TLS_REJECT_UNAUTHORIZED"))
        // The CA file, when set, must be a real non-empty certificate store.
        env["SSL_CERT_FILE"]?.let { assertTrue(File(it).length() > 0) }
    }

    @Test
    fun `core process env is anchored in the app runtime`() {
        val usr = makeUsr()
        val env = apply(usr)
        assertEquals(usr.absolutePath, env["PREFIX"])
        assertEquals("${usr.absolutePath}/lib", env["LD_LIBRARY_PATH"])
        assertTrue(env["PATH"]!!.startsWith("${usr.absolutePath}/bin"))
        assertEquals("${usr.absolutePath}/libexec/git-core", env["GIT_EXEC_PATH"])
    }
}
