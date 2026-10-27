package com.example.zcode_mobile.local

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.FileSystemException
import java.nio.file.Files

/**
 * Extraction behavior regression coverage (device failures 1+2, Vivo V2035).
 *
 * Proven device facts encoded here:
 *  - the Vivo refuses Files.createLink (AccessDeniedException x135) -> the
 *    extractor must fall back to a budgeted copy and count honestly;
 *  - an app-API bug (java.nio.file.Path.of missing on API < 34) must FAIL
 *    with an explicit "APP DEFECT" classification, never be reported as a
 *    device refusal (that masking cost two device builds);
 *  - archive entries must never escape the destination;
 *  - linker-critical sonames ship as real files, so extraction must work
 *    with zero symlink AND zero hardlink support for the regular-file path.
 */
class TarExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val tar = ByteArrayOutputStream()

    /** Simulates the Vivo V2035: symlink() refused, link() refused. */
    private object VivoLikeOps : TarExtractor.LinkOps {
        override fun createSymbolicLink(link: File, target: String) {
            throw FileSystemException(link.path, target, "symlink refused (simulated Vivo)")
        }

        override fun createLink(link: File, existing: File) {
            throw FileSystemException(link.path, existing.path, "link refused (simulated Vivo)")
        }

        override fun isSymbolicLink(f: File): Boolean = Files.isSymbolicLink(f.toPath())
    }

    /** Simulates the 0.3.0/0.3.1 app bug: Path.of NoSuchMethodError. */
    private object PathOfBugOps : TarExtractor.LinkOps {
        override fun createSymbolicLink(link: File, target: String) {
            throw NoSuchMethodError(
                "No static method of(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;"
            )
        }

        override fun createLink(link: File, existing: File) {
            throw NoSuchMethodError("No static method of(...)Ljava/nio/file/Path;")
        }

        override fun isSymbolicLink(f: File): Boolean = Files.isSymbolicLink(f.toPath())
    }

    private fun tarWith(vararg writers: (TarArchiveOutputStream) -> Unit): ByteArray {
        val bos = ByteArrayOutputStream()
        TarArchiveOutputStream(bos).use { tos ->
            writers.forEach { it(tos) }
        }
        return bos.toByteArray()
    }

    private fun addRegular(tos: TarArchiveOutputStream, name: String, content: String, mode: Int = 420) {
        val e = TarArchiveEntry(name)
        e.mode = mode
        val bytes = content.toByteArray()
        e.size = bytes.size.toLong()
        tos.putArchiveEntry(e)
        tos.write(bytes)
        tos.closeArchiveEntry()
    }

    private fun addDir(tos: TarArchiveOutputStream, name: String) {
        tos.putArchiveEntry(TarArchiveEntry(name, TarArchiveEntry.LF_DIR))
        tos.closeArchiveEntry()
    }

    private fun addSymlink(tos: TarArchiveOutputStream, name: String, target: String) {
        val e = TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK)
        e.linkName = target
        tos.putArchiveEntry(e)
        tos.closeArchiveEntry()
    }

    private fun addHardlink(tos: TarArchiveOutputStream, name: String, target: String) {
        val e = TarArchiveEntry(name, TarArchiveEntry.LF_LINK)
        e.linkName = target
        tos.putArchiveEntry(e)
        tos.closeArchiveEntry()
    }

    // ------------------------------------------------------------- success

    @Test
    fun `regular files and symlinks extract on a normal filesystem`() {
        val bytes = tarWith({ tos ->
            addDir(tos, "usr/")
            addRegular(tos, "usr/bin/real.txt", "REAL")
            addDir(tos, "usr/lib/")
            addRegular(tos, "usr/lib/libreal.so.1.2", "LIBPAYLOAD")
            addSymlink(tos, "usr/lib/libreal.so.1", "libreal.so.1.2")
        })
        val dest = tmp.newFolder("out")
        TarExtractor(dest).extract(bytes.inputStream())

        assertTrue(File(dest, "usr/bin/real.txt").isFile)
        assertEquals("REAL", File(dest, "usr/bin/real.txt").readText())
        val link = File(dest, "usr/lib/libreal.so.1")
        assertTrue("symlink must be created", Files.isSymbolicLink(link.toPath()))
        assertEquals("LIBPAYLOAD", link.readText())
    }

    @Test
    fun `tar hardlink entries resolve via archive-member path`() {
        val bytes = tarWith({ tos ->
            addDir(tos, "usr/")
            addDir(tos, "usr/bin/")
            addRegular(tos, "usr/bin/git", "GITBIN", mode = 493)
            addDir(tos, "usr/libexec/git-core/")
            addHardlink(tos, "usr/libexec/git-core/git-status", "usr/bin/git")
        })
        val dest = tmp.newFolder("out")
        val report = TarExtractor.ExtractReport()
        TarExtractor(dest, report).extract(bytes.inputStream())

        val extracted = File(dest, "usr/libexec/git-core/git-status")
        assertTrue(extracted.isFile)
        assertEquals("GITBIN", extracted.readText())
        assertEquals(1, report.hardlinkEntries)
        assertEquals(1, report.hardlinkCreated)
    }

    // --------------------------------------------------- device-refusal path

    @Test
    fun `symlink refusal falls back to hardlink then budgeted copy`() {
        val bytes = tarWith({ tos ->
            addDir(tos, "usr/")
            addDir(tos, "usr/lib/")
            addRegular(tos, "usr/lib/libreal.so.1.2", "LIBPAYLOAD")
            addSymlink(tos, "usr/lib/libreal.so.1", "libreal.so.1.2")
        })
        val dest = tmp.newFolder("out")
        val report = TarExtractor.ExtractReport()
        TarExtractor(dest, report, VivoLikeOps).extract(bytes.inputStream())

        // Both link syscalls refused on the simulated device — the content
        // must still arrive via the budgeted copy, and the report must say so.
        assertEquals("LIBPAYLOAD", File(dest, "usr/lib/libreal.so.1").readText())
        assertEquals(1, report.symlinkFsFailures)
        assertTrue(report.fallbackBytes > 0)
        assertTrue(
            "firstError must carry the REAL device exception, not an app bug",
            report.firstError!!.contains("simulated Vivo"),
        )
    }

    @Test
    fun `copy fallback is budget-capped and counted as absent`() {
        val bytes = tarWith({ tos ->
            addDir(tos, "usr/")
            addDir(tos, "usr/lib/")
            addRegular(tos, "usr/lib/big.bin", "X".repeat(1024))
            addSymlink(tos, "usr/lib/big-link", "big.bin")
        })
        val dest = tmp.newFolder("out")
        val report = TarExtractor.ExtractReport()
        TarExtractor(dest, report, VivoLikeOps, copyBudgetBytes = 0).extract(bytes.inputStream())

        assertFalse(File(dest, "usr/lib/big-link").exists())
        assertEquals(1, report.hardlinkCopySkipped)
    }

    // ------------------------------------------------------ app-API-bug path

    @Test
    fun `nosuchmethoderror fails loudly as APP DEFECT - never as device refusal`() {
        val bytes = tarWith({ tos ->
            addDir(tos, "usr/")
            addDir(tos, "usr/lib/")
            addRegular(tos, "usr/lib/libreal.so.1.2", "LIBPAYLOAD")
            addSymlink(tos, "usr/lib/libreal.so.1", "libreal.so.1.2")
        })
        val dest = tmp.newFolder("out")
        val report = TarExtractor.ExtractReport()
        val err = runCatching {
            TarExtractor(dest, report, PathOfBugOps).extract(bytes.inputStream())
        }.exceptionOrNull()
        assertTrue("extraction must fail", err is java.io.IOException)
        assertTrue(
            "message must classify the app defect: ${err?.message}",
            err!!.message!!.contains("APP DEFECT"),
        )
        assertFalse(
            "must NOT masquerade as device refusal",
            err.message!!.contains("device refused"),
        )
    }

    // -------------------------------------------------------- path traversal

    @Test
    fun `entry escaping the destination is rejected`() {
        val bytes = tarWith({ tos ->
            addRegular(tos, "../evil.txt", "EVIL")
        })
        val dest = tmp.newFolder("out")
        val err = runCatching {
            TarExtractor(dest).extract(bytes.inputStream())
        }.exceptionOrNull()
        assertTrue(err is java.io.IOException)
        assertTrue(err!!.message!!.contains("escapes destination"))
        assertFalse(File(dest.parentFile, "evil.txt").exists())
    }

    @Test
    fun `hardlink target escaping the destination is never materialized`() {
        val bytes = tarWith({ tos ->
            addDir(tos, "usr/")
            addRegular(tos, "usr/bin/real.txt", "REAL")
            addHardlink(tos, "usr/bin/alias", "../../../outside")
        })
        val dest = tmp.newFolder("out")
        val report = TarExtractor.ExtractReport()
        TarExtractor(dest, report).extract(bytes.inputStream())

        assertFalse(File(dest, "usr/bin/alias").exists())
        assertEquals(1, report.absentEntries)
    }

    @Test
    fun `absolute link names are never materialized`() {
        val bytes = tarWith({ tos ->
            addDir(tos, "usr/")
            addDir(tos, "usr/bin/")
            addRegular(tos, "usr/bin/real.txt", "REAL")
            addHardlink(tos, "usr/bin/alias", "/etc/passwd")
        })
        val dest = tmp.newFolder("out")
        val report = TarExtractor.ExtractReport()
        TarExtractor(dest, report).extract(bytes.inputStream())
        assertFalse(File(dest, "usr/bin/alias").exists())
    }
}
