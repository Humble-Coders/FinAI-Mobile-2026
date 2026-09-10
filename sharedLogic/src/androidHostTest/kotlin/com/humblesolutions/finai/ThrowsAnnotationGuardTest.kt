package com.humblesolutions.finai

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Fails the build when a public shared suspend function lacks
 * `@Throws(..., CancellationException::class)`.
 *
 * From Swift, an undeclared Kotlin exception terminates the process instead of
 * surfacing as an error (kmp-arch-v2 → SKIE). This is stricter than "functions
 * containing require/check/error/throw": a network call throws without any of
 * those words appearing, so every public suspend function must declare it.
 *
 * Lives in androidHostTest rather than jvmTest: sharedLogic has no JVM target,
 * and the Android target's host tests already run on the JVM.
 */
class ThrowsAnnotationGuardTest {

    @Test
    fun `every public suspend function in shared code declares Throws with CancellationException`() {
        val root = File("src/commonMain/kotlin")
        assertTrue(root.isDirectory, "commonMain sources not found at ${root.absolutePath}; the scan would pass without checking anything")

        val checked = mutableListOf<String>()
        val violations = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val source = withoutComments(file.readText())
            for (match in DECLARATION.findAll(source)) {
                val (annotations, before, after, name) = match.destructured
                if (HIDDEN.containsMatchIn(before) || HIDDEN.containsMatchIn(after)) continue
                val where = "${file.relativeTo(root)}:${lineOf(source, match.range.first + annotations.length)} $name"
                checked += where
                if ("@Throws(" !in annotations || "CancellationException" !in annotations) violations += where
            }
        }

        assertTrue(checked.isNotEmpty(), "no public suspend functions found under ${root.path}; the scan is not seeing the sources")
        assertTrue(
            violations.isEmpty(),
            "Public suspend functions without @Throws(..., CancellationException::class) — " +
                "from Swift, any exception they raise terminates the process:\n" + violations.joinToString("\n"),
        )
    }

    private companion object {
        val DECLARATION = Regex(
            """((?:@[\w.]+(?:\((?:[^()]|\([^()]*\))*\))?\s+)*)""" +
                """((?:(?:public|internal|private|protected|override|open|abstract|final|actual|inline|operator|infix|tailrec)\s+)*)""" +
                """suspend\s+((?:(?:inline|operator|infix|tailrec)\s+)*)fun\b\s*(?:<[^>]*>\s*)?([\w.]+)""",
        )
        val HIDDEN = Regex("""\b(private|internal|protected)\b""")
        val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
        val LINE_COMMENT = Regex("""//[^\n]*""")
        val NOT_NEWLINE = Regex("[^\n]")

        fun withoutComments(text: String): String {
            val noBlocks = BLOCK_COMMENT.replace(text) { it.value.replace(NOT_NEWLINE, " ") }
            return LINE_COMMENT.replace(noBlocks) { " ".repeat(it.value.length) }
        }

        fun lineOf(text: String, index: Int): Int = text.substring(0, index).count { it == '\n' } + 1
    }
}
