package ma7moud3ly.fast.compose.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProgressTest {

    @Test
    fun showsCountTimesSpeedAndItem() {
        val progress = Progress("Rendering", 4, out = PrintStream(ByteArrayOutputStream()))
        progress.step()
        val line = progress.line("Card.kt · Dark", elapsedSeconds = 2.0)
        assertTrue(line.startsWith("Rendering  25%|"), line)
        assertTrue(line.endsWith("| 1/4 [00:02<00:06, 0.5 previews/s] Card.kt · Dark"), line)
    }

    @Test
    fun showsUnknownSpeedBeforeTheFirstItem() {
        val line = Progress("Listing", 3, unit = "files", out = PrintStream(ByteArrayOutputStream())).line("A.kt", 1.0)
        assertTrue(line.endsWith("| 0/3 [00:01<?, ? files/s] A.kt"), line)
    }

    @Test
    fun stopsAtTheTotal() {
        val progress = Progress("Checking", 1, out = PrintStream(ByteArrayOutputStream()))
        repeat(3) { progress.step() }
        assertTrue("100%|" in progress.line("", 1.0))
    }

    @Test
    fun clearsItsLineWhenFinished() {
        val bytes = ByteArrayOutputStream()
        val progress = Progress("Rendering", 2, out = PrintStream(bytes, true, "UTF-8"))
        progress.start("Card.kt · Light")
        progress.finish()
        val text = bytes.toString("UTF-8")
        assertTrue(text.endsWith("\r"), text)
        assertTrue(text.substringAfterLast("Card.kt · Light").trim('\r', ' ').isEmpty(), text)
    }

    @Test
    fun formatsTimes() {
        assertEquals("00:07", Progress.time(7.9))
        assertEquals("02:05", Progress.time(125.0))
        assertEquals("1:00:01", Progress.time(3601.0))
    }

    @Test
    fun parsesNoProgress() {
        assertTrue(Options.parse(listOf("render")).progress)
        assertFalse(Options.parse(listOf("render", "--no-progress")).progress)
    }
}
