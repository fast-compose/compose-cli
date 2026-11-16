package ma7moud3ly.fast.compose.cli

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PreviewServiceTest {

    private val service = PreviewService(ProjectSettings())

    @AfterTest
    fun close() = service.close()

    @Test
    fun listsPreviewsWithTheirSettings() {
        val file = SourceText.of(
            """
            @Preview(name = "Light")
            @Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 200)
            @Composable
            fun CardPreview() {
                Text("Hello")
            }
            """.trimIndent(),
            null,
        )
        val report = service.run(file, Mode.LIST)
        assertEquals(listOf("Light", "Dark"), report.previews.map { it.name })
        assertEquals(listOf("CardPreview", "CardPreview"), report.previews.map { it.function })
        assertEquals(listOf(false, true), report.previews.map { it.config.darkTheme })
        assertEquals(200, report.previews[1].config.widthDp)
        assertTrue(report.problems.isEmpty())
    }

    @Test
    fun rendersOneImagePerPreviewParameterValue() {
        val file = SourceText.of(
            """
            class NamesProvider : PreviewParameterProvider<String> {
                override val values = sequenceOf("Ann", "Bob")
            }

            @Preview(widthDp = 80, heightDp = 40)
            @Composable
            fun Greeting(@PreviewParameter(NamesProvider::class) name: String) {
                Text("Hi " + name)
            }
            """.trimIndent(),
            null,
        )
        val report = service.run(file, Mode.RENDER, settings = RenderSettings(density = 1f, waitMs = 0))
        assertEquals(2, report.previews.size)
        assertTrue(report.previews.all { it.hasArguments })
        assertEquals(emptyList(), report.problems.filter { it.severity == Severity.ERROR })
        assertEquals(2, report.images.size)
    }

    @Test
    fun runsKotlinxDatetimeWithoutAClasspath() {
        assertTrue(BundledLibraries.jars.isNotEmpty())
        val file = SourceText.of(
            """
            import kotlinx.datetime.TimeZone
            import kotlinx.datetime.toJavaZoneId

            @Preview
            @Composable
            fun Zone() {
                Text(TimeZone.UTC.toJavaZoneId().toString())
            }
            """.trimIndent(),
            null,
        )
        val report = service.run(file, Mode.CHECK)
        assertEquals(emptyList(), report.problems)
    }

    @Test
    fun findsMultiPreviewAnnotations() {
        val file = SourceText.of(
            """
            @Preview(name = "Small", widthDp = 100)
            @Preview(name = "Large", widthDp = 300)
            annotation class Sizes

            @Sizes
            @Composable
            fun Card() {
                Text("Card")
            }
            """.trimIndent(),
            null,
        )
        val report = service.run(file, Mode.LIST)
        assertEquals(listOf(100, 300), report.previews.map { it.config.widthDp })
        assertTrue(report.previews.all { it.function == "Card" })
    }

    @Test
    fun reportsParseErrors() {
        val report = service.run(SourceText.of("@Preview @Composable fun A() { Text(\"a\" }", null), Mode.CHECK)
        assertTrue(report.errors > 0)
        assertTrue(report.problems.all { it.line != null })
    }

    @Test
    fun findsRuntimeErrors() {
        val file = SourceText.of(
            """
            @Preview
            @Composable
            fun Greeting() {
                Column {
                    Txt("Hello")
                }
            }
            """.trimIndent(),
            null,
        )
        val report = service.run(file, Mode.CHECK)
        val problem = report.problems.firstOrNull { "Txt" in it.message }
        assertNotNull(problem, report.problems.toString())
        assertEquals(5, problem.line)
        assertEquals("Greeting", problem.preview)
    }

    @Test
    fun rendersPng() {
        val file = SourceText.of(
            """
            @Preview(widthDp = 120, heightDp = 60, showBackground = true)
            @Composable
            fun Hello() {
                Text("Hello")
            }
            """.trimIndent(),
            null,
        )
        val report = service.run(file, Mode.RENDER, settings = RenderSettings(density = 1f, waitMs = 0))
        val image = report.images.single()
        assertEquals(120, image.widthPx)
        assertEquals(60, image.heightPx)
        assertEquals(PNG_SIGNATURE, image.bytes.take(4))
    }

    @Test
    fun wrapsContentWithoutSize() {
        val file = SourceText.of(
            """
            @Preview
            @Composable
            fun Small() {
                Spacer(Modifier.size(40.dp, 30.dp))
            }
            """.trimIndent(),
            null,
        )
        val image = service.run(file, Mode.RENDER, settings = RenderSettings(density = 2f, waitMs = 0)).images.single()
        assertEquals(80, image.widthPx)
        assertEquals(60, image.heightPx)
    }

    @Test
    fun usesOtherFilesAndResourcesOfTheProject() {
        val root = createTempDirectory().toFile()
        val ui = File(root, "app/src/main/kotlin/app/ui").apply { mkdirs() }
        File(ui, "Theme.kt").writeText(
            """
            package app.ui

            val Title = "Welcome"

            @Composable
            fun Header(text: String) {
                Text(text)
            }
            """.trimIndent(),
        )
        val screen = File(ui, "Screen.kt").apply {
            writeText(
                """
                package app.ui

                @Preview
                @Composable
                fun ScreenPreview() {
                    Column {
                        Header(Title)
                        Text(stringResource(R.string.subtitle))
                    }
                }
                """.trimIndent(),
            )
        }
        File(root, "app/src/main/res/values").apply { mkdirs() }.resolve("strings.xml").writeText(
            """<resources><string name="subtitle">Hello</string></resources>""",
        )
        val report = service.run(SourceText.read(screen), Mode.CHECK)
        assertEquals(emptyList(), report.problems)
    }

    private companion object {
        val PNG_SIGNATURE = listOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
    }
}
