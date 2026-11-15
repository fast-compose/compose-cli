package ma7moud3ly.fast.compose.cli

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PathsTest {

    private val preview = "@Preview @Composable fun A() { Text(\"a\") }"

    private fun project(): File = createTempDirectory().toFile().apply {
        resolve("ui").mkdirs()
        resolve("ui/HomeScreen.kt").writeText(preview)
        resolve("ui/Theme.kt").writeText("val Primary = 1")
        resolve("ui/detail").mkdirs()
        resolve("ui/detail/DetailScreen.kt").writeText(preview)
        resolve("build/generated").mkdirs()
        resolve("build/generated/Gen.kt").writeText(preview)
        resolve("Notes.txt").writeText(preview)
    }

    private fun names(files: List<File>) = files.map { it.name }

    @Test
    fun foldersTakeOnlyTheirOwnFiles() {
        val root = project()
        assertEquals(listOf("HomeScreen.kt"), names(kotlinFiles(listOf(root.resolve("ui")))))
        assertEquals(emptyList(), kotlinFiles(listOf(root)))
    }

    @Test
    fun recursiveFoldersTakeFilesWithPreviewsOutsideBuild() {
        val root = project()
        assertEquals(
            listOf("DetailScreen.kt", "HomeScreen.kt"),
            names(kotlinFiles(listOf(root), recursive = true)).sorted(),
        )
    }

    @Test
    fun globsMatchWithinAndAcrossFolders() {
        val root = project().invariantSeparatorsPath
        assertEquals(listOf("HomeScreen.kt"), names(kotlinFiles(listOf(File("$root/ui/*.kt")))))
        assertEquals(
            listOf("DetailScreen.kt", "HomeScreen.kt"),
            names(kotlinFiles(listOf(File("$root/**/*Screen.kt")))).sorted(),
        )
    }

    @Test
    fun filesAreTakenAsGivenWhenKotlin() {
        val root = project()
        assertEquals(listOf("Theme.kt"), names(kotlinFiles(listOf(root.resolve("ui/Theme.kt")))))
        assertEquals(emptyList(), kotlinFiles(listOf(root.resolve("Notes.txt"))))
    }

    @Test
    fun printsPathsRelativeToTheCurrentFolder() {
        val inside = File("app/src/Card.kt").absoluteFile.invariantSeparatorsPath
        assertEquals("app/src/Card.kt", relativePath(inside))
        val outside = File("..", "other/Theme.kt").absoluteFile.normalize().invariantSeparatorsPath
        assertEquals("../other/Theme.kt", relativePath(outside))
    }

    @Test
    fun convertsGlobsToRegexes() {
        assertTrue(globRegex("*.kt").matches("Card.kt"))
        assertFalse(globRegex("*.kt").matches("ui/Card.kt"))
        assertTrue(globRegex("**/*.kt").matches("Card.kt"))
        assertTrue(globRegex("**/*.kt").matches("a/b/Card.kt"))
        assertTrue(globRegex("Card?.kt").matches("Card2.kt"))
        assertFalse(globRegex("Card.kt").matches("CardXkt"))
    }

    @Test
    fun parsesIgnoreWarnings() {
        assertTrue(Options.parse(listOf("check", "--ignore-warnings")).ignoreWarnings)
        assertTrue(Options.parse(listOf("check", "-r")).recursive)
        assertEquals(emptyList(), Options.parse(listOf("render")).paths)
    }
}
