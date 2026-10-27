package com.example.zcode_mobile.local

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

/**
 * Static regression guard for the exact defect that broke the Vivo V2035
 * device tests twice: java.nio.file.Path.of(...) requires Android API 34,
 * the acceptance device runs Android 13 (API 33). JVM unit tests CANNOT
 * catch this via execution (the desktop JVM implements Path.of), so this
 * test enforces it at the source level. Paths.get (API 26+) is the required
 * replacement.
 *
 * Comments are stripped before matching: documenting the historical bug is
 * legitimate; using the API is not.
 */
class ApiCompatSourceTest {

    private val forbidden = listOf(
        // API 34+ factories — NoSuchMethodError on the Android 13 device.
        Pattern.compile("\\bPath\\.of\\s*\\("),
        Pattern.compile("\\bPaths\\.of\\s*\\("),
    )

    private val blockComment = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL)
    private val lineComment = Pattern.compile("//[^\n]*")

    /** Removes /* */ and // comments (string-literal "//" is irrelevant here:
     *  the forbidden API names never legitimately appear inside strings). */
    private fun stripComments(text: String): String =
        lineComment.matcher(blockComment.matcher(text).replaceAll("")).replaceAll("")

    private fun locateSourceRoot(): File? {
        var dir = File(System.getProperty("user.dir")!!)
        repeat(6) {
            val candidate = File(dir, "src/main/java/com/example/zcode_mobile")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return null
        }
        return null
    }

    @Test
    fun `no API-34-only Path factories in main sources`() {
        val root = locateSourceRoot()
        assertTrue(
            "source root not found from ${System.getProperty("user.dir")}",
            root != null,
        )
        val violations = mutableListOf<String>()
        root!!.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val code = stripComments(f.readText())
            for (p in forbidden) {
                val m = p.matcher(code)
                while (m.find()) {
                    violations.add(
                        "${f.relativeTo(root).path}: ...${
                            code.substring(
                                (m.start() - 40).coerceAtLeast(0),
                                (m.end() + 40).coerceAtMost(code.length),
                            )
                        }..."
                    )
                }
            }
        }
        assertTrue(
            "API-34-only java.nio.file factories found (device-failure-2 regression):\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }
}
