package io.github.zcodeandroid.app.local

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Pure-JVM tar extraction for the embedded Bionic bundle.
 *
 * DEVICE FAILURE 1+2 LESSONS (Vivo V2035, Android 13, 2026-10-27/28):
 *  1. Symlink creation failed on the device ONLY because this code path used
 *     java.nio.file.Path.of(...) — unavailable before API 34 — and the
 *     NoSuchMethodError was swallowed as a "device refusal". Lesson: an app
 *     API bug must FAIL THE PROVISIONING loudly, never masquerade as device
 *     behavior. [isAppApiBug] + fail-fast below institutionalize this.
 *  2. The device genuinely refuses Files.createLink (AccessDeniedException,
 *     135x) — so the extraction must never NEED hard links: the builder now
 *     ships linker-critical sonames as real files and converts duplicate
 *     content to relative SYMLINKS. The link()/copy fallback chain is kept
 *     as defense in depth and is budget-capped.
 *  3. Hardlink/symlink linkName resolution: entries may reference their
 *     target as a tar-member path (relative to the archive root) or as a
 *     path relative to the entry's own directory — both are resolved and
 *     both are confined under the destination (path-traversal guard).
 *
 * No android.* imports: the exact behavior is unit-tested on the JVM with an
 * injected [LinkOps] (default [DefaultLinkOps] uses java.nio.file).
 */
