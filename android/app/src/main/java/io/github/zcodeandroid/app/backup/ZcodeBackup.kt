package io.github.zcodeandroid.app.backup

import android.content.Context
import android.net.Uri
import io.github.zcodeandroid.app.local.RuntimePaths
import java.io.File
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Session/data backup + restore (migration Phase 2 item 4):
 *
 * Export: zips the runtime data directory (sessions, memory, settings) into a
 * SAF-chosen document with a schema-versioned manifest (file list + sha256 +
 * sizes). Credential material is EXCLUDED by path policy — an export must
 * never carry API keys, provider credentials or tokens (owner requirement).
 *
 * Restore: validates the manifest, verifies every entry's sha256, then
 * extracts over the data directory. Entries not in the manifest are rejected
 * (no path traversal, no surprise files).
 *
 * Honest status: implemented against app-private storage semantics; the
 * uninstall -> reinstall -> restore round-trip is the Phase 2 exit criterion
 * and still needs device verification.
 */
object ZcodeBackup {
    const val SCHEMA_VERSION = 1
    const val MANIFEST_NAME = "zcode-backup-manifest.json"

    /** Never exported: identity material and credential stores. */
    private val EXCLUDED_PATH_PREFIXES = listOf(
        ".zcode/v2/provider_config.json",
        ".zcode/v2/auth",
        ".zcode/v2/credentials",
        ".zcode/credentials",
        ".zcode/auth",
        "auth/",
        "credentials/",
    )

    private fun shouldExclude(relativePath: String): Boolean {
        val p = relativePath.replace('\\', '/')
        return EXCLUDED_PATH_PREFIXES.any { p == it || p.startsWith(it) }
    }

    private fun sha256(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        return d.joinToString("") { "%02x".format(it) }
    }

    suspend fun export(context: Context, target: Uri): Result<Int> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val dataDir = RuntimePaths.dataDir(context)
            val files = dataDir.walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(dataDir).invariantSeparatorsPath to it }
                .filter { !shouldExclude(it.first) }
                .toList()

            val manifest = JSONObject()
            manifest.put("schemaVersion", SCHEMA_VERSION)
            manifest.put("createdAt", System.currentTimeMillis())
            manifest.put("appVersion", runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "")
            val entries = org.json.JSONArray()
            for ((rel, file) in files) {
                val e = JSONObject()
                e.put("path", rel)
                e.put("size", file.length())
                e.put("sha256", sha256(file.readBytes()))
                entries.put(e)
            }
            manifest.put("files", entries)
            val manifestBytes = manifest.toString(2).toByteArray(Charsets.UTF_8)

            context.contentResolver.openOutputStream(target)?.use { rawOut ->
                BufferedOutputStream(rawOut).use { out ->
                    ZipOutputStream(out).use { zip ->
                        for ((rel, file) in files) {
                            zip.putNextEntry(ZipEntry("data/$rel"))
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                        zip.putNextEntry(ZipEntry(MANIFEST_NAME))
                        zip.write(manifestBytes)
                        zip.closeEntry()
                    }
                }
            } ?: error("cannot open export target")
            files.size
        }
    }

    suspend fun restore(context: Context, source: Uri): Result<Int> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val dataDir = RuntimePaths.dataDir(context)
            val bytesByEntry = LinkedHashMap<String, ByteArray>()
            context.contentResolver.openInputStream(source)?.use { rawIn ->
                ZipInputStream(rawIn).use { zip ->
                    var entry: ZipEntry? = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val name = entry.name
                            require(!name.contains("..")) { "unsafe backup entry: $name" }
                            bytesByEntry[name] = zip.readBytes()
                        }
                        entry = zip.nextEntry
                    }
                }
            } ?: error("cannot open backup source")

            val manifestBytes = bytesByEntry[MANIFEST_NAME]
                ?: error("not a ZCode backup (missing $MANIFEST_NAME)")
            val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
            require(manifest.optInt("schemaVersion") == SCHEMA_VERSION) {
                "backup schema version ${manifest.optInt("schemaVersion")} not supported (expected $SCHEMA_VERSION)"
            }
            val files = manifest.optJSONArray("files")
                ?: error("backup manifest missing file list")

            var restored = 0
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                val rel = f.getString("path")
                require(!rel.contains("..")) { "unsafe manifest path: $rel" }
                if (shouldExclude(rel)) continue
                val bytes = bytesByEntry["data/$rel"]
                    ?: error("backup missing declared file: $rel")
                require(sha256(bytes) == f.optString("sha256")) {
                    "integrity check failed for $rel"
                }
                val target = File(dataDir, rel)
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
                restored++
            }
            restored
        }
    }
}
