package ma7moud3ly.fast.compose.cli

import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.InputStream
import java.io.PrintStream
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * An MCP server over stdio: newline-delimited JSON-RPC messages, with the tools `check`, `render` and
 * `list_previews`. Rendered previews go back to the agent as PNG images. Requests are handled one at a time.
 */
class McpServer(private val service: PreviewService) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Reads requests from [input] until it closes and writes responses to [output]. Everything else the process
     * prints, e.g. by libraries, goes to stderr so it can't break the protocol.
     */
    fun run(input: InputStream = System.`in`, output: PrintStream = PrintStream(FileOutputStream(FileDescriptor.out), true, "UTF-8")) {
        System.setOut(System.err)
        input.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val response = handleLine(line) ?: continue
                output.println(json.encodeToString(JsonElement.serializer(), response))
                output.flush()
            }
        }
    }

    /** The response to one message [line], or null for a notification. */
    fun handleLine(line: String): JsonObject? {
        val message = try {
            json.parseToJsonElement(line)
        } catch (e: Exception) {
            return error(JsonNull, PARSE_ERROR, "Parse error: ${e.message}")
        }
        if (message !is JsonObject) return error(JsonNull, INVALID_REQUEST, "Expected a JSON-RPC request object")
        return handle(message)
    }

    /** The response to [message], or null for a notification or a response. */
    fun handle(message: JsonObject): JsonObject? {
        val id = message["id"]
        val method = (message["method"] as? JsonPrimitive)?.contentOrNull
        if (method == null) {
            // A response to a request of ours; the server sends none.
            return if (id == null) null else error(id, INVALID_REQUEST, "Missing method")
        }
        if (id == null) return null
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        return when (method) {
            "initialize" -> result(id, initialize(params))
            "ping" -> result(id, JsonObject(emptyMap()))
            "tools/list" -> result(id, buildJsonObject { put("tools", TOOLS) })
            "tools/call" -> result(id, callTool(params))
            else -> error(id, METHOD_NOT_FOUND, "Method not found: $method")
        }
    }

    private fun initialize(params: JsonObject): JsonObject {
        val requested = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
        return buildJsonObject {
            put("protocolVersion", requested?.takeIf { it in PROTOCOL_VERSIONS } ?: PROTOCOL_VERSIONS.first())
            putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
            putJsonObject("serverInfo") {
                put("name", "fast-compose")
                put("title", "Fast Compose CLI")
                put("version", VERSION)
                put("websiteUrl", "https://github.com/fast-compose/compose-cli")
                putJsonArray("icons") {
                    add(buildJsonObject {
                        put("src", ICON_URL)
                        put("mimeType", "image/png")
                        putJsonArray("sizes") { add(JsonPrimitive("500x500")) }
                    })
                }
            }
            put("instructions", INSTRUCTIONS)
        }
    }

    private fun callTool(params: JsonObject): JsonObject {
        val name = (params["name"] as? JsonPrimitive)?.contentOrNull
        val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        return try {
            when (name) {
                "check" -> report(files(arguments).map { service.run(it, Mode.CHECK, arguments.string("preview")) })
                "list_previews" -> report(files(arguments).map { service.run(it, Mode.LIST, arguments.string("preview")) })
                "render" -> render(arguments)
                else -> toolError("Unknown tool: $name")
            }
        } catch (e: UsageError) {
            toolError(e.message ?: "Invalid arguments")
        } catch (e: Exception) {
            toolError("${e::class.simpleName}: ${e.message}")
        }
    }

    private fun render(arguments: JsonObject): JsonObject {
        val settings = RenderSettings(
            device = arguments.string("size")?.let { DeviceSize.parse(it) } ?: DeviceSize.Phone,
            dark = arguments.boolean("dark"),
            fontScale = arguments.number("fontScale")?.toFloat()?.takeIf { it > 0f },
            density = arguments.number("density")?.toFloat()?.takeIf { it > 0f } ?: 2f,
            waitMs = arguments.number("wait")?.toLong()?.coerceAtLeast(0) ?: 300,
        )
        val reports = files(arguments).map { service.run(it, Mode.RENDER, arguments.string("preview"), settings) }
        return buildJsonObject {
            putJsonArray("content") {
                add(text(json.encodeToString(JsonElement.serializer(), toJson(reports))))
                for (report in reports) {
                    for (image in report.images) {
                        val title = "${image.preview.name} (${image.preview.function}) in ${report.path.substringAfterLast('/')}, " +
                            "${image.widthPx} × ${image.heightPx} px"
                        add(text(title))
                        add(buildJsonObject {
                            put("type", "image")
                            put("data", Base64.getEncoder().encodeToString(image.bytes))
                            put("mimeType", "image/png")
                        })
                    }
                }
            }
            put("isError", false)
        }
    }

    /** The files a tool call names: `code` (as the file at `path` when given), or the file or folder at `path`. */
    private fun files(arguments: JsonObject): List<SourceText> {
        val path = arguments.string("path")?.let(::File)
        val code = arguments.string("code")
        if (code != null) return listOf(SourceText.of(code, path))
        if (path == null) throw UsageError("Give 'path' (a Kotlin file or folder) or 'code' (Kotlin source)")
        if (!isGlob(path.path) && !path.exists()) throw UsageError("Not found: $path")
        return kotlinFiles(listOf(path), recursive = arguments.boolean("recursive") == true).map { SourceText.read(it) }
    }

    private fun report(reports: List<FileReport>): JsonObject = buildJsonObject {
        putJsonArray("content") { add(text(json.encodeToString(JsonElement.serializer(), toJson(reports)))) }
        put("isError", false)
    }

    private fun toolError(message: String): JsonObject = buildJsonObject {
        putJsonArray("content") { add(text(message)) }
        put("isError", true)
    }

    private fun text(text: String) = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    private fun result(id: JsonElement, result: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    private fun error(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }

    private fun JsonObject.primitive(name: String): JsonPrimitive? = (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }
    private fun JsonObject.string(name: String): String? = primitive(name)?.contentOrNull?.takeIf { it.isNotEmpty() }
    private fun JsonObject.boolean(name: String): Boolean? = primitive(name)?.booleanOrNull
    private fun JsonObject.number(name: String): Number? = primitive(name)?.let { it.longOrNull ?: it.floatOrNull }

    companion object {
        /** Protocol versions the server speaks, newest first. */
        val PROTOCOL_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

        /** The logo, shown by MCP clients that display server icons. */
        private const val ICON_URL = "https://raw.githubusercontent.com/fast-compose/compose-cli/main/docs/images/logo.png"

        private const val PARSE_ERROR = -32700
        private const val INVALID_REQUEST = -32600
        private const val METHOD_NOT_FOUND = -32601

        private const val INSTRUCTIONS =
            "Fast Compose checks and renders Jetpack Compose and Compose Multiplatform @Preview functions without " +
                "compiling them. Use check after editing a Kotlin file with previews, render to see them, and " +
                "list_previews to find them. A preview that renders here may still fail to compile, so build before " +
                "shipping."

        private val fileProperties = buildJsonObject {
            putJsonObject("path") {
                put("type", "string")
                put("description", "A Kotlin file, a folder, or a glob such as ui/**/*Screen.kt; folders and globs take the files with @Preview functions")
            }
            putJsonObject("recursive") {
                put("type", "boolean")
                put("description", "Search a folder's subfolders too (default false)")
            }
            putJsonObject("code") {
                put("type", "string")
                put("description", "Kotlin source to use instead of reading 'path'; 'path' then places it in its project")
            }
            putJsonObject("preview") {
                put("type", "string")
                put("description", "Only the preview with this name or function name")
            }
        }

        private fun tool(name: String, description: String, extra: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
            put("name", name)
            put("description", description)
            putJsonObject("inputSchema") {
                put("type", "object")
                put("properties", JsonObject(fileProperties + extra))
            }
        }

        private val TOOLS: JsonArray = buildJsonArray {
            add(
                tool(
                    "check",
                    "Runs a Kotlin file's @Preview functions off-screen and reports parse errors, unknown names, " +
                        "binding mismatches and runtime errors as JSON, with file, line and preview.",
                ),
            )
            add(
                tool(
                    "render",
                    "Renders a Kotlin file's @Preview functions and returns a PNG image of each, plus the problems " +
                        "found as JSON.",
                    buildJsonObject {
                        putJsonObject("size") {
                            put("type", "string")
                            put("description", "Size in dp for previews without widthDp/heightDp: 411x891 (default), phone, foldable, tablet or desktop")
                        }
                        putJsonObject("dark") {
                            put("type", "boolean")
                            put("description", "Render every preview in dark (true) or light (false) theme")
                        }
                        putJsonObject("fontScale") {
                            put("type", "number")
                            put("description", "Font scale for every preview")
                        }
                        putJsonObject("density") {
                            put("type", "number")
                            put("description", "Pixels per dp (default 2)")
                        }
                        putJsonObject("wait") {
                            put("type", "number")
                            put("description", "Milliseconds to let effects, animations and images settle (default 300)")
                        }
                    },
                ),
            )
            add(
                tool(
                    "list_previews",
                    "Lists a Kotlin file's @Preview functions with their names, lines and settings, without running them.",
                ),
            )
        }
    }
}
