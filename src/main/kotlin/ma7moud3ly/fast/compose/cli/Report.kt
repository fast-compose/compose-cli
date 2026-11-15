package ma7moud3ly.fast.compose.cli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ma7moud3ly.fast.compose.interpreter.MissingReference
import ma7moud3ly.fast.compose.parser.Diagnostic
import ma7moud3ly.fast.compose.ui.LogLine
import ma7moud3ly.fast.compose.ui.PreviewConfig

enum class Severity { ERROR, WARNING }

/**
 * A problem found in a previewed file or a file it uses: a parse error, an unknown name, a binding mismatch or a
 * runtime error. [file] is an absolute path with `/` separators; [preview] names the preview that hit it at runtime.
 */
data class Problem(
    val severity: Severity,
    val message: String,
    val file: String,
    val line: Int? = null,
    val column: Int? = null,
    val preview: String? = null,
) {
    /** Problems with the same key are the same problem, e.g. reported by each preview of a file. */
    val key: List<Any?> get() = listOf(severity, message, file, line)

    companion object {
        fun of(diagnostic: Diagnostic, file: String) = Problem(
            if (diagnostic.severity == Diagnostic.Severity.ERROR) Severity.ERROR else Severity.WARNING,
            diagnostic.message,
            file,
            diagnostic.span.line,
            diagnostic.span.column,
        )

        fun of(missing: MissingReference, file: String) =
            Problem(Severity.WARNING, missing.toString(), missing.file ?: file)

        /** The problem in an error or warning log line, or null for `println` output. */
        fun of(log: LogLine, file: String, preview: String?): Problem? {
            if (!log.isError && !log.isWarning) return null
            return Problem(
                if (log.isError) Severity.ERROR else Severity.WARNING,
                log.text.replace(LOCATION_PREFIX, ""),
                log.file ?: file,
                log.line,
                preview = preview,
            )
        }

        /** `Line 12: ` or `Theme.kt:12: ` before a message, as runtime errors print their location. */
        private val LOCATION_PREFIX = Regex("""^(Line \d+|[^\s:]+\.kts?(:\d+)?): """)
    }
}

/** A `@Preview` of a file: its [function], 1-based source [line] and settings. */
data class PreviewInfo(val function: String, val line: Int, val config: PreviewConfig) {
    val name: String get() = config.name

    /** True when [filter] names this preview, by its name or its function's name. */
    fun matches(filter: String?): Boolean = filter == null || filter == name || filter == function
}

/** A rendered preview: PNG [bytes] of [widthPx] × [heightPx] pixels. */
class RenderedImage(val preview: PreviewInfo, val bytes: ByteArray, val widthPx: Int, val heightPx: Int) {
    /** Where the CLI wrote the image, if it did. */
    var path: String? = null
}

/** What a command found in one file: its previews, problems, `println` output and rendered images. */
data class FileReport(
    val path: String,
    val previews: List<PreviewInfo>,
    val problems: List<Problem>,
    val output: List<String> = emptyList(),
    val images: List<RenderedImage> = emptyList(),
) {
    val errors: Int get() = problems.count { it.severity == Severity.ERROR }
    val warnings: Int get() = problems.count { it.severity == Severity.WARNING }
}

/**
 * [reports] as JSON: files with their previews, problems, output and images, then the totals. Without [details], the
 * problems and output are left empty; the totals still count them.
 */
fun toJson(reports: List<FileReport>, details: Boolean = true): JsonObject = buildJsonObject {
    put("files", JsonArray(reports.map { toJson(it, details) }))
    put("errors", reports.sumOf { it.errors })
    put("warnings", reports.sumOf { it.warnings })
}

fun toJson(report: FileReport, details: Boolean = true): JsonObject = buildJsonObject {
    put("path", report.path)
    put("previews", JsonArray(report.previews.map { preview ->
        val image = report.images.firstOrNull { it.preview === preview }
        buildJsonObject {
            put("name", preview.name)
            put("function", preview.function)
            put("line", preview.line)
            preview.config.group?.let { put("group", it) }
            preview.config.widthDp?.let { put("widthDp", it) }
            preview.config.heightDp?.let { put("heightDp", it) }
            preview.config.locale?.let { put("locale", it) }
            put("fontScale", preview.config.fontScale)
            put("darkTheme", preview.config.darkTheme)
            put("showBackground", preview.config.showBackground)
            if (image != null) {
                image.path?.let { put("image", it) }
                put("widthPx", image.widthPx)
                put("heightPx", image.heightPx)
            }
        }
    }))
    put("problems", JsonArray(if (details) report.problems.map { toJson(it) } else emptyList()))
    put("output", JsonArray(if (details) report.output.map { JsonPrimitive(it) } else emptyList()))
}

fun toJson(problem: Problem): JsonObject = buildJsonObject {
    put("severity", problem.severity.name.lowercase())
    put("message", problem.message)
    put("file", problem.file)
    problem.line?.let { put("line", it) }
    problem.column?.let { put("column", it) }
    problem.preview?.let { put("preview", it) }
}

/**
 * [reports] as text for a terminal: per file, its problems, previews and images, then the totals. Without [details],
 * problems and `println` output are left out; the totals still count them.
 */
fun toText(reports: List<FileReport>, showPreviews: Boolean, details: Boolean = true): String = buildString {
    for (report in reports) {
        appendLine(report.path)
        if (details) for (problem in report.problems) appendLine("  ${problemText(problem)}")
        if (showPreviews) {
            for (preview in report.previews) {
                val image = report.images.firstOrNull { it.preview === preview }
                append("  preview  ${preview.name}")
                if (preview.name != preview.function) append(" (${preview.function})")
                append("  line ${preview.line}")
                previewDetails(preview.config).takeIf { it.isNotEmpty() }?.let { append("  ·  $it") }
                if (image != null) append("  →  ${image.path ?: "rendered"} (${image.widthPx} × ${image.heightPx} px)")
                appendLine()
            }
            if (report.previews.isEmpty()) appendLine("  no @Preview functions")
        }
        if (details) for (line in report.output) appendLine("  output   $line")
    }
    val errors = reports.sumOf { it.errors }
    val warnings = reports.sumOf { it.warnings }
    append("${reports.size} file(s), $errors error(s), $warnings warning(s)")
}

/** One problem on one line: `error    Card.kt:12:5  message  [preview]`. */
fun problemText(problem: Problem): String {
    val location = buildString {
        append(problem.file.substringAfterLast('/'))
        problem.line?.let { append(":$it") }
        problem.column?.let { append(":$it") }
    }
    val severity = problem.severity.name.lowercase().padEnd(8)
    val preview = problem.preview?.let { "  [$it]" }.orEmpty()
    return "$severity $location  ${problem.message}$preview"
}

/** The size, theme, font scale and locale set by a `@Preview`, e.g. `360 × wrap dp · dark`. */
private fun previewDetails(config: PreviewConfig): String = listOfNotNull(
    if (config.widthDp != null || config.heightDp != null) "${config.widthDp ?: "wrap"} × ${config.heightDp ?: "wrap"} dp" else null,
    if (config.darkTheme) "dark" else null,
    if (config.fontScale != 1f) "font ×${config.fontScale}" else null,
    config.locale,
).joinToString("  ·  ")
