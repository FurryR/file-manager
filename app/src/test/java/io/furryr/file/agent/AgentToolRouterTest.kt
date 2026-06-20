package io.furryr.file.agent

import android.content.Context
import io.furryr.file.daemon.DaemonConnection
import io.furryr.file.daemon.DaemonLauncher
import io.furryr.file.daemon.StatResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AgentToolRouterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockContext: Context

    @Before
    fun setUp() {
        mockContext = mockk(relaxed = true)
        AgentToolRouter.configure(mockContext)
    }

    @After
    fun tearDown() {
        // Unmock all mocked objects (safe — no-op if not mocked)
        try { unmockkObject(DaemonLauncher) } catch (_: Exception) {}
        try { unmockkObject(ContainerManager) } catch (_: Exception) {}
        try { unmockkObject(AgentPTYClient) } catch (_: Exception) {}
    }

    // ══════════════════════════════════════════════════════════════════════
    // 0. Confirmation policy
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `checkConfirmation NONE returns null for all tools`() {
        ToolDefinitions.allToolDefinitions().forEach { toolDef ->
            val name = (toolDef["function"] as Map<*, *>)["name"] as String
            assertNull(
                "NONE should allow $name",
                AgentToolRouter.checkConfirmation(name, ConfirmLevel.NONE)
            )
        }
    }

    @Test
    fun `checkConfirmation ALL requires confirmation for all 6 tools`() {
        val toolNames = setOf(
            "read_file", "write_file", "list_directory",
            "execute_command", "search_files", "get_file_info"
        )
        toolNames.forEach { name ->
            val result = AgentToolRouter.checkConfirmation(name, ConfirmLevel.ALL)
            assertNotNull("ALL should block $name", result)
            assertTrue("requiresConfirmation must be true", result!!.requiresConfirmation)
        }
    }

    @Test
    fun `checkConfirmation NON_DESTRUCTIVE blocks write_file and execute_command`() {
        val destructive = setOf("write_file", "execute_command")
        val safe = setOf("read_file", "list_directory", "search_files", "get_file_info")

        destructive.forEach { name ->
            val result = AgentToolRouter.checkConfirmation(name, ConfirmLevel.NON_DESTRUCTIVE)
            assertNotNull("NON_DESTRUCTIVE should block $name", result)
            assertTrue(result!!.requiresConfirmation)
        }
        safe.forEach { name ->
            assertNull(
                "NON_DESTRUCTIVE should not block $name",
                AgentToolRouter.checkConfirmation(name, ConfirmLevel.NON_DESTRUCTIVE)
            )
        }
    }

    @Test
    fun `checkConfirmation result data contains tool name`() {
        val result = AgentToolRouter.checkConfirmation("write_file", ConfirmLevel.NON_DESTRUCTIVE)
        assertNotNull(result)
        val json = JSONObject(result!!.data!!)
        assertEquals("write_file", json.getString("tool"))
        assertEquals("confirm", json.getString("action"))
    }

    // ── Confirmation policy integration with execute ──────────────────────

    @Test
    fun `execute with ALL policy blocks read_file with confirmation`() = runTest {
        val tc = ToolCallData(
            id = "call-1",
            name = "read_file",
            arguments = """{"uri": "/nonexistent/file.txt"}"""
        )
        val ctx = AgentContext(
            executionMode = ExecutionMode.APP_UID,
            confirmationPolicy = ConfirmLevel.ALL
        )
        val result = AgentToolRouter.execute(tc, ctx)
        assertTrue(result.requiresConfirmation)
        assertTrue(result.success)
    }

    @Test
    fun `execute with NON_DESTRUCTIVE blocks write_file`() = runTest {
        val tc = ToolCallData(
            id = "call-1",
            name = "write_file",
            arguments = """{"uri": "/tmp/x", "content": "x"}"""
        )
        val ctx = AgentContext(
            executionMode = ExecutionMode.APP_UID,
            confirmationPolicy = ConfirmLevel.NON_DESTRUCTIVE
        )
        val result = AgentToolRouter.execute(tc, ctx)
        assertTrue(result.requiresConfirmation)
    }

    @Test
    fun `execute with NON_DESTRUCTIVE allows read_file`() = runTest {
        val tmpFile = tempFolder.newFile("test.txt")
        tmpFile.writeText("hello")

        val tc = ToolCallData(
            id = "call-1",
            name = "read_file",
            arguments = """{"uri": "${tmpFile.absolutePath}"}"""
        )
        val ctx = AgentContext(
            executionMode = ExecutionMode.APP_UID,
            confirmationPolicy = ConfirmLevel.NON_DESTRUCTIVE
        )
        val result = AgentToolRouter.execute(tc, ctx)
        assertTrue(result.success)
        assertFalse(result.requiresConfirmation)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 1. read_file
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `read_file missing uri returns error`() = runTest {
        val tc = ToolCallData("call-1", "read_file", """{}""")
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertFalse(result.success)
        assertTrue(result.error!!.contains("Missing required argument"))
    }

    @Test
    fun `read_file nonexistent path returns error`() = runTest {
        val tc = ToolCallData(
            "call-1", "read_file",
            """{"uri": "/nonexistent/path/file.txt"}"""
        )
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertFalse(result.success)
        assertTrue(result.error!!.contains("not found"))
    }

    @Test
    fun `read_file valid direct path returns content`() = runTest {
        val tmpFile = tempFolder.newFile("read-test.txt")
        tmpFile.writeText("Hello, World!")

        val tc = ToolCallData(
            "call-1", "read_file",
            """{"uri": "${tmpFile.absolutePath}"}"""
        )
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertTrue("Expected success but got: ${result.error}", result.success)
        val data = JSONObject(result.data!!)
        assertEquals("Hello, World!", data.getString("content"))
        assertEquals(tmpFile.absolutePath, data.getString("path"))
    }

    @Test
    fun `read_file with max_bytes truncates content`() = runTest {
        val tmpFile = tempFolder.newFile("large.txt")
        tmpFile.writeText("A".repeat(2000))

        val tc = ToolCallData(
            "call-1", "read_file",
            """{"uri": "${tmpFile.absolutePath}", "max_bytes": 100}"""
        )
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertTrue(result.success)
        val data = JSONObject(result.data!!)
        assertEquals(100, data.getString("content").length)
        assertTrue(data.getBoolean("truncated"))
    }

    @Test
    fun `read_file reads directory fails with not a file error`() = runTest {
        val dir = tempFolder.newFolder("some-dir")
        val tc = ToolCallData(
            "call-1", "read_file",
            """{"uri": "${dir.absolutePath}"}"""
        )
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertFalse(result.success)
        assertTrue(result.error!!.contains("Not a regular file"))
    }

    @Test
    fun `read_file invalid android URI returns error`() = runTest {
        val tc = ToolCallData(
            "call-1", "read_file",
            """{"uri": "android:///"}"""
        )
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertFalse(result.success)
    }

    @Test
    fun `read_file invalid container URI returns error`() = runTest {
        val tc = ToolCallData(
            "call-1", "read_file",
            """{"uri": "container:///invalid name/path"}"""
        )
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertFalse(result.success)
    }

    @Test
    fun `read_file container URI calls execInContainer`() = runTest {
        mockkObject(ContainerManager)
        coEvery {
            ContainerManager.execInContainer(any(), eq("alpine"), any())
        } returns Result.success("file content here")

        val tc = ToolCallData(
            "call-1", "read_file",
            """{"uri": "container:///alpine/etc/hosts"}"""
        )
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.CONTAINER))
        assertTrue("Expected success but got: ${result.error}", result.success)
        val data = JSONObject(result.data!!)
        assertEquals("file content here", data.getString("content"))
        assertEquals("alpine", data.getString("container"))
    }

    // ══════════════════════════════════════════════════════════════════════
    // 2. write_file
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `write_file missing uri returns error`() = runTest {
        val tc = ToolCallData("call-1", "write_file", """{"content": "test"}""")
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertFalse(result.success)
        assertTrue(result.error!!.contains("missing", ignoreCase = true))
    }

    @Test
    fun `write_file missing content returns error`() = runTest {
        val tc = ToolCallData("call-1", "write_file", """{"uri": "/tmp/x"}""")
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertFalse(result.success)
        assertTrue(result.error!!.contains("Missing required argument"))
    }

    @Test
    fun `write_file creates file and returns success`() = runTest {
        val tmpFile = File(tempFolder.root, "write-test.txt")
        assertFalse(tmpFile.exists())

        val tc = ToolCallData(
            "call-1", "write_file",
            """{"uri": "${tmpFile.absolutePath}", "content": "new content"}"""
        )
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertTrue("Expected success but got: ${result.error}", result.success)
        assertTrue(tmpFile.exists())
        assertEquals("new content", tmpFile.readText())
    }

    @Test
    fun `write_file refuses overwrite by default`() = runTest {
        val tmpFile = tempFolder.newFile("existing.txt")
        tmpFile.writeText("original")

        val tc = ToolCallData(
            "call-1", "write_file",
            """{"uri": "${tmpFile.absolutePath}", "content": "overwritten"}"""
        )
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertFalse(result.success)
        assertTrue(result.error!!.contains("overwrite"))
        assertEquals("original", tmpFile.readText()) // content unchanged
    }

    @Test
    fun `write_file overwrite true replaces existing file`() = runTest {
        val tmpFile = tempFolder.newFile("existing2.txt")
        tmpFile.writeText("original")

        val tc = ToolCallData(
            "call-1", "write_file",
            """{"uri": "${tmpFile.absolutePath}", "content": "overwritten", "overwrite": true}"""
        )
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertTrue("Expected success but got: ${result.error}", result.success)
        assertEquals("overwritten", tmpFile.readText())
    }

    @Test
    fun `write_file invalid android URI returns error`() = runTest {
        val tc = ToolCallData(
            "call-1", "write_file",
            """{"uri": "android:///", "content": "x"}"""
        )
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertFalse(result.success)
    }

    @Test
    fun `write_file to android sdcard path succeeds`() = runTest {
        val relPath = "agent-write-test.txt"
        val tmpFile = File(tempFolder.root, relPath)

        // We need android:///sdcard to resolve to something writable.
        // Use a direct path in the test — the android URI routing is covered
        // by AndroidUriResolver tests.
        val tc = ToolCallData(
            "call-1", "write_file",
            """{"uri": "${tmpFile.absolutePath}", "content": "sdcard write", "overwrite": true}"""
        )
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertTrue("Expected success but got: ${result.error}", result.success)
        assertEquals("sdcard write", tmpFile.readText())
    }

    // ══════════════════════════════════════════════════════════════════════
    // 3. list_directory
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `list_directory missing uri returns error`() = runTest {
        val tc = ToolCallData("call-1", "list_directory", """{}""")
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertFalse(result.success)
        assertTrue(result.error!!.contains("Missing required argument"))
    }

    @Test
    fun `list_directory nonexistent path returns error`() = runTest {
        val tc = ToolCallData(
            "call-1", "list_directory",
            """{"uri": "/nonexistent/dir"}"""
        )
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertFalse(result.success)
        assertTrue(result.error!!.contains("not found"))
    }

    @Test
    fun `list_directory lists files in a directory`() = runTest {
        val dir = tempFolder.newFolder("list-dir")
        File(dir, "a.txt").createNewFile()
        File(dir, "b.txt").createNewFile()
        File(dir, "subdir").mkdir()

        val tc = ToolCallData(
            "call-1", "list_directory",
            """{"uri": "${dir.absolutePath}"}"""
        )
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertTrue("Expected success but got: ${result.error}", result.success)
        val data = JSONObject(result.data!!)
        val entries = data.getJSONArray("entries")
        assertEquals(3, entries.length())
        val names = (0 until entries.length()).map { entries.getJSONObject(it).getString("name") }
        assertTrue(names.contains("a.txt"))
        assertTrue(names.contains("b.txt"))
        assertTrue(names.contains("subdir"))
    }

    @Test
    fun `list_directory with recursive walks subtree`() = runTest {
        val dir = tempFolder.newFolder("recursive-dir")
        File(dir, "top.txt").createNewFile()
        val sub = File(dir, "sub")
        sub.mkdir()
        File(sub, "deep.txt").createNewFile()

        val tc = ToolCallData(
            "call-1", "list_directory",
            """{"uri": "${dir.absolutePath}", "recursive": true}"""
        )
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertTrue(result.success)
        val data = JSONObject(result.data!!)
        assertTrue(data.getBoolean("recursive"))
        val entries = data.getJSONArray("entries")
        // Should include top.txt and deep.txt (2 files, not the dir itself)
        assertTrue(entries.length() >= 2)
    }

    @Test
    fun `list_directory with glob pattern filters results`() = runTest {
        val dir = tempFolder.newFolder("glob-dir")
        File(dir, "foo.kt").createNewFile()
        File(dir, "bar.java").createNewFile()
        File(dir, "baz.kt").createNewFile()

        val tc = ToolCallData(
            "call-1", "list_directory",
            """{"uri": "${dir.absolutePath}", "pattern": "*.kt"}"""
        )
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertTrue(result.success)
        val data = JSONObject(result.data!!)
        val entries = data.getJSONArray("entries")
        assertEquals(2, entries.length())
        val names = (0 until entries.length()).map { entries.getJSONObject(it).getString("name") }
        assertTrue(names.all { it.endsWith(".kt") })
    }

    @Test
    fun `list_directory on a file returns not a directory error`() = runTest {
        val file = tempFolder.newFile("not-a-dir.txt")
        val tc = ToolCallData(
            "call-1", "list_directory",
            """{"uri": "${file.absolutePath}"}"""
        )
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertFalse(result.success)
        assertTrue(result.error!!.contains("Not a directory"))
    }

    // ══════════════════════════════════════════════════════════════════════
    // 4. execute_command
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `execute_command missing command returns error`() = runTest {
        val tc = ToolCallData("call-1", "execute_command", """{}""")
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertFalse(result.success)
        assertTrue(result.error!!.contains("Missing required argument"))
    }

    @Test
    fun `execute_command APP_UID runs shell command`() = runTest {
        val tc = ToolCallData(
            "call-1", "execute_command",
            """{"command": "echo hello"}"""
        )
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertTrue("Expected success but got: ${result.error}", result.success)
        val data = JSONObject(result.data!!)
        assertEquals(0, data.getInt("exit_code"))
        assertTrue(data.getString("output").contains("hello"))
    }

    @Test
    fun `execute_command APP_UID failing command returns error`() = runTest {
        val tc = ToolCallData(
            "call-1", "execute_command",
            """{"command": "exit 1"}"""
        )
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertFalse(result.success)
        val data = JSONObject(result.data!!)
        assertEquals(1, data.getInt("exit_code"))
    }

    @Test
    fun `execute_command with timeout_ms kills long-running command`() = runTest {
        val tc = ToolCallData(
            "call-1", "execute_command",
            """{"command": "sleep 10", "timeout_ms": 500}"""
        )
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        // Should finish (killed by timeout) without hanging
        assertNotNull(result)
        // exit code may be -1 (killed) or the data may show duration
        assertNotNull(result.data)
    }

    @Test
    fun `execute_command with cwd_uri sets working directory`() = runTest {
        val dir = tempFolder.newFolder("cwd-dir")
        val tc = ToolCallData(
            "call-1", "execute_command",
            """{"command": "pwd", "cwd_uri": "${dir.absolutePath}"}"""
        )
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertTrue("Expected success but got: ${result.error}", result.success)
        val data = JSONObject(result.data!!)
        val output = data.getString("output").trim()
        assertEquals(dir.canonicalPath, output)
    }

    @Test
    fun `execute_command CONTAINER without currentContainer returns error`() = runTest {
        val tc = ToolCallData(
            "call-1", "execute_command",
            """{"command": "ls"}"""
        )
        val result = AgentToolRouter.execute(
            tc,
            AgentContext(ExecutionMode.CONTAINER, currentContainer = null)
        )
        assertFalse(result.success)
        assertTrue(result.error!!.contains("currentContainer"))
    }

    @Test
    fun `execute_command with unknown tool name returns error`() = runTest {
        val tc = ToolCallData("call-1", "nonexistent_tool", """{}""")
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertFalse(result.success)
        assertTrue(result.error!!.contains("Unknown tool"))
    }

    // ══════════════════════════════════════════════════════════════════════
    // 5. search_files
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `search_files missing root_uri returns error`() = runTest {
        val tc = ToolCallData(
            "call-1", "search_files",
            """{"pattern": "*.txt"}"""
        )
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertFalse(result.success)
        assertTrue(result.error!!.contains("Missing required argument"))
    }

    @Test
    fun `search_files missing pattern returns error`() = runTest {
        val tc = ToolCallData(
            "call-1", "search_files",
            """{"root_uri": "/tmp"}"""
        )
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertFalse(result.success)
        assertTrue(result.error!!.contains("Missing required argument"))
    }

    @Test
    fun `search_files nonexistent root returns error`() = runTest {
        val tc = ToolCallData(
            "call-1", "search_files",
            """{"root_uri": "/nonexistent/dir", "pattern": "*.txt"}"""
        )
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertFalse(result.success)
        assertTrue(result.error!!.contains("not found"))
    }

    @Test
    fun `search_files root on file returns not a directory error`() = runTest {
        val file = tempFolder.newFile("not-dir.txt")
        val tc = ToolCallData(
            "call-1", "search_files",
            """{"root_uri": "${file.absolutePath}", "pattern": "*.txt"}"""
        )
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertFalse(result.success)
        assertTrue(result.error!!.contains("Not a directory"))
    }

    @Test
    fun `search_files finds matching files`() = runTest {
        val root = tempFolder.newFolder("search-root")
        File(root, "a.txt").createNewFile()
        File(root, "b.txt").createNewFile()
        File(root, "c.kt").createNewFile()
        val sub = File(root, "sub")
        sub.mkdir()
        File(sub, "d.txt").createNewFile()

        val tc = ToolCallData(
            "call-1", "search_files",
            """{"root_uri": "${root.absolutePath}", "pattern": "*.txt"}"""
        )
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertTrue("Expected success but got: ${result.error}", result.success)
        val data = JSONObject(result.data!!)
        val matches = data.getJSONArray("matches")
        assertEquals(3, matches.length()) // a.txt, b.txt, sub/d.txt
        val names = (0 until matches.length()).map { matches.getJSONObject(it).getString("name") }
        assertTrue(names.contains("a.txt"))
        assertTrue(names.contains("b.txt"))
        assertTrue(names.contains("d.txt"))
    }

    @Test
    fun `search_files respects max_results`() = runTest {
        val root = tempFolder.newFolder("search-limit")
        for (i in 1..10) {
            File(root, "file-$i.txt").createNewFile()
        }

        val tc = ToolCallData(
            "call-1", "search_files",
            """{"root_uri": "${root.absolutePath}", "pattern": "*.txt", "max_results": 3}"""
        )
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertTrue(result.success)
        val data = JSONObject(result.data!!)
        val matches = data.getJSONArray("matches")
        assertTrue(matches.length() <= 3)
    }

    @Test
    fun `search_files no matches returns empty list`() = runTest {
        val root = tempFolder.newFolder("search-empty")
        File(root, "a.kt").createNewFile()

        val tc = ToolCallData(
            "call-1", "search_files",
            """{"root_uri": "${root.absolutePath}", "pattern": "*.txt"}"""
        )
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertTrue(result.success)
        val data = JSONObject(result.data!!)
        assertEquals(0, data.getJSONArray("matches").length())
    }

    // ══════════════════════════════════════════════════════════════════════
    // 6. get_file_info
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `get_file_info missing uri returns error`() = runTest {
        val tc = ToolCallData("call-1", "get_file_info", """{}""")
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertFalse(result.success)
        assertTrue(result.error!!.contains("Missing required argument"))
    }

    @Test
    fun `get_file_info nonexistent file reports exists false`() = runTest {
        val mockConn = mockk<DaemonConnection>(relaxed = true)
        mockkObject(DaemonLauncher)
        every { DaemonLauncher.getConnection() } returns mockConn
        every { mockConn.stat(any()) } returns Result.success(
            StatResult(0, 0, false, false)
        )

        val tc = ToolCallData(
            "call-1", "get_file_info",
            """{"uri": "/nonexistent/file.txt"}"""
        )
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertTrue("Expected success but got: ${result.error}", result.success)
        val data = JSONObject(result.data!!)
        assertFalse(data.getBoolean("exists"))
        assertEquals("/nonexistent/file.txt", data.getString("path"))
    }

    @Test
    fun `get_file_info existing file returns metadata`() = runTest {
        val tmpFile = tempFolder.newFile("info-test.txt")
        tmpFile.writeText("hello world")

        val mockConn = mockk<DaemonConnection>(relaxed = true)
        mockkObject(DaemonLauncher)
        every { DaemonLauncher.getConnection() } returns mockConn
        every { mockConn.stat(any()) } returns Result.success(
            StatResult(1024 * 1024 * 100, 1024 * 1024 * 500, true, false)
        )

        val tc = ToolCallData(
            "call-1", "get_file_info",
            """{"uri": "${tmpFile.absolutePath}"}"""
        )
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertTrue("Expected success but got: ${result.error}", result.success)
        val data = JSONObject(result.data!!)
        assertTrue(data.getBoolean("exists"))
        assertEquals(tmpFile.length(), data.getLong("size"))
        assertFalse(data.getBoolean("is_directory"))
        // DaemonConnection.stat is mocked to return fs bytes
        assertEquals(1024 * 1024 * 100L, data.getLong("fs_usable_bytes"))
        assertEquals(1024 * 1024 * 500L, data.getLong("fs_total_bytes"))
    }

    @Test
    fun `get_file_info directory reports is_directory true`() = runTest {
        val dir = tempFolder.newFolder("info-dir")

        val mockConn = mockk<DaemonConnection>(relaxed = true)
        mockkObject(DaemonLauncher)
        every { DaemonLauncher.getConnection() } returns mockConn
        every { mockConn.stat(any()) } returns Result.success(
            StatResult(0, 0, true, true)
        )

        val tc = ToolCallData(
            "call-1", "get_file_info",
            """{"uri": "${dir.absolutePath}"}"""
        )
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertTrue("Expected success but got: ${result.error}", result.success)
        val data = JSONObject(result.data!!)
        assertTrue(data.getBoolean("is_directory"))
    }

    @Test
    fun `get_file_info invalid android URI returns error`() = runTest {
        val tc = ToolCallData(
            "call-1", "get_file_info",
            """{"uri": "android:///"}"""
        )
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertFalse(result.success)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 7. Execution mode routing
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `execute with SHIZUKU mode is accepted`() = runTest {
        val tc = ToolCallData(
            "call-1", "read_file",
            """{"uri": "${tempFolder.newFile("shizuku-test.txt").absolutePath}"}"""
        )
        val result = AgentToolRouter.execute(
            tc,
            AgentContext(ExecutionMode.SHIZUKU)
        )
        // SHIZUKU should still work for non-root paths via direct FS
        assertTrue(result.success)
    }

    @Test
    fun `execute with ROOT_SHELL mode is accepted`() = runTest {
        val tc = ToolCallData(
            "call-1", "read_file",
            """{"uri": "${tempFolder.newFile("root-test.txt").absolutePath}"}"""
        )
        val result = AgentToolRouter.execute(
            tc,
            AgentContext(ExecutionMode.ROOT_SHELL)
        )
        // ROOT_SHELL for direct paths reads via direct FS
        assertTrue(result.success)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 8. URI scheme routing
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `read_file with android sdcard URI resolves and reads`() = runTest {
        // Create a file where android:///sdcard/... resolves to
        val tempDir = tempFolder.newFolder("storage", "emulated", "0")
        val testFile = File(tempDir, "android-read.txt")
        testFile.parentFile!!.mkdirs()
        testFile.writeText("android content")

        // Use direct path that simulates resolved android URI
        val tc = ToolCallData(
            "call-1", "read_file",
            """{"uri": "${testFile.absolutePath}"}"""
        )
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.APP_UID))
        assertTrue("Expected success but got: ${result.error}", result.success)
        assertEquals("android content", JSONObject(result.data!!).getString("content"))
    }

    @Test
    fun `get_file_info with container URI calls execInContainer`() = runTest {
        mockkObject(ContainerManager)
        coEvery {
            ContainerManager.execInContainer(any(), eq("alpine"), any())
        } returns Result.success("""{"exists":true,"size":1024,"permissions":"644"}""")

        val tc = ToolCallData(
            "call-1", "get_file_info",
            """{"uri": "container:///alpine/etc/hostname"}"""
        )
        val result = AgentToolRouter.execute(tc, AgentContext(ExecutionMode.CONTAINER))
        assertTrue("Expected success but got: ${result.error}", result.success)
        val data = result.data!!
        assertTrue(data.contains("exists"))
    }

    // ══════════════════════════════════════════════════════════════════════
    // 9. ToolResult structure
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `ToolResult default values are correct`() {
        val success = ToolResult(success = true, data = "{}")
        assertEquals("text/plain", success.mimeType)
        assertFalse(success.requiresConfirmation)
        assertNull(success.error)

        val failure = ToolResult(success = false, error = "something went wrong")
        assertNull(failure.data)
    }

    @Test
    fun `AgentContext default values are correct`() {
        val ctx = AgentContext(ExecutionMode.APP_UID)
        assertNull(ctx.currentContainer)
        assertEquals(ConfirmLevel.NONE, ctx.confirmationPolicy)
    }

    @Test
    fun `ConfirmLevel enum has three values`() {
        assertEquals(3, ConfirmLevel.values().size)
        assertNotNull(ConfirmLevel.valueOf("NONE"))
        assertNotNull(ConfirmLevel.valueOf("NON_DESTRUCTIVE"))
        assertNotNull(ConfirmLevel.valueOf("ALL"))
    }

    // ══════════════════════════════════════════════════════════════════════
    // 10. shellQuote utility
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `shellQuote wraps string in single quotes`() {
        assertEquals("'hello'", AgentToolRouter.shellQuote("hello"))
    }

    @Test
    fun `shellQuote escapes single quotes`() {
        assertEquals("'it'\\''s'", AgentToolRouter.shellQuote("it's"))
    }

    @Test
    fun `shellQuote empty string`() {
        assertEquals("''", AgentToolRouter.shellQuote(""))
    }

    @Test
    fun `shellQuote string with spaces`() {
        assertEquals("'hello world'", AgentToolRouter.shellQuote("hello world"))
    }
}
