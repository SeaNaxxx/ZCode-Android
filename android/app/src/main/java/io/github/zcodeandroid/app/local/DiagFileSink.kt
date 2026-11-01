package io.github.zcodeandroid.app.local

import java.io.File

/**
 * One append-only, size-capped evidence file, shared by every persisted log
 * sink (webconsole.log, server.log). Rotation keeps exactly one previous
 * generation (`.old`): enough to answer "what happened before the restart"
 * without unbounded growth.
 *
 * Pure java.io so the behavior is unit-testable on the CI JVM. Never throws:
 * evidence must not be able to crash the runtime that produces it.
 */
class DiagFileSink(private val file: File, private val maxBytes: Long) {

    private val lock = Any()

    init {
        runCatching { file.parentFile?.mkdirs() }
    }

    fun append(line: String) {
        try {
            synchronized(lock) {
                if (file.length() > maxBytes) {
                    val old = File(file.parentFile, file.name + ".old")
                    if (old.exists()) old.delete()
                    file.renameTo(old)
                }
                file.appendText("$line\n")
            }
        } catch (_: Throwable) {
            // Drop the line instead of crashing the producer.
        }
    }

    /** Last persisted lines across both generations (oldest first). */
    fun tail(maxLines: Int): List<String> = runCatching {
        if (!file.isFile) return@runCatching emptyList()
        val lines = file.readLines()
        val old = File(file.parentFile, file.name + ".old")
        if (old.isFile && lines.size < maxLines) {
            (old.readLines() + lines).takeLast(maxLines)
        } else {
            lines.takeLast(maxLines)
        }
    }.getOrDefault(emptyList())

    fun sizeBytes(): Long = runCatching { if (file.isFile) file.length() else 0L }.getOrDefault(0L)
}
