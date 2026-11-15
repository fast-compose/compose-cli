package ma7moud3ly.fast.compose.cli

import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import ma7moud3ly.fast.compose.FastComposeInfo
import ma7moud3ly.fast.compose.parser.KotlinParser

/** The CLI's version, from its jar's manifest. */
val VERSION: String = PreviewService::class.java.`package`?.implementationVersion ?: "dev"

/** The CLI's version with the Fast Compose engine and Compose versions it runs. */
val VERSION_TEXT: String =
    "Fast Compose CLI $VERSION (Fast Compose ${FastComposeInfo.VERSION}, Jetpack Compose ${FastComposeInfo.COMPOSE_VERSION})"

const val USAGE = """Usage: fast-compose <command> [paths] [options]

Commands:
  check [paths]       Report parse errors, unknown names, binding mismatches and runtime errors
  list [paths]        List the @Preview functions
  render [paths]      Render each @Preview to a PNG
  mcp                 Run as an MCP server over stdio, with the tools check, render and list_previews
  version             Print the version
  help                Print this help

Paths are Kotlin files, folders or globs (*.kt, ui/**/*Screen.kt); folders and globs take the files with @Preview
functions. Without paths, or with *, the current folder is searched. Subfolders are searched only with -r, or where
a glob has **.

Options:
  -r, --recursive       Search folders' subfolders too
  -p, --preview NAME    Only the preview with this name or function name
  -o, --output DIR      Folder for rendered images (render; default: current folder)
  --json                Print JSON instead of text
  --ignore-warnings     Report errors only
  -q, --quiet           Leave out problems, println output and messages; totals and the exit code still count them
  --size WxH|DEVICE     Size in dp for previews without widthDp/heightDp: 411x891 (default), phone, foldable,
                        tablet, desktop
  --dark, --light       Render every preview in dark or light theme
  --font-scale N        Font scale for every preview
  --density N           Pixels per dp in images (default: 2)
  --wait MS             Time to let effects, animations and images settle before capture (default: 300)
  --no-sources          Don't read other project files; the file must declare what it uses
  --source-root DIR     Another source root to look up declarations in (repeatable)
  --res DIR             Another res/ or composeResources/ folder (repeatable)
  -cp, --classpath CP   Library jars to run real library code from, separated by the path separator; @file reads
                        them from a file (repeatable)

Exit codes: 0 no errors, 1 errors found, 2 wrong command line."""

private val prettyJson = Json { prettyPrint = true }

fun main(args: Array<String>) {
    if (System.getProperty("java.awt.headless") == null) System.setProperty("java.awt.headless", "true")
    val code = try {
        run(Options.parse(args.toList().ifEmpty { listOf("help") }))
    } catch (e: UsageError) {
        System.err.println("Error: ${e.message}\n")
        System.err.println(USAGE)
        2
    }
    exitProcess(code)
}

/** Runs the command of [options] and returns the exit code. */
fun run(options: Options): Int = when (options.command) {
    "check" -> runFiles(options, Mode.CHECK)
    "list" -> runFiles(options, Mode.LIST)
    "render" -> runFiles(options, Mode.RENDER)
    "mcp" -> PreviewService(options.project).use { McpServer(it).run(); 0 }
    "version", "--version", "-v" -> println(VERSION_TEXT).let { 0 }
    "help", "-h", "--help" -> println("$VERSION_TEXT\n\n$USAGE").let { 0 }
    else -> throw UsageError("Unknown command '${options.command}'")
}

private fun runFiles(options: Options, mode: Mode): Int {
    options.paths.firstOrNull { !isGlob(it.path) && !it.exists() }?.let { throw UsageError("Not found: $it") }
    (options.project.sourceRoots + options.project.resourceFolders).firstOrNull { !it.isDirectory }
        ?.let { throw UsageError("Not a folder: $it") }
    val files = kotlinFiles(options.paths, options.recursive)
    if (files.isEmpty() && !options.quiet) System.err.println("No Kotlin files with @Preview functions found")
    if (files.isEmpty() && !options.json) return 0
    // With --json, stdout carries only the JSON: anything else printed while previews run goes to stderr, which
    // --quiet discards.
    val stdout = System.out
    val stderr = System.err
    if (options.quiet) System.setErr(PrintStream(OutputStream.nullOutputStream()))
    if (options.json) System.setOut(System.err)
    val usedNames = mutableSetOf<String>()
    val reports = try {
        if (files.isEmpty()) {
            emptyList<FileReport>()
        } else {
            PreviewService(options.project).use { service ->
                files.map { file ->
                    val report = service.run(SourceText.read(file), mode, options.preview, options.render)
                    if (mode == Mode.RENDER) writeImages(report, file, options.output, usedNames)
                    if (options.ignoreWarnings) {
                        report.copy(problems = report.problems.filter { it.severity == Severity.ERROR })
                    } else {
                        report
                    }
                }
            }
        }
    } finally {
        System.setOut(stdout)
        System.setErr(stderr)
    }
    val shown = reports.map(::relativePaths)
    if (options.json) {
        println(prettyJson.encodeToString(JsonElement.serializer(), toJson(shown, details = !options.quiet)))
    } else {
        println(toText(shown, showPreviews = mode != Mode.CHECK, details = !options.quiet))
    }
    return if (reports.any { it.errors > 0 }) 1 else 0
}

