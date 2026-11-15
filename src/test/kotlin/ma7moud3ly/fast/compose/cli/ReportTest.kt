package ma7moud3ly.fast.compose.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import ma7moud3ly.fast.compose.ui.LogLine

class ReportTest {

    @Test
    fun takesLocationsOutOfLogMessages() {
        val main = Problem.of(LogLine("Line 12: Unresolved reference 'Txt'", isError = true, line = 12), "/p/Card.kt", "Card")
        assertEquals(Problem(Severity.ERROR, "Unresolved reference 'Txt'", "/p/Card.kt", 12, preview = "Card"), main)

        val other = Problem.of(LogLine("Theme.kt:3: Expected ')'", isError = true, line = 3, file = "/p/Theme.kt"), "/p/Card.kt", null)
        assertEquals(Problem(Severity.ERROR, "Expected ')'", "/p/Theme.kt", 3), other)
    }

    @Test
    fun outputIsNoProblem() {
        assertNull(Problem.of(LogLine("hello"), "/p/Card.kt", null))
    }

    @Test
    fun formatsProblemsOnOneLine() {
        val problem = Problem(Severity.WARNING, "Stand-in used", "/p/ui/Card.kt", 4, 9, "Card")
        assertEquals("warning  Card.kt:4:9  Stand-in used  [Card]", problemText(problem))
    }

    @Test
    fun quietOutputLeavesOutProblemsButCountsThem() {
        val report = FileReport(
            path = "/p/Card.kt",
            previews = emptyList(),
            problems = listOf(Problem(Severity.ERROR, "Unresolved reference 'Txt'", "/p/Card.kt", 3)),
            output = listOf("hello"),
        )
        val text = toText(listOf(report), showPreviews = false, details = false)
        assertEquals("/p/Card.kt\n1 file(s), 1 error(s), 0 warning(s)", text)

        val json = toJson(listOf(report), details = false)
        assertEquals("[]", json["files"]!!.jsonArray[0].jsonObject["problems"].toString())
        assertEquals("[]", json["files"]!!.jsonArray[0].jsonObject["output"].toString())
        assertEquals("1", json["errors"].toString())
    }

    @Test
    fun parsesQuiet() {
        assertTrue(Options.parse(listOf("render", "-q")).quiet)
        assertTrue(Options.parse(listOf("render", "--quiet")).quiet)
    }

    @Test
    fun makesSafeFileNames() {
        assertEquals("Dark_mode__large", safeName("Dark mode, large"))
        assertEquals("preview", safeName(""))
    }
}
