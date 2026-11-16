package ma7moud3ly.fast.compose.cli

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ma7moud3ly.fast.compose.project.DiskFiles

class ModulesTest {

    private val services = mutableListOf<PreviewService>()

    @AfterTest
    fun close() = services.forEach { it.close() }

    /** A project with a `core` module (a composable and a string) and an `app` module whose screen uses both. */
    private fun project(): File = createTempDirectory().toFile().apply {
        resolve("settings.gradle.kts").writeText("include(\":app\", \":core\")")
        resolve("core/src/main/kotlin/core/ui").mkdirs()
        resolve("core/src/main/kotlin/core/ui/Title.kt").writeText(
            """
            package core.ui

            @Composable
            fun Title(text: String) {
                Text(text)
            }
            """.trimIndent(),
        )
        resolve("core/src/main/res/values").mkdirs()
        resolve("core/src/main/res/values/strings.xml").writeText(
            """<resources><string name="subtitle">Hello</string></resources>""",
        )
        resolve("app/src/main/kotlin/app").mkdirs()
        resolve("app/src/main/kotlin/app/Screen.kt").writeText(
            """
            package app

            import core.ui.Title

            @Preview
            @Composable
            fun ScreenPreview() {
                Column {
                    Title("Home")
                    Text(stringResource(R.string.subtitle))
                }
            }
            """.trimIndent(),
        )
        resolve("app/build/generated/src/main/kotlin").mkdirs()
    }

    private fun check(root: File, modules: Boolean): FileReport {
        val service = PreviewService(ProjectSettings(modules = modules)).also { services += it }
        return service.run(SourceText.read(root.resolve("app/src/main/kotlin/app/Screen.kt")), Mode.CHECK)
    }

    @Test
    fun findsTheProjectRoot() {
        val root = project()
        assertEquals(root.absoluteFile, GradleModules.rootOf(root.resolve("app/src/main/kotlin/app/Screen.kt")))
        assertNull(GradleModules.rootOf(createTempDirectory().toFile().resolve("Lone.kt")))
    }

    @Test
    fun listsEveryModulesSharedSourceSets() {
        val root = project()
        val folders = GradleModules.scan(root)
        assertEquals(
            listOf("app/src/main/kotlin", "core/src/main/kotlin"),
            folders.sourceRoots.map { it.removePrefix(DiskFiles.pathOf(root) + "/") },
        )
        assertEquals(listOf("core/src/main/res"), folders.resourceFolders.map { it.removePrefix(DiskFiles.pathOf(root) + "/") })
    }

    @Test
    fun readsOtherModulesOnlyWithModules() {
        val root = project()
        assertTrue(check(root, modules = false).problems.isNotEmpty())
        assertEquals(emptyList(), check(root, modules = true).problems)
    }

    @Test
    fun parsesModules() {
        assertTrue(Options.parse(listOf("render", "-m")).project.modules)
        assertTrue(Options.parse(listOf("render", "--modules")).project.modules)
    }
}
