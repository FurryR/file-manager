package io.furryr.file.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class BlockModelsTest {

    @Test
    fun `AiBlock can be instantiated with all fields`() {
        val toolCall = ToolCall(id = "tc1", name = "read_file", arguments = mapOf("path" to "/tmp"))
        val block = AiBlock(
            content = "Hello, world!",
            role = Role.ASSISTANT,
            toolCalls = listOf(toolCall),
            status = BlockStatus.COMPLETE,
            id = UUID.randomUUID().toString(),
            timestamp = 1_000_000L,
        )
        assertEquals("Hello, world!", block.content)
        assertEquals(Role.ASSISTANT, block.role)
        assertEquals(1, block.toolCalls?.size)
        assertEquals("tc1", block.toolCalls?.first()?.id)
        assertEquals("read_file", block.toolCalls?.first()?.name)
        assertEquals("/tmp", block.toolCalls?.first()?.arguments?.get("path"))
        assertEquals(BlockStatus.COMPLETE, block.status)
    }

    @Test
    fun `AiBlock allows null toolCalls`() {
        val block = AiBlock(
            content = "No tools",
            role = Role.USER,
            toolCalls = null,
            status = BlockStatus.COMPLETE,
            id = UUID.randomUUID().toString(),
            timestamp = 1_000_000L,
        )
        assertNull(block.toolCalls)
    }

    @Test
    fun `AiBlock supports STREAMING status`() {
        val block = AiBlock(
            content = "Streaming...",
            role = Role.ASSISTANT,
            toolCalls = null,
            status = BlockStatus.STREAMING,
            id = UUID.randomUUID().toString(),
            timestamp = 1_000_000L,
        )
        assertEquals(BlockStatus.STREAMING, block.status)
    }

    @Test
    fun `AiBlock supports FAILED status`() {
        val block = AiBlock(
            content = "",
            role = Role.SYSTEM,
            toolCalls = null,
            status = BlockStatus.FAILED,
            id = UUID.randomUUID().toString(),
            timestamp = 1_000_000L,
        )
        assertEquals(BlockStatus.FAILED, block.status)
    }

    @Test
    fun `CommandBlock can be instantiated with exitCode and duration`() {
        val block = CommandBlock(
            command = "ls -la",
            cwd = "/home",
            mode = ExecutionMode.ROOT_SHELL,
            status = CommandStatus.SUCCESS,
            exitCode = 0,
            duration = 1234L,
            id = UUID.randomUUID().toString(),
            timestamp = 2_000_000L,
        )
        assertEquals("ls -la", block.command)
        assertEquals("/home", block.cwd)
        assertEquals(ExecutionMode.ROOT_SHELL, block.mode)
        assertEquals(CommandStatus.SUCCESS, block.status)
        assertEquals(0, block.exitCode)
        assertEquals(1234L, block.duration)
    }

    @Test
    fun `CommandBlock allows null exitCode and duration when PENDING`() {
        val block = CommandBlock(
            command = "sleep 10",
            cwd = "/tmp",
            mode = ExecutionMode.APP_UID,
            status = CommandStatus.PENDING,
            exitCode = null,
            duration = null,
            id = UUID.randomUUID().toString(),
            timestamp = 3_000_000L,
        )
        assertNull(block.exitCode)
        assertNull(block.duration)
        assertEquals(CommandStatus.PENDING, block.status)
    }

    @Test
    fun `CommandBlock supports all execution modes`() {
        for (mode in ExecutionMode.values()) {
            val block = CommandBlock(
                command = "echo test",
                cwd = "/",
                mode = mode,
                status = CommandStatus.PENDING,
                exitCode = null,
                duration = null,
                id = UUID.randomUUID().toString(),
                timestamp = 1L,
            )
            assertEquals(mode, block.mode)
        }
    }

    @Test
    fun `CommandBlock supports all command statuses`() {
        for (status in CommandStatus.values()) {
            val block = CommandBlock(
                command = "echo test",
                cwd = "/",
                mode = ExecutionMode.APP_UID,
                status = status,
                exitCode = null,
                duration = null,
                id = UUID.randomUUID().toString(),
                timestamp = 1L,
            )
            assertEquals(status, block.status)
        }
    }

    @Test
    fun `OutputBlock can be instantiated`() {
        val block = OutputBlock(
            sessionId = "ses_001",
            isFullscreen = true,
            byteOffset = 0L,
            byteLength = 4096L,
            id = UUID.randomUUID().toString(),
            timestamp = 3_000_000L,
        )
        assertEquals("ses_001", block.sessionId)
        assertTrue(block.isFullscreen)
        assertEquals(0L, block.byteOffset)
        assertEquals(4096L, block.byteLength)
    }

    @Test
    fun `OutputBlock supports non-fullscreen mode`() {
        val block = OutputBlock(
            sessionId = "ses_002",
            isFullscreen = false,
            byteOffset = 100L,
            byteLength = 200L,
            id = UUID.randomUUID().toString(),
            timestamp = 4_000_000L,
        )
        assertEquals(false, block.isFullscreen)
    }

    @Test
    fun `ExitStatusBlock can be instantiated`() {
        val block = ExitStatusBlock(
            exitCode = 1,
            duration = 5678L,
            id = UUID.randomUUID().toString(),
            timestamp = 4_000_000L,
        )
        assertEquals(1, block.exitCode)
        assertEquals(5678L, block.duration)
    }

    @Test
    fun `SuggestionBlock can be instantiated`() {
        val block = SuggestionBlock(
            suggestions = listOf("ls -la", "pwd", "cat file"),
            id = UUID.randomUUID().toString(),
            timestamp = 5_000_000L,
        )
        assertEquals(3, block.suggestions.size)
        assertEquals("ls -la", block.suggestions[0])
        assertEquals("pwd", block.suggestions[1])
        assertEquals("cat file", block.suggestions[2])
    }

    @Test
    fun `SuggestionBlock supports empty suggestions`() {
        val block = SuggestionBlock(
            suggestions = emptyList(),
            id = UUID.randomUUID().toString(),
            timestamp = 6_000_000L,
        )
        assertTrue(block.suggestions.isEmpty())
    }

    @Test
    fun `ConfirmBlock can be instantiated`() {
        var invoked = false
        val callback: (Boolean) -> Unit = { confirmed ->
            invoked = confirmed
        }
        val block = ConfirmBlock(
            action = "delete_file",
            description = "Delete /tmp/test?",
            callback = callback,
            id = UUID.randomUUID().toString(),
            timestamp = 6_000_000L,
        )
        assertEquals("delete_file", block.action)
        assertEquals("Delete /tmp/test?", block.description)
        // invoke the callback
        block.callback(true)
        assertTrue(invoked)
    }

    @Test
    fun `Block subclasses have unique ids`() {
        val blocks: List<Block> = listOf(
            AiBlock("a", Role.ASSISTANT, null, BlockStatus.COMPLETE, "id1", 1L),
            CommandBlock("ls", "/", ExecutionMode.APP_UID, CommandStatus.PENDING, null, null, "id2", 2L),
            OutputBlock("s1", false, 0L, 0L, "id3", 3L),
            ExitStatusBlock(0, 100L, "id4", 4L),
            SuggestionBlock(emptyList(), "id5", 5L),
            ConfirmBlock("a", "d", {}, "id6", 6L),
        )
        val ids = blocks.map { it.id }.distinct()
        assertEquals(6, ids.size)
    }

    @Test
    fun `ToolCall can hold complex arguments`() {
        val args: Map<String, Any?> = mapOf(
            "path" to "/data/file.txt",
            "recursive" to true,
            "depth" to 3,
            "nullField" to null,
        )
        val toolCall = ToolCall(id = "tc2", name = "search", arguments = args)
        assertEquals("/data/file.txt", toolCall.arguments["path"])
        assertEquals(true, toolCall.arguments["recursive"])
        assertEquals(3, toolCall.arguments["depth"])
        assertNull(toolCall.arguments["nullField"])
    }

    @Test
    fun `Enum Role has three values`() {
        assertEquals(3, Role.values().size)
        assertTrue(Role.values().toList().containsAll(listOf(Role.USER, Role.ASSISTANT, Role.SYSTEM)))
    }

    @Test
    fun `Enum BlockStatus has three values`() {
        assertEquals(3, BlockStatus.values().size)
        assertTrue(BlockStatus.values().toList().containsAll(listOf(BlockStatus.STREAMING, BlockStatus.COMPLETE, BlockStatus.FAILED)))
    }

    @Test
    fun `Enum CommandStatus has four values`() {
        assertEquals(4, CommandStatus.values().size)
        assertTrue(CommandStatus.values().toList().containsAll(listOf(CommandStatus.PENDING, CommandStatus.RUNNING, CommandStatus.SUCCESS, CommandStatus.FAILED)))
    }

    @Test
    fun `Enum ExecutionMode has four values`() {
        assertEquals(4, ExecutionMode.values().size)
        assertTrue(ExecutionMode.values().toList().containsAll(listOf(ExecutionMode.APP_UID, ExecutionMode.CONTAINER, ExecutionMode.ROOT_SHELL, ExecutionMode.SHIZUKU)))
    }

    @Test
    fun `Block is a sealed class with 6 subclasses`() {
        // Verify the sealed hierarchy by checking that all subclasses compile as Block type
        val ai: Block = AiBlock("", Role.USER, null, BlockStatus.COMPLETE, "", 0L)
        val cmd: Block = CommandBlock("", "", ExecutionMode.APP_UID, CommandStatus.PENDING, null, null, "", 0L)
        val out: Block = OutputBlock("", false, 0L, 0L, "", 0L)
        val exit: Block = ExitStatusBlock(0, 0L, "", 0L)
        val sug: Block = SuggestionBlock(emptyList(), "", 0L)
        val confirm: Block = ConfirmBlock("", "", {}, "", 0L)
        assertNotNull(ai)
        assertNotNull(cmd)
        assertNotNull(out)
        assertNotNull(exit)
        assertNotNull(sug)
        assertNotNull(confirm)
    }
}
