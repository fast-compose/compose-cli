package ma7moud3ly.fast.compose.cli

import java.io.File

/** A wrong command line; its message is printed with the usage. */
class UsageError(message: String) : Exception(message)

/** A size in dp that previews without `widthDp`/`heightDp` are laid out at. */
data class DeviceSize(val widthDp: Int, val heightDp: Int) {
    companion object {
        val Phone = DeviceSize(411, 891)

        private val named = mapOf(
            "phone" to Phone,
            "foldable" to DeviceSize(673, 841),
            "tablet" to DeviceSize(1280, 800),
            "desktop" to DeviceSize(1920, 1080),
        )

        /** Parses `411x891` or a device name: `phone`, `foldable`, `tablet` or `desktop`. */
        fun parse(text: String): DeviceSize {
            named[text.lowercase()]?.let { return it }
            val parts = text.lowercase().split('x')
            val width = parts.getOrNull(0)?.toIntOrNull()
            val height = parts.getOrNull(1)?.toIntOrNull()
            if (parts.size != 2 || width == null || height == null || width <= 0 || height <= 0) {
                throw UsageError("Invalid size '$text': use WIDTHxHEIGHT in dp, e.g. 411x891, or ${named.keys.joinToString()}")
            }
            return DeviceSize(width, height)
        }
    }
}

/** How previews are rendered: overrides of `@Preview` settings and output quality. */
data class RenderSettings(
    val device: DeviceSize = DeviceSize.Phone,
    val dark: Boolean? = null,
    val fontScale: Float? = null,
    val density: Float = 2f,
    val waitMs: Long = 300,
)

/** Where previews find other files, resources and library code. */
data class ProjectSettings(
    val sources: Boolean = true,
    val modules: Boolean = false,
    val sourceRoots: List<File> = emptyList(),
    val resourceFolders: List<File> = emptyList(),
    val classpath: List<File> = emptyList(),
)

/** A parsed command line. */
data class Options(
    val command: String,
    val paths: List<File> = emptyList(),
    val output: File = File("."),
    val preview: String? = null,
    val json: Boolean = false,
    val ignoreWarnings: Boolean = false,
    val quiet: Boolean = false,
    val progress: Boolean = true,
    val recursive: Boolean = false,
    val render: RenderSettings = RenderSettings(),
    val project: ProjectSettings = ProjectSettings(),
) {
    companion object {

        /** Parses [args]: the command, then paths and options in any order (`--name value` or `--name=value`). */
        fun parse(args: List<String>): Options {
            val command = args.firstOrNull() ?: throw UsageError("Missing command")
            var options = Options(command)
            var render = RenderSettings()
            var project = ProjectSettings()
            val paths = mutableListOf<File>()
            var i = 1
            while (i < args.size) {
                val arg = args[i++]
                if (!arg.startsWith("-") || arg == "-") {
                    paths += File(arg)
                    continue
                }
                val name = arg.substringBefore('=')
                val inline = if ('=' in arg) arg.substringAfter('=') else null
                fun value(): String = inline ?: args.getOrNull(i++) ?: throw UsageError("Missing value for $name")
                when (name) {
                    "-o", "--output" -> options = options.copy(output = File(value()))
                    "-p", "--preview" -> options = options.copy(preview = value())
                    "--json" -> options = options.copy(json = true)
                    "--ignore-warnings" -> options = options.copy(ignoreWarnings = true)
                    "-r", "--recursive" -> options = options.copy(recursive = true)
                    "-q", "--quiet" -> options = options.copy(quiet = true)
                    "--no-progress" -> options = options.copy(progress = false)
                    "--size" -> render = render.copy(device = DeviceSize.parse(value()))
                    "--dark" -> render = render.copy(dark = true)
                    "--light" -> render = render.copy(dark = false)
                    "--font-scale" -> render = render.copy(fontScale = positiveFloat(name, value()))
                    "--density" -> render = render.copy(density = positiveFloat(name, value()))
                    "--wait" -> render = render.copy(
                        waitMs = value().toLongOrNull()?.takeIf { it >= 0 } ?: throw UsageError("$name takes milliseconds"),
                    )
                    "--no-sources" -> project = project.copy(sources = false)
                    "-m", "--modules" -> project = project.copy(modules = true)
                    "--source-root" -> project = project.copy(sourceRoots = project.sourceRoots + File(value()))
                    "--res" -> project = project.copy(resourceFolders = project.resourceFolders + File(value()))
                    "--classpath", "-cp" -> project = project.copy(classpath = project.classpath + classpath(value()))
                    "-h", "--help" -> return Options("help")
                    else -> throw UsageError("Unknown option '$arg'")
                }
            }
            return options.copy(paths = paths, render = render, project = project)
        }

        private fun positiveFloat(name: String, text: String): Float =
            text.toFloatOrNull()?.takeIf { it > 0f } ?: throw UsageError("$name takes a positive number")

        /**
         * Jars and class folders from [text]: entries separated by the platform's path separator; `@file` reads more
         * entries from a file, one or more per line.
         */
        fun classpath(text: String): List<File> = text.split(File.pathSeparatorChar).filter { it.isNotBlank() }.flatMap { entry ->
            if (entry.startsWith("@")) {
                val file = File(entry.drop(1))
                if (!file.isFile) throw UsageError("Classpath file not found: $file")
                file.readLines().filter { it.isNotBlank() }.flatMap { classpath(it.trim()) }
            } else {
                listOf(File(entry.trim()))
            }
        }
    }
}
