package io.furryr.file.agent

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import org.junit.Assert.*
import org.junit.Test

class ToolDefinitionsTest {

    private val gson = Gson()

    @Test
    fun `allToolDefinitions returns all 6 tools`() {
        val tools = ToolDefinitions.allToolDefinitions()
        assertEquals("Expected exactly 6 tool definitions", 6, tools.size)
    }

    @Test
    fun `each tool has type and function keys`() {
        val tools = ToolDefinitions.allToolDefinitions()
        tools.forEach { tool ->
            assertEquals("type must be 'function'", "function", tool["type"])
            assertNotNull("function key must exist", tool["function"])
        }
    }

    @Test
    fun `each function has name description and parameters`() {
        val tools = ToolDefinitions.allToolDefinitions()
        tools.forEach { tool ->
            val func = tool["function"] as Map<*, *>
            assertNotNull("function.name is required", func["name"])
            assertNotNull("function.description is required", func["description"])
            assertNotNull("function.parameters is required", func["parameters"])
        }
    }

    @Test
    fun `tool names are all unique and cover the expected set`() {
        val tools = ToolDefinitions.allToolDefinitions()
        val names = tools.map { (it["function"] as Map<*, *>)["name"] as String }.toSet()
        val expected = setOf(
            "read_file",
            "write_file",
            "list_directory",
            "execute_command",
            "search_files",
            "get_file_info"
        )
        assertEquals(expected, names)
    }

    @Test
    fun `each tool parameters have valid JSON Schema structure`() {
        val tools = ToolDefinitions.allToolDefinitions()
        tools.forEach { tool ->
            val func = tool["function"] as Map<*, *>
            val params = func["parameters"] as Map<*, *>

            assertEquals("parameters.type must be 'object'", "object", params["type"])
            assertNotNull("parameters.properties is required", params["properties"])
            assertNotNull("parameters.required is required", params["required"])
            assertNotNull("parameters.additionalProperties is required", params["additionalProperties"])
            assertEquals("additionalProperties must be false", false, params["additionalProperties"])

            val properties = params["properties"] as Map<*, *>
            val required = params["required"] as List<*>
            val name = func["name"] as String

            // All required properties must exist in properties
            required.forEach { prop ->
                assertTrue(
                    "Required property '$prop' for tool '$name' must exist in properties",
                    properties.containsKey(prop)
                )
            }

            // Every property has a type and description
            properties.forEach { (propKey, propValue) ->
                val prop = propValue as Map<*, *>
                assertNotNull("Property '$propKey' in tool '$name' must have a type", prop["type"])
                assertNotNull("Property '$propKey' in tool '$name' must have a description", prop["description"])
            }
        }
    }

    @Test
    fun `read_file tool has correct parameters`() {
        val tools = ToolDefinitions.allToolDefinitions()
        val readFile = findTool(tools, "read_file")
        val params = paramsOf(readFile)
        val props = params["properties"] as Map<*, *>
        val required = params["required"] as List<*>

        assertEquals(listOf("uri"), required)
        assertTrue(props.containsKey("uri"))
        assertTrue(props.containsKey("max_bytes"))

        val uriProp = props["uri"] as Map<*, *>
        assertEquals("string", uriProp["type"])

        val maxBytesProp = props["max_bytes"] as Map<*, *>
        assertEquals("integer", maxBytesProp["type"])
        assertEquals(1048576, maxBytesProp["maximum"])
    }

    @Test
    fun `write_file tool has correct parameters`() {
        val tools = ToolDefinitions.allToolDefinitions()
        val writeFile = findTool(tools, "write_file")
        val params = paramsOf(writeFile)
        val props = params["properties"] as Map<*, *>
        val required = params["required"] as List<*>

        assertEquals(listOf("uri", "content"), required)
        assertTrue(props.containsKey("uri"))
        assertTrue(props.containsKey("content"))
        assertTrue(props.containsKey("overwrite"))

        val contentProp = props["content"] as Map<*, *>
        assertEquals("string", contentProp["type"])

        val overwriteProp = props["overwrite"] as Map<*, *>
        assertEquals("boolean", overwriteProp["type"])
        assertEquals(false, overwriteProp["default"])
    }

