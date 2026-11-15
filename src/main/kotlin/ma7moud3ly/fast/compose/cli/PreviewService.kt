package ma7moud3ly.fast.compose.cli

import java.io.Closeable
import ma7moud3ly.fast.compose.interpreter.Interpreter
import ma7moud3ly.fast.compose.interpreter.LoadedSources
import ma7moud3ly.fast.compose.interpreter.RuntimeError
import ma7moud3ly.fast.compose.ui.PreviewConfig
import ma7moud3ly.fast.compose.ui.previewConfig

/** What to do with a file's previews. */
enum class Mode {
    /** Parse the file and read its previews' settings; nothing runs. */
    LIST,

    /** Also run every preview off-screen to find runtime errors, unknown names and binding mismatches. */
    CHECK,

    /** Also keep a PNG of every preview. */
    RENDER,
}

/** The core of the CLI and the MCP server: lists, checks and renders the previews of Kotlin files. */
class PreviewService(project: ProjectSettings) : Closeable {

    private val workspace = Workspace(project)
    private val renderer = Renderer()

    /**
     * Runs [mode] on [file]'s previews, or only those [filter] names (by preview or function name). Problems found by
     * several previews are reported once.
     */
    fun run(file: SourceText, mode: Mode, filter: String? = null, settings: RenderSettings = RenderSettings()): FileReport {
        val problems = ProblemList()
        val parsed = file.parsed
        parsed.diagnostics.forEach { problems += Problem.of(it, file.path) }
        val hasErrors = parsed.hasErrors

        val loaded = if (hasErrors) null else workspace.load(file)
        loaded?.let { problems += loadProblems(it, file) }

        val previews = previews(file, loaded, problems).filter { it.matches(filter) }
        if (mode == Mode.LIST || hasErrors) return FileReport(file.path, previews, problems.toList())

        val sources = workspace.sources(file)
        val resources = workspace.resources(file)
        val output = mutableListOf<String>()
        val images = mutableListOf<RenderedImage>()
        for (preview in previews) {
            val config = preview.config.copy(
                darkTheme = settings.dark ?: preview.config.darkTheme,
                fontScale = settings.fontScale ?: preview.config.fontScale,
            )
            val result = renderer.render(
                RenderRequest(
                    file = file,
                    entry = preview.function,
                    config = config,
                    device = settings.device,
                    density = settings.density,
                    waitMs = settings.waitMs,
                    sources = sources,
                    resources = resources,
                    externals = workspace.externals,
                    capture = mode == Mode.RENDER,
                ),
            )
            result.diagnostics.forEach { problems += Problem.of(it, file.path) }
            for (log in result.logs) {
                val problem = Problem.of(log, file.path, preview.name)
                if (problem != null) problems += problem else if (log.text !in output) output += log.text
            }
            result.crash?.let { crash ->
                val name = crash::class.simpleName ?: "Error"
                val message = crash.message?.let { "$name: $it" } ?: name
                problems += Problem(Severity.ERROR, "Preview crashed: $message", file.path, preview = preview.name)
            }
            if (result.timedOut) {
                problems += Problem(Severity.ERROR, "Timed out loading the files the preview uses", file.path, preview = preview.name)
            }
            result.png?.let { images += RenderedImage(preview, it, result.widthPx, result.heightPx) }
        }
        return FileReport(file.path, previews, problems.toList(), output, images)
    }

    /** Names imported from the project that no file declares, and parse errors of the files [file] uses. */
    private fun loadProblems(loaded: LoadedSources, file: SourceText): List<Problem> =
        loaded.missing.map { Problem.of(it, file.path) } +
            loaded.diagnostics.flatMap { (path, diagnostics) -> diagnostics.map { Problem.of(it, path) } }

    /** [file]'s `@Preview`s with their settings; invalid `@Preview` arguments are reported and ignored. */
    private fun previews(file: SourceText, loaded: LoadedSources?, problems: ProblemList): List<PreviewInfo> {
        val program = file.parsed.program
        if (program.previews.isEmpty()) return emptyList()
        val reader = Interpreter(
            program,
            renderer.registry,
            onError = { error: RuntimeError ->
                problems += Problem(
                    if (error.isWarning) Severity.WARNING else Severity.ERROR,
                    error.message,
                    error.file ?: file.path,
                    error.line,
                )
            },
            onLog = {},
            dependencies = loaded?.dependencies.orEmpty(),
        )
        return program.previews.map { (function, annotation) ->
            val config = runCatching { reader.previewConfig(function, annotation) }.getOrNull()
                ?: PreviewConfig(name = function.name)
            PreviewInfo(function.name, function.span.line, config)
        }
    }

    override fun close() {
        renderer.close()
        workspace.close()
    }
}

/** Problems in the order found, without repeats. */
private class ProblemList {
    private val problems = LinkedHashMap<List<Any?>, Problem>()

    operator fun plusAssign(problem: Problem) {
        problems.putIfAbsent(problem.key, problem)
    }

    operator fun plusAssign(list: List<Problem>) = list.forEach { plusAssign(it) }

    fun toList(): List<Problem> = problems.values.toList()
}
