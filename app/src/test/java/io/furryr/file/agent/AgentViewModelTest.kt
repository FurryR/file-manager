package io.furryr.file.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [AgentViewModelImpl] covering block management, command flow,
 * AI streaming, tool routing, confirmation handling, and mode switching.
 *
 * All external dependencies ([AIClient], [AgentToolRouter], [SessionManager])
 * are mocked via mockk to ensure test isolation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AgentViewModelTest {

    private lateinit var context: Context
    private lateinit var mockAiClient: AIClient
    private lateinit var viewModel: AgentViewModelImpl

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        mockAiClient = mockk(relaxed = true)
        every { mockAiClient.provider } returns "openai"

        // Ensure AgentToolRouter is configured for tests
        runCatching { AgentToolRouter.configure(context) }

        viewModel = AgentViewModelImpl(context, mockAiClient, testDispatcher)
    }

    @After
    fun tearDown() {
        viewModel.destroy()
        Dispatchers.resetMain()
        runCatching { unmockkObject(AgentToolRouter) }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 1. Block management
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `blocks list starts empty`() {
        assertTrue(viewModel.blocks.isEmpty())
    }

    @Test
    fun `addBlock appends to blocks list`() = runTest {
        val initialCount = viewModel.blocks.size

        viewModel.onModeChanged(ExecutionMode.APP_UID)
        advanceUntilIdle()

        assertTrue(viewModel.blocks.size > initialCount)
    }

    @Test
    fun `clearBlocks removes all blocks`() = runTest {
        viewModel.onModeChanged(ExecutionMode.APP_UID)
        advanceUntilIdle()
        assertTrue(viewModel.blocks.isNotEmpty())

        viewModel.clearBlocks()
        assertTrue(viewModel.blocks.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. Command flow
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `onSendCommand adds CommandBlock with RUNNING status`() = runTest {
        viewModel.onSendCommand("ls -la", ExecutionMode.APP_UID)
        advanceUntilIdle()

        val cmdBlocks = viewModel.blocks.filterIsInstance<CommandBlock>()
        assertEquals(1, cmdBlocks.size)
        assertEquals("ls -la", cmdBlocks[0].command)
        assertEquals(ExecutionMode.APP_UID, cmdBlocks[0].mode)
    }

    @Test
    fun `command flow completes with ExitStatusBlock`() = runTest {
        mockkObject(AgentToolRouter)
        every {
            AgentToolRouter.executeShellCommand(any(), any(), any(), any())
        } returns ToolResult(
            success = true,
            data = """{"exit_code":0,"output":"total 42"}"""
        )

        val initialCount = viewModel.blocks.size
        viewModel.onSendCommand("ls -la", ExecutionMode.APP_UID)
        advanceUntilIdle()

        // Should have: CommandBlock + OutputBlock + ExitStatusBlock
        assertTrue(viewModel.blocks.size >= initialCount + 3)

        val exitBlocks = viewModel.blocks.filterIsInstance<ExitStatusBlock>()
        assertTrue(
            "Should contain at least one ExitStatusBlock",
            exitBlocks.isNotEmpty()
        )

        val cmdBlocks = viewModel.blocks.filterIsInstance<CommandBlock>()
        val finalCmd = cmdBlocks.lastOrNull()
        assertNotNull(finalCmd)
        assertTrue(
            "Command should end SUCCESS or FAILED, was ${finalCmd!!.status}",
            finalCmd.status == CommandStatus.SUCCESS || finalCmd.status == CommandStatus.FAILED
        )

        unmockkObject(AgentToolRouter)
    }

    @Test
    fun `command flow handles failure`() = runTest {
        mockkObject(AgentToolRouter)
        every {
            AgentToolRouter.executeShellCommand(any(), any(), any(), any())
        } returns ToolResult(
            success = false,
            error = "Permission denied"
        )

        viewModel.onSendCommand("cat /etc/shadow", ExecutionMode.APP_UID)
        advanceUntilIdle()

        val cmdBlocks = viewModel.blocks.filterIsInstance<CommandBlock>()
        val finalCmd = cmdBlocks.lastOrNull()
        assertNotNull(finalCmd)

        val hasFailedExit = viewModel.blocks.any {
            it is ExitStatusBlock && it.exitCode != 0
        } || viewModel.blocks.any {
            it is AiBlock && it.status == BlockStatus.FAILED
        }
        assertTrue("Should have failure indication", hasFailedExit)

        unmockkObject(AgentToolRouter)
    }

    @Test
    fun `command flow uses correct execution mode`() = runTest {
        mockkObject(AgentToolRouter)
        val capturedModes = mutableListOf<AgentContext>()

        every {
            AgentToolRouter.executeShellCommand(any(), any(), any(), capture(capturedModes))
        } returns ToolResult(success = true, data = """{"exit_code":0,"output":""}""")

        viewModel.onSendCommand("id", ExecutionMode.ROOT_SHELL)
        advanceUntilIdle()

        assertEquals(1, capturedModes.size)
        assertEquals(ExecutionMode.ROOT_SHELL, capturedModes[0].executionMode)

        unmockkObject(AgentToolRouter)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. AI flow
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `onAskAi adds user block and streaming assistant block`() = runTest {
        coEvery {
            mockAiClient.sendMessage(any(), any(), any())
        } coAnswers {
            val onEvent = thirdArg<(AiEvent) -> Unit>()
            onEvent(AiEvent.Content("Hello!"))
            onEvent(AiEvent.Done)
            Result.success("Hello!")
        }

        val countBefore = viewModel.blocks.size
        viewModel.onAskAi("How are you?")
        advanceUntilIdle()

        assertTrue(viewModel.blocks.size > countBefore)

        val userBlocks = viewModel.blocks.filterIsInstance<AiBlock>().filter { it.role == Role.USER }
        assertTrue(userBlocks.isNotEmpty())
        assertEquals("How are you?", userBlocks.last().content)
    }

    @Test
    fun `AI streaming updates block content incrementally`() = runTest {
        coEvery {
            mockAiClient.sendMessage(any(), any(), any())
        } coAnswers {
            val onEvent = thirdArg<(AiEvent) -> Unit>()
            onEvent(AiEvent.Content("Hello"))
            onEvent(AiEvent.Content(" world"))
            onEvent(AiEvent.Content("!"))
            onEvent(AiEvent.Done)
            Result.success("Hello world!")
        }

        viewModel.onAskAi("Say hello")
        advanceUntilIdle()

        val assistantBlocks = viewModel.blocks.filterIsInstance<AiBlock>()
            .filter { it.role == Role.ASSISTANT }
        val streamingBlock = assistantBlocks.lastOrNull()
        assertNotNull(streamingBlock)
        assertTrue(
            "Expected 'Hello world!' in content, got: ${streamingBlock!!.content}",
            streamingBlock.content.contains("Hello world!")
        )
        assertEquals(BlockStatus.COMPLETE, streamingBlock.status)
    }

    @Test
    fun `AI error marks block as FAILED`() = runTest {
        coEvery {
            mockAiClient.sendMessage(any(), any(), any())
        } coAnswers {
            val onEvent = thirdArg<(AiEvent) -> Unit>()
            onEvent(AiEvent.Error("Rate limit exceeded"))
            onEvent(AiEvent.Done)
            Result.success("")
        }

        viewModel.onAskAi("test")
        advanceUntilIdle()

        val failedBlocks = viewModel.blocks.filterIsInstance<AiBlock>()
            .filter { it.status == BlockStatus.FAILED }
        assertTrue(
            "Should have at least one FAILED block, got ${failedBlocks.size}",
            failedBlocks.isNotEmpty()
        )
    }

    @Test
    fun `onAskAi with blank prompt is no-op`() = runTest {
        val countBefore = viewModel.blocks.size
        viewModel.onAskAi("   ")
        advanceUntilIdle()

        assertEquals(countBefore, viewModel.blocks.size)
    }

    @Test
    fun `onAskAi without aiClient produces system error block`() = runTest {
        val vmNoAi = AgentViewModelImpl(context, aiClient = null, ioDispatcher = testDispatcher)
        vmNoAi.onAskAi("test")
        // No coroutines launched, result is synchronous
        val errorBlocks = vmNoAi.blocks.filterIsInstance<AiBlock>()
            .filter { it.role == Role.SYSTEM && it.status == BlockStatus.FAILED }
        assertTrue(
            "Should produce error block when AIClient is null",
            errorBlocks.isNotEmpty()
        )
        vmNoAi.destroy()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. Tool call handling
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `AI tool call triggers AgentToolRouter`() = runTest {
        mockkObject(AgentToolRouter)
        coEvery {
            AgentToolRouter.execute(any(), any())
        } returns ToolResult(
            success = true,
            data = """{"path":"/tmp","entries":[]}"""
        )

        coEvery {
            mockAiClient.sendMessage(any(), any(), any())
        } coAnswers {
            val onEvent = thirdArg<(AiEvent) -> Unit>()
            onEvent(AiEvent.Content("Let me check..."))
            onEvent(AiEvent.ToolCall("call_1", "list_directory", """{"uri":"/tmp"}"""))
            onEvent(AiEvent.Done)
            Result.success("Let me check...")
        }

        viewModel.onAskAi("List /tmp")
        advanceUntilIdle()

        // Verify tool was dispatched
        coVerify(atLeast = 1) {
            AgentToolRouter.execute(
                match { it.name == "list_directory" },
                any()
            )
        }

        unmockkObject(AgentToolRouter)
    }

    @Test
    fun `tool call requiring confirmation adds ConfirmBlock`() = runTest {
        mockkObject(AgentToolRouter)
        coEvery {
            AgentToolRouter.execute(any(), any())
        } returns ToolResult(
            success = true,
            data = """{"tool":"write_file","action":"confirm"}""",
            requiresConfirmation = true
        )

        coEvery {
            mockAiClient.sendMessage(any(), any(), any())
        } coAnswers {
            val onEvent = thirdArg<(AiEvent) -> Unit>()
            onEvent(AiEvent.Content("I'll write that file."))
            onEvent(AiEvent.ToolCall("call_1", "write_file", """{"uri":"/tmp/test.txt","content":"hi"}"""))
            onEvent(AiEvent.Done)
            Result.success("I'll write that file.")
        }

        viewModel.onAskAi("Write test file")
        advanceUntilIdle()

        val confirmBlocks = viewModel.blocks.filterIsInstance<ConfirmBlock>()
        assertTrue(
            "Should have a ConfirmBlock for write_file, got ${confirmBlocks.size}",
            confirmBlocks.isNotEmpty()
        )
        assertEquals("write_file", confirmBlocks.last().action)

        unmockkObject(AgentToolRouter)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 5. Confirmation flow
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `onConfirmAction denied adds system block`() = runTest {
        val confirmBlock = ConfirmBlock(
            action = "write_file",
            description = "Write to /system?",
            callback = {},
            id = "confirm-1",
            timestamp = System.currentTimeMillis()
        )

        val countBefore = viewModel.blocks.size
        viewModel.onConfirmAction(confirmed = false, block = confirmBlock)
        advanceUntilIdle()

        assertTrue(viewModel.blocks.size > countBefore)

        val deniedBlock = viewModel.blocks.lastOrNull() as? AiBlock
        assertNotNull(deniedBlock)
        assertEquals(Role.SYSTEM, deniedBlock!!.role)
        assertTrue(deniedBlock.content.contains("denied", ignoreCase = true))
    }

    @Test
    fun `onConfirmAction confirmed executes pending tool`() = runTest {
        // First, set up the pending state by triggering a tool call that requires confirmation
        mockkObject(AgentToolRouter)

        // First call: requires confirmation
        coEvery {
            AgentToolRouter.execute(
                match { it.name == "write_file" },
                any()
            )
        } returns ToolResult(
            success = true,
            data = """{"tool":"write_file","action":"confirm"}""",
            requiresConfirmation = true
        ) andThen ToolResult(
            success = true,
            data = """{"path":"/tmp/test.txt","bytes_written":5}"""
        )

        coEvery {
            mockAiClient.sendMessage(any(), any(), any())
        } coAnswers {
            val onEvent = thirdArg<(AiEvent) -> Unit>()
            onEvent(AiEvent.ToolCall("call_1", "write_file", """{"uri":"/tmp/test.txt","content":"hi"}"""))
            onEvent(AiEvent.Done)
            Result.success("")
        }

        viewModel.onAskAi("Write test")
        advanceUntilIdle()

        // Find the ConfirmBlock
        val confirmBlock = viewModel.blocks.filterIsInstance<ConfirmBlock>().lastOrNull()
        assertNotNull("Should have a pending ConfirmBlock", confirmBlock)

        // Confirm it
        viewModel.onConfirmAction(confirmed = true, block = confirmBlock!!)
        advanceUntilIdle()

        // Should now have a tool result block
        val resultBlocks = viewModel.blocks.filterIsInstance<AiBlock>()
            .filter { it.role == Role.SYSTEM }
        assertTrue(
            "Should have tool result blocks, got ${resultBlocks.size}",
            resultBlocks.isNotEmpty()
        )

        unmockkObject(AgentToolRouter)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 6. Mode switching
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `onModeChanged updates currentMode`() {
        assertEquals(ExecutionMode.APP_UID, viewModel.currentMode)

        viewModel.onModeChanged(ExecutionMode.ROOT_SHELL)
        assertEquals(ExecutionMode.ROOT_SHELL, viewModel.currentMode)
    }

    @Test
    fun `onModeChanged adds annotation block`() = runTest {
        val countBefore = viewModel.blocks.size

        viewModel.onModeChanged(ExecutionMode.SHIZUKU)
        advanceUntilIdle()

        assertTrue(viewModel.blocks.size > countBefore)

        val annotationBlock = viewModel.blocks.lastOrNull() as? AiBlock
        assertNotNull(annotationBlock)
        assertEquals(Role.SYSTEM, annotationBlock!!.role)
        assertTrue(
            annotationBlock.content.contains("SHIZUKU", ignoreCase = true)
        )
    }

    @Test
    fun `CONTAINER mode sets currentContainer`() {
        assertNull(viewModel.currentContainer)

        viewModel.onModeChanged(ExecutionMode.CONTAINER)
        assertEquals("alpine", viewModel.currentContainer)
    }

    @Test
    fun `switching away from CONTAINER clears currentContainer`() {
        viewModel.onModeChanged(ExecutionMode.CONTAINER)
        assertEquals("alpine", viewModel.currentContainer)

        viewModel.onModeChanged(ExecutionMode.APP_UID)
        assertNull(viewModel.currentContainer)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 7. isProcessing state
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `isProcessing is false initially`() {
        assertFalse(viewModel.isProcessing)
    }

    @Test
    fun `isProcessing returns to false after command completes`() = runTest {
        mockkObject(AgentToolRouter)
        every {
            AgentToolRouter.executeShellCommand(any(), any(), any(), any())
        } returns ToolResult(success = true, data = """{"exit_code":0,"output":""}""")

        viewModel.onSendCommand("echo test", ExecutionMode.APP_UID)
        advanceUntilIdle()

        assertFalse(viewModel.isProcessing)

        unmockkObject(AgentToolRouter)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 8. Conversation history isolation
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `clearBlocks resets all internal state`() = runTest {
        viewModel.onModeChanged(ExecutionMode.ROOT_SHELL)
        advanceUntilIdle()
        assertTrue(viewModel.blocks.isNotEmpty())

        viewModel.clearBlocks()

        assertTrue(viewModel.blocks.isEmpty())
        assertFalse(viewModel.isProcessing)
        assertNull(viewModel.currentContainer)
        // currentMode is not reset by clearBlocks (user preference)
        assertEquals(ExecutionMode.ROOT_SHELL, viewModel.currentMode)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 9. Edge cases
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `multiple commands produce sequential blocks`() = runTest {
        mockkObject(AgentToolRouter)
        coEvery {
            AgentToolRouter.executeShellCommand(any(), any(), any(), any())
        } returns ToolResult(success = true, data = """{"exit_code":0,"output":""}""")

        viewModel.onSendCommand("cmd1", ExecutionMode.APP_UID)
        viewModel.onSendCommand("cmd2", ExecutionMode.APP_UID)
        advanceUntilIdle()

        val cmdBlocks = viewModel.blocks.filterIsInstance<CommandBlock>()
        assertEquals(2, cmdBlocks.size)
        assertEquals("cmd1", cmdBlocks[0].command)
        assertEquals("cmd2", cmdBlocks[1].command)

        unmockkObject(AgentToolRouter)
    }

    @Test
    fun `onInsertSuggestion does not throw`() {
        // Should not throw, even with no side effects
        viewModel.onInsertSuggestion("suggested text")
    }

    @Test
    fun `terminalSession is null initially`() {
        assertNull(viewModel.terminalSession)
    }
}