    @Test
    fun `list_directory tool has correct parameters`() {
        val tools = ToolDefinitions.allToolDefinitions()
        val listDir = findTool(tools, "list_directory")
        val params = paramsOf(listDir)
        val props = params["properties"] as Map<*, *>
        val required = params["required"] as List<*>

        assertEquals(listOf("uri"), required)
        assertTrue(props.containsKey("uri"))
        assertTrue(props.containsKey("recursive"))
        assertTrue(props.containsKey("pattern"))

        val recursiveProp = props["recursive"] as Map<*, *>
        assertEquals("boolean", recursiveProp["type"])
        assertEquals(false, recursiveProp["default"])
    }

    @Test
    fun `execute_command tool has correct parameters`() {
        val tools = ToolDefinitions.allToolDefinitions()
        val execCmd = findTool(tools, "execute_command")
        val params = paramsOf(execCmd)
        val props = params["properties"] as Map<*, *>
        val required = params["required"] as List<*>

        assertEquals(listOf("command"), required)
        assertTrue(props.containsKey("command"))
        assertTrue(props.containsKey("cwd_uri"))
        assertTrue(props.containsKey("timeout_ms"))
        assertTrue(props.containsKey("context"))

        val commandProp = props["command"] as Map<*, *>
        assertEquals("string", commandProp["type"])

        val timeoutProp = props["timeout_ms"] as Map<*, *>
        assertEquals("integer", timeoutProp["type"])
        assertEquals(30000, timeoutProp["default"])

        val contextProp = props["context"] as Map<*, *>
        assertEquals("string", contextProp["type"])
        assertEquals(listOf("app", "container", "root"), contextProp["enum"])
        assertEquals("app", contextProp["default"])
    }

    @Test
    fun `search_files tool has correct parameters`() {
        val tools = ToolDefinitions.allToolDefinitions()
        val searchFiles = findTool(tools, "search_files")
        val params = paramsOf(searchFiles)
        val props = params["properties"] as Map<*, *>
        val required = params["required"] as List<*>

        assertEquals(listOf("root_uri", "pattern"), required)
        assertTrue(props.containsKey("root_uri"))
        assertTrue(props.containsKey("pattern"))
        assertTrue(props.containsKey("max_results"))

        val maxResultsProp = props["max_results"] as Map<*, *>
        assertEquals("integer", maxResultsProp["type"])
        assertEquals(100, maxResultsProp["default"])
    }

    @Test
    fun `get_file_info tool has correct parameters`() {
        val tools = ToolDefinitions.allToolDefinitions()
        val getFileInfo = findTool(tools, "get_file_info")
        val params = paramsOf(getFileInfo)
        val props = params["properties"] as Map<*, *>
        val required = params["required"] as List<*>

        assertEquals(listOf("uri"), required)
        assertEquals(1, props.size)
        assertTrue(props.containsKey("uri"))

        val uriProp = props["uri"] as Map<*, *>
        assertEquals("string", uriProp["type"])
    }

    @Test
    fun `serializes to JSON and round-trips through Gson`() {
        val tools = ToolDefinitions.allToolDefinitions()
        val json = gson.toJson(tools)

        assertNotNull("JSON output must not be null", json)
        assertTrue("JSON must not be empty", json.isNotEmpty())

        // Parse it back via JsonParser
        val parsed = JsonParser.parseString(json).asJsonArray
        assertEquals("Must have 6 elements after round-trip", 6, parsed.size())

        // Verify all tool names survive the round-trip
        val names = mutableSetOf<String>()
        parsed.forEach { element ->
            val obj = element.asJsonObject
            assertEquals("function", obj.get("type").asString)
            val func = obj.getAsJsonObject("function")
            names.add(func.get("name").asString)

            assertTrue("function.description must be a string", func.has("description"))
            val params = func.getAsJsonObject("parameters")
            assertEquals("object", params.get("type").asString)
            assertTrue("parameters.properties must exist", params.has("properties"))
            assertTrue("parameters.required must exist", params.has("required"))
        }

        val expected = setOf(
            "read_file", "write_file", "list_directory",
            "execute_command", "search_files", "get_file_info"
        )
        assertEquals(expected, names)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun findTool(
        tools: List<Map<String, Any?>>,
        name: String
    ): Map<String, Any?> {
        return tools.first { tool ->
            val func = tool["function"] as Map<*, *>
            func["name"] == name
        }
    }

    /** Convenience: extract the `parameters` sub-map from a tool definition. */
    private fun paramsOf(tool: Map<String, Any?>): Map<*, *> {
        val func = tool["function"] as Map<*, *>
        return func["parameters"] as Map<*, *>
    }
}
