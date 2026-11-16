package ma7moud3ly.fast.compose.cli

import java.io.File

/**
 * Libraries the CLI ships for the library code previews often call, so it runs without `--classpath`: kotlinx-datetime
 * (`Instant`, `TimeZone`, `toJavaZoneId()`, …) and kotlinx-serialization-core, which it refers to. They come after the
 * project's own jars, so a project's version wins.
 */
object BundledLibraries {

    /** Classes whose jars are offered to library code, one per library. */
    private val classes = listOf(
        "kotlinx.datetime.TimeZone",
        "kotlinx.serialization.KSerializer",
    )

    /** The jars of the bundled libraries, as the CLI runs them; empty for any that can't be found. */
    val jars: List<File> by lazy { classes.mapNotNull(::jarOf).distinct() }

    private fun jarOf(className: String): File? = runCatching {
        val location = Class.forName(className).protectionDomain.codeSource.location
        File(location.toURI()).takeIf { it.isFile }
    }.getOrNull()
}
