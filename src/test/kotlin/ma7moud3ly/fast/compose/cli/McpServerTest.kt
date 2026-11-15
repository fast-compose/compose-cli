package ma7moud3ly.fast.compose.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class McpServerTest {

    private val service = PreviewService(ProjectSettings(sources = false))
    private val server = McpServer(service)

    @AfterTest
    fun close() = service.close()

    private fun call(line: String): JsonObject? = server.handleLine(line)

    @Test
    fun initializes() {
        val response = call("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""")!!
        val result = response["result"]!!.jsonObject
        assertEquals("2025-06-18", result["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("fast-compose", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun answersUnknownVersionsWithItsNewest() {
        val response = call("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}""")!!
        val version = response["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content
        assertEquals(McpServer.PROTOCOL_VERSIONS.first(), version)
    }

    @Test
    fun listsTools() {
        val response = call("""{"jsonrpc":"2.0","id":"a","method":"tools/list"}""")!!
        val tools = response["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("check", "render", "list_previews"), tools)
    }

    @Test
    fun ignoresNotifications() {
        assertNull(call("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
    }

    @Test
    fun reportsProtocolErrors() {
        assertEquals(-32700, error(call("{oops")!!))
        assertEquals(-32601, error(call("""{"jsonrpc":"2.0","id":2,"method":"resources/list"}""")!!))
    }

    @Test
    fun checksCode() {
        val code = Json.encodeToString(String.serializer(), "@Preview @Composable fun A() { Txt(\"a\") }")
        val response = call("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"check","arguments":{"code":$code}}}""")!!
        val result = response["result"]!!.jsonObject
        assertEquals("false", result["isError"]!!.jsonPrimitive.content)
        val text = result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue("Txt" in text, text)
    }

    @Test
    fun rendersImages() {
        val code = Json.encodeToString(String.serializer(), "@Preview(widthDp = 50, heightDp = 20) @Composable fun A() { Text(\"a\") }")
        val response = call(
            """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"render","arguments":{"code":$code,"wait":0}}}""",
        )!!
        val content = response["result"]!!.jsonObject["content"]!!.jsonArray
        val image = content.map { it.jsonObject }.single { it["type"]!!.jsonPrimitive.content == "image" }
        assertEquals("image/png", image["mimeType"]!!.jsonPrimitive.content)
    }

    @Test
    fun reportsBadArgumentsAsToolErrors() {
        val response = call("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"check","arguments":{}}}""")!!
        assertEquals("true", response["result"]!!.jsonObject["isError"]!!.jsonPrimitive.content)
    }

    @Test
    fun runsOverStreams() {
        val input = """
            {"jsonrpc":"2.0","id":1,"method":"ping"}
            {"jsonrpc":"2.0","method":"notifications/initialized"}
        """.trimIndent().byteInputStream()
        val bytes = ByteArrayOutputStream()
        val stdout = System.out
        try {
            server.run(input, PrintStream(bytes, true, "UTF-8"))
        } finally {
            System.setOut(stdout)
        }
        val lines = bytes.toString("UTF-8").lines().filter { it.isNotBlank() }
        assertEquals(listOf("""{"jsonrpc":"2.0","id":1,"result":{}}"""), lines)
    }

    private fun error(response: JsonObject): Int = response["error"]!!.jsonObject["code"]!!.jsonPrimitive.int
}