/** [report] with its file paths relative to the current folder, as the CLI prints them. */
private fun relativePaths(report: FileReport): FileReport = report.copy(
    path = relativePath(report.path),
    problems = report.problems.map { it.copy(file = relativePath(it.file)) },
)

/**
 * [path] relative to the current folder with `/` separators, e.g. `app/src/main/kotlin/Card.kt` or `../core/Theme.kt`;
 * unchanged when it can't be, e.g. on another drive.
 */
fun relativePath(path: String): String =
    runCatching { File(path).relativeTo(File(".").absoluteFile.normalize()).invariantSeparatorsPath }
        .getOrNull()?.ifEmpty { "." } ?: path

/**
 * [paths] as Kotlin files: `.kt` files as given; in folders, and for `*` or no paths in the current folder, the `.kt`
 * files with `@Preview` functions, in subfolders too when [recursive]; for globs such as `*.kt` (`**` for any
 * folders), the matching files with `@Preview` functions. Folders named `build` or starting with `.` are skipped.
 */
fun kotlinFiles(paths: List<File>, recursive: Boolean = false): List<File> = paths.ifEmpty { listOf(File(".")) }.flatMap { path ->
    when {
        path.path == "*" -> previewFiles(File("."), recursive)
        isGlob(path.path) -> glob(path.path)
        path.isFile -> if (path.extension == "kt") listOf(path) else emptyList()
        path.isDirectory -> previewFiles(path, recursive)
        else -> emptyList()
    }
}.distinctBy { it.absoluteFile.normalize() }

/** True when [path] has `*` or `?`, which shells such as cmd.exe pass on unexpanded. */
fun isGlob(path: String): Boolean = '*' in path || '?' in path

/** The `.kt` files with `@Preview` functions in [folder], and in its subfolders when [recursive]. */
private fun previewFiles(folder: File, recursive: Boolean): List<File> =
    kotlinFilesIn(folder, if (recursive) Int.MAX_VALUE else 1).filter(::hasPreviews)

/**
 * Files matching [pattern] with `@Preview` functions: `*` and `?` within a folder name, `**` across folders. Only a
 * pattern with `**` searches below the folders it names.
 */
private fun glob(pattern: String): List<File> {
    val normalized = pattern.replace('\\', '/')
    val base = normalized.substring(0, normalized.indexOfFirst { it == '*' || it == '?' }).substringBeforeLast('/', "")
    val folder = File(base.ifEmpty { "." })
    val rest = if (base.isEmpty()) normalized else normalized.removePrefix("$base/")
    val depth = if ("**" in rest) Int.MAX_VALUE else rest.count { it == '/' } + 1
    val regex = globRegex(rest)
    return kotlinFilesIn(folder, depth).filter { regex.matches(it.relativeTo(folder).invariantSeparatorsPath) && hasPreviews(it) }
}

/** [glob] as a regex over `/`-separated relative paths. */
internal fun globRegex(glob: String): Regex {
    val regex = StringBuilder()
    var i = 0
    while (i < glob.length) {
        when {
            glob.startsWith("**/", i) -> regex.append("(?:.*/)?").also { i += 3 }
            glob.startsWith("**", i) -> regex.append(".*").also { i += 2 }
            glob[i] == '*' -> regex.append("[^/]*").also { i++ }
            glob[i] == '?' -> regex.append("[^/]").also { i++ }
            else -> regex.append(Regex.escape(glob[i].toString())).also { i++ }
        }
    }
    return Regex(regex.toString())
}

/** The `.kt` files in [folder] down to [depth] levels (1: its own files), in path order, skipping `build` and hidden folders. */
private fun kotlinFilesIn(folder: File, depth: Int): List<File> = folder.walkTopDown()
    .maxDepth(depth)
    .onEnter { it == folder || !it.name.startsWith(".") && it.name != "build" }
    .filter { it.isFile && it.extension == "kt" }
    .sortedBy { it.path }
    .toList()

private fun hasPreviews(file: File): Boolean = KotlinParser.hasPreviews(file.readText())

/** Writes [report]'s images to [folder] as `<File>-<Preview>.png`, unique across [used], and records their paths. */
private fun writeImages(report: FileReport, file: File, folder: File, used: MutableSet<String>) {
    if (report.images.isEmpty()) return
    folder.mkdirs()
    for (image in report.images) {
        val base = "${file.nameWithoutExtension}-${safeName(image.preview.name)}"
        var name = base
        var index = 2
        while (!used.add(name)) name = "$base-${index++}"
        val target = File(folder, "$name.png")
        target.writeBytes(image.bytes)
        image.path = target.invariantSeparatorsPath
    }
}

/** [name] as part of a file name: letters, digits, `-`, `_` and `.`; other characters become `_`. */
fun safeName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "preview" }
