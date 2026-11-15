package ma7moud3ly.fast.compose.cli

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OptionsTest {

    @Test
    fun parsesPathsAndOptionsInAnyOrder() {
        val options = Options.parse(
            listOf("render", "--dark", "Card.kt", "-o", "out", "--size=tablet", "ui", "--font-scale", "1.5", "--json"),
        )
        assertEquals("render", options.command)
        assertEquals(listOf(File("Card.kt"), File("ui")), options.paths)
        assertEquals(File("out"), options.output)
        assertEquals(DeviceSize(1280, 800), options.render.device)
        assertEquals(true, options.render.dark)
        assertEquals(1.5f, options.render.fontScale)
        assertTrue(options.json)
    }

    @Test
    fun parsesProjectSettings() {
        val options = Options.parse(listOf("check", "A.kt", "--no-sources", "--res", "r1", "--res", "r2", "--source-root", "s"))
        assertFalse(options.project.sources)
        assertEquals(listOf(File("r1"), File("r2")), options.project.resourceFolders)
        assertEquals(listOf(File("s")), options.project.sourceRoots)
    }

    @Test
    fun readsClasspathFromFiles() {
        val folder = createTempDirectory().toFile()
        val list = File(folder, "classpath.txt")
        list.writeText("a.jar\n\nb.jar${File.pathSeparator}c.jar\n")
        val classpath = Options.classpath("x.jar${File.pathSeparator}@${list.path}")
        assertEquals(listOf("x.jar", "a.jar", "b.jar", "c.jar"), classpath.map { it.path })
    }

    @Test
    fun parsesSizes() {
        assertEquals(DeviceSize(360, 640), DeviceSize.parse("360x640"))
        assertEquals(DeviceSize.Phone, DeviceSize.parse("Phone"))
        assertFailsWith<UsageError> { DeviceSize.parse("360") }
        assertFailsWith<UsageError> { DeviceSize.parse("0x10") }
    }

    @Test
    fun rejectsWrongCommandLines() {
        assertFailsWith<UsageError> { Options.parse(emptyList()) }
        assertFailsWith<UsageError> { Options.parse(listOf("check", "--unknown")) }
        assertFailsWith<UsageError> { Options.parse(listOf("render", "-o")) }
        assertFailsWith<UsageError> { Options.parse(listOf("render", "--density", "-1")) }
    }
}