class TarExtractor(
    private val dest: File,
    private val report: ExtractReport = ExtractReport(),
    private val linkOps: LinkOps = DefaultLinkOps,
    private val copyBudgetBytes: Long = DEFAULT_COPY_BUDGET_BYTES,
) {

    /** Link/directory primitives, injectable for JVM tests. */
    interface LinkOps {
        fun createSymbolicLink(link: File, target: String)
        fun createLink(link: File, existing: File)
        fun isSymbolicLink(f: File): Boolean
    }

    object DefaultLinkOps : LinkOps {
        override fun createSymbolicLink(link: File, target: String) {
            Files.createSymbolicLink(link.toPath(), Paths.get(target))
        }

        override fun createLink(link: File, existing: File) {
            Files.createLink(link.toPath(), existing.toPath())
        }

        override fun isSymbolicLink(f: File): Boolean = Files.isSymbolicLink(f.toPath())
    }

    class ExtractReport {
        var regularFiles = 0
        var symlinksCreated = 0
        var symlinkFsFailures = 0
        var symlinkFallbackHardlinks = 0
        var symlinkFallbackCopies = 0
        var hardlinkEntries = 0
        var hardlinkCreated = 0
        var hardlinkCopies = 0
        var hardlinkCopySkipped = 0
        var fallbackBytes = 0L
        var absentEntries = 0
        var firstError: String? = null

        val fallbackCopyBytes: Long get() = fallbackBytes
    }

    /** API-level bugs must fail loudly, never be reported as device refusals. */
    fun isAppApiBug(e: Throwable): Boolean {
        if (e is NoSuchMethodError || e is NoSuchMethodException) return true
        if (e is UnsupportedOperationException &&
            (e.cause is NoSuchMethodError || e.cause is NoSuchMethodException)
        ) return true
        return false
    }

    fun extract(raw: InputStream) {
        TarArchiveInputStream(raw.buffered(1 shl 16)).use { tis ->
            val destCanon = dest.canonicalPath + File.separator
            while (true) {
                val entry: TarArchiveEntry = tis.nextTarEntry ?: break
                val target = File(dest, entry.name)
                if (!isInside(dest, target)) {
                    throw IOException("archive entry escapes destination: ${entry.name}")
                }
                when {
                    entry.isDirectory -> target.mkdirs()
                    entry.isSymbolicLink -> extractSymlink(entry, target)
                    entry.isLink -> extractHardlink(entry, target)
                    else -> {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { tis.copyTo(it) }
                        applyMode(target, entry.mode)
                        report.regularFiles++
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------- symlinks

    private fun extractSymlink(entry: TarArchiveEntry, target: File) {
        target.parentFile?.mkdirs()
        if (target.exists() && !linkOps.isSymbolicLink(target)) target.delete()
        try {
            linkOps.createSymbolicLink(target, entry.linkName)
            report.symlinksCreated++
        } catch (e: Throwable) {
            if (isAppApiBug(e)) {
                // Never mask an app defect as "device refused" (failure-2 lesson).
                throw IOException(
                    "APP DEFECT (not a device refusal): symlink creation used an API " +
                        "missing on this Android level: ${e.javaClass.name}: ${e.message}", e
                )
            }
            report.symlinkFsFailures++
            if (report.firstError == null) {
                report.firstError = "${e.javaClass.simpleName}: ${e.message}"
            }
            if (report.symlinkFsFailures <= 3) {
                log("symlink FAILED: ${entry.name} -> ${entry.linkName} " +
                    "(${e.javaClass.simpleName}: ${e.message})")
            }
            fallbackSymlink(entry, target, e)
        }
    }

    /** symlink failed -> hardlink to the resolved target -> budgeted copy. */
    private fun fallbackSymlink(entry: TarArchiveEntry, target: File, cause: Throwable) {
        val resolved = resolveUnderDest(entry, entry.linkName)
        if (resolved == null || !resolved.isFile) {
            report.absentEntries++
            if (report.absentEntries <= 3) {
                log("symlink target absent for ${entry.name} -> ${entry.linkName} (left out)")
            }
            return
        }
        try {
            linkOps.createLink(target, resolved)
            report.symlinkFallbackHardlinks++
            return
        } catch (e: Throwable) {
            if (isAppApiBug(e)) {
                throw IOException(
                    "APP DEFECT (not a device refusal): hard-link creation used an API " +
                        "missing on this Android level: ${e.javaClass.name}: ${e.message}", e
                )
            }
        }
        if (report.fallbackBytes + resolved.length() <= copyBudgetBytes) {
            val ok = runCatching { resolved.copyTo(target, overwrite = true) }
            if (ok.isSuccess) {
                applyMode(target, entry.mode)
                report.symlinkFallbackCopies++
                report.fallbackBytes += resolved.length()
                return
            }
        } else {
            report.hardlinkCopySkipped++
            if (report.hardlinkCopySkipped <= 3) {
                log("copy fallback SKIPPED (budget): ${entry.name}")
            }
            report.absentEntries++
            return
        }
        report.absentEntries++
        if (report.absentEntries <= 3) {
            log("symlink fallback exhausted for ${entry.name} (${cause.javaClass.simpleName})")
        }
    }

    // ------------------------------------------------------------ hardlinks

    private fun extractHardlink(entry: TarArchiveEntry, target: File) {
        report.hardlinkEntries++
        target.parentFile?.mkdirs()
        val resolved = resolveUnderDest(entry, entry.linkName)
        if (resolved == null || !resolved.isFile) {
            // Devices proven to refuse link() cannot materialize this entry at
            // all; count it absent rather than fabricating a file.
            report.absentEntries++
            if (report.absentEntries <= 3) {
                log("hardlink target not found in archive: ${entry.name} -> ${entry.linkName}")
            }
            return
        }
        try {
            linkOps.createLink(target, resolved)
            report.hardlinkCreated++
            return
        } catch (e: Throwable) {
            if (isAppApiBug(e)) {
                throw IOException(
                    "APP DEFECT (not a device refusal): hard-link creation used an API " +
                        "missing on this Android level: ${e.javaClass.name}: ${e.message}", e
                )
            }
            if (report.firstError == null) {
                report.firstError = "${e.javaClass.simpleName}: ${e.message}"
            }
        }
        if (report.fallbackBytes + resolved.length() <= copyBudgetBytes) {
            val ok = runCatching { resolved.copyTo(target, overwrite = true) }
            if (ok.isSuccess) {
                applyMode(target, entry.mode)
                report.hardlinkCopies++
                report.fallbackBytes += resolved.length()
                if (report.hardlinkCopies <= 3) {
                    log("hardlink fallback copy: ${entry.name} " +
                        "(${resolved.length() / (1 shl 10)}KiB)")
                }
                return
            }
        } else {
            report.hardlinkCopySkipped++
            if (report.hardlinkCopySkipped <= 3) {
                log("hardlink fallback SKIPPED (budget): ${entry.name}")
            }
            report.absentEntries++
            return
        }
        report.absentEntries++
    }

    /**
     * Resolves an entry's link name under [dest]. Accepts tar-member paths
     * ("usr/bin/git") and entry-parent-relative paths ("../../bin/git");
     * returns null when the resolution escapes the destination or does not
     * exist (never fabricate files outside the runtime).
     */
    private fun resolveUnderDest(entry: TarArchiveEntry, linkName: String): File? {
        if (linkName.isBlank() || linkName.startsWith("/")) return null
        val asMember = canonicalUnderDest(File(dest, linkName)) ?: return null
        if (asMember.isFile) return asMember
        val parent = File(dest, entry.name).parentFile ?: return null
        val asRelative = canonicalUnderDest(File(parent, linkName)) ?: return null
        return if (asRelative.isFile) asRelative else null
    }

    /** Canonicalizes [f] and confines it under [dest]; null when it escapes. */
    private fun canonicalUnderDest(f: File): File? {
        val c = try {
            f.canonicalFile
        } catch (_: IOException) {
            return null
        }
        val destCanon = dest.canonicalPath + File.separator
        return if (c.canonicalPath == dest.canonicalPath || c.canonicalPath.startsWith(destCanon)) c else null
    }

    private fun isInside(dest: File, target: File): Boolean {
        val c = try {
            target.canonicalPath
        } catch (_: IOException) {
            return false
        }
        val destCanon = dest.canonicalPath + File.separator
        return c.startsWith(destCanon) || c == dest.canonicalPath
    }

    private fun applyMode(f: File, mode: Int) {
        val user = (mode shr 6) and 7
        f.setReadable(true)
        if (user and 4 != 0) f.setReadable(true, false) else f.setReadable(true, true)
        if (user and 2 != 0) f.setWritable(true)
        if (user and 1 != 0) f.setExecutable(true, false)
    }

    private fun log(line: String) {
        RuntimeBus.append(line)
    }

    companion object {
        /** Worst-case on-device copy fallback budget (failure-1/2 evidence). */
        const val DEFAULT_COPY_BUDGET_BYTES: Long = 200L * 1024 * 1024
    }
}
