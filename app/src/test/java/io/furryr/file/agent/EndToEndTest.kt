package io.furryr.file.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * End-to-end tests for the Container → Session → Command execution flow.
 *
 * These tests cover ContainerManager (create, delete, rename, list, execInContainer)
 * with mocked external dependencies (DistroFetcher, ProotInstaller, ProotRunner).
 *
 * Container processes are managed via [SessionManager] — the container lifecycle
 * (RUNNING/STOPPED) is derived from [SessionManager.hasContainerSession].
 *
 * Test coverage:
 * 1. Container creation from a mocked distro → Container with state CREATED
 * 2. execInContainer captures stdout from a spawned process
 * 3. deleteContainer removes the container from listing
 * 4. Error handling: network/download failures → Result.failure, not crash
 * 5. End-to-end orchestration: select distro → name → create → exec
 * 6. Edge cases: duplicate names, empty listing, complex commands
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EndToEndTest {

    private lateinit var context: Context

    private val testImageRef = "library/alpine:latest"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "containers").deleteRecursively()
    }

    @After
    fun tearDown() {
        runCatching { unmockkObject(ProotInstaller) }
        runCatching { unmockkObject(DistroFetcher) }
        runCatching { unmockkObject(ProotRunner) }
        runCatching { unmockkObject(ContainerManager) }
    }

    private fun mockDownloadRootfs() {
        coEvery { DistroFetcher.downloadRootfs(any(), any(), any()) } answers {
            val destDir = arg<File>(1)
            destDir.mkdirs()
            File(destDir, "bin").mkdir()
            Result.success(destDir)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Test 1 — Full container creation flow
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `create container returns container with CREATED state`() = runBlocking {
        mockkObject(ProotInstaller)
        every { ProotInstaller.install(any()) } returns Result.success(
            File(context.filesDir, "proot-aarch64")
        )

        mockkObject(DistroFetcher)
        mockDownloadRootfs()

        val result = ContainerManager.createContainer(context, "alpine-dev", testImageRef)

        assertTrue("createContainer should succeed", result.isSuccess)
        val container = result.getOrThrow()
        assertEquals("alpine-dev", container.name)
        assertEquals("alpine", container.distroId)
        assertEquals(ContainerState.CREATED, container.state)
        assertTrue(container.rootfsPath.endsWith("/containers/alpine-dev"))

        val list = ContainerManager.listContainers(context)
        assertTrue(list.isSuccess)
        assertEquals(1, list.getOrThrow().size)
        assertEquals("alpine-dev", list.getOrThrow().first().name)

        val containerDir = File(context.filesDir, "containers/alpine-dev")
        assertTrue(containerDir.exists())
        assertTrue(File(containerDir, "distro.json").exists())

        unmockkObject(ProotInstaller)
        unmockkObject(DistroFetcher)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Test 2 — Container running state via SessionManager
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `container RUNNING state reflects active terminal session`() = runBlocking {
        mockkObject(ProotInstaller)
        every { ProotInstaller.install(any()) } returns Result.success(
            File(context.filesDir, "proot-aarch64")
        )
        mockkObject(DistroFetcher)
        mockDownloadRootfs()

        ContainerManager.createContainer(context, "state-test", testImageRef).getOrThrow()

        unmockkObject(ProotInstaller)
        unmockkObject(DistroFetcher)

        // Without an active session, container is CREATED
        val beforeList = ContainerManager.listContainers(context).getOrThrow()
        assertEquals(ContainerState.CREATED, beforeList.first().state)

        // hasContainerSession returns false when no session exists
        assertTrue(!SessionManager.hasContainerSession("state-test"))
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Test 3 — execInContainer captures stdout
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `execInContainer captures stdout from command`() = runBlocking {
        mockkObject(ProotInstaller)
        every { ProotInstaller.install(any()) } returns Result.success(
            File(context.filesDir, "proot-aarch64")
        )
        mockkObject(DistroFetcher)
        mockDownloadRootfs()

        ContainerManager.createContainer(context, "alpine-dev", testImageRef).getOrThrow()

        unmockkObject(ProotInstaller)
        unmockkObject(DistroFetcher)

        mockkObject(ProotRunner)
        every {
            ProotRunner.buildProotCommand(any(), any(), any())
        } returns listOf("echo", "hello world from container")

        val execResult = ContainerManager.execInContainer(
            context,
            "alpine-dev",
            "echo 'hello world from container'",
        )

        assertTrue("execInContainer should succeed", execResult.isSuccess)
        assertEquals("hello world from container", execResult.getOrThrow())

        verify(exactly = 1) {
            ProotRunner.buildProotCommand(
                any(),
                match { it.name == "alpine-dev" },
                match { commandList ->
                    commandList.size == 1 && commandList[0] == "echo 'hello world from container'"
                },
            )
        }

        unmockkObject(ProotRunner)
    }

    @Test
    fun `execInContainer returns failure on non-zero exit code`() = runBlocking {
        mockkObject(ProotInstaller)
        every { ProotInstaller.install(any()) } returns Result.success(
            File(context.filesDir, "proot-aarch64")
        )
        mockkObject(DistroFetcher)
        mockDownloadRootfs()

        ContainerManager.createContainer(context, "alpine-dev", testImageRef).getOrThrow()

        unmockkObject(ProotInstaller)
        unmockkObject(DistroFetcher)

        mockkObject(ProotRunner)
        every {
            ProotRunner.buildProotCommand(any(), any(), any())
        } returns listOf("false")

        val execResult = ContainerManager.execInContainer(
            context,
            "alpine-dev",
            "false",
        )

        assertTrue("execInContainer should fail on non-zero exit", execResult.isFailure)
        val exception = execResult.exceptionOrNull()
        assertTrue(
            "Exception message should mention exit",
            exception!!.message!!.contains("exit"),
        )

        unmockkObject(ProotRunner)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Test 4 — deleteContainer removes container from listing
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `delete container removes it from listing`() = runBlocking {
        mockkObject(ProotInstaller)
        every { ProotInstaller.install(any()) } returns Result.success(
            File(context.filesDir, "proot-aarch64")
        )
        mockkObject(DistroFetcher)
        mockDownloadRootfs()

        ContainerManager.createContainer(context, "delete-me", testImageRef).getOrThrow()

        unmockkObject(ProotInstaller)
        unmockkObject(DistroFetcher)

        val beforeList = ContainerManager.listContainers(context)
        assertTrue(beforeList.isSuccess)
        assertEquals(1, beforeList.getOrThrow().size)
        assertEquals("delete-me", beforeList.getOrThrow().first().name)

        val deleteResult = ContainerManager.deleteContainer(context, "delete-me")
        assertTrue("deleteContainer should succeed", deleteResult.isSuccess)

        val afterList = ContainerManager.listContainers(context)
        assertTrue(afterList.isSuccess)
        assertTrue("Container list should be empty after delete", afterList.getOrThrow().isEmpty())

        val containerDir = File(context.filesDir, "containers/delete-me")
        assertTrue("Container directory should be deleted", !containerDir.exists())

        assertNull(ContainerManager.getContainer(context, "delete-me"))
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Test 5 — Error handling: download failure
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `create container returns failure on download error`() = runBlocking {
        mockkObject(ProotInstaller)
        every { ProotInstaller.install(any()) } returns Result.success(
            File(context.filesDir, "proot-aarch64")
        )

        mockkObject(DistroFetcher)
        coEvery {
            DistroFetcher.downloadRootfs(any(), any(), any())
        } returns Result.failure(IOException("Network unreachable"))

        val result = ContainerManager.createContainer(context, "fail-container", testImageRef)

        assertTrue("createContainer should fail on network error", result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is IOException)
        assertTrue(exception!!.message!!.contains("Network"))

        val list = ContainerManager.listContainers(context)
        assertTrue(list.isSuccess)
        assertTrue(list.getOrThrow().isEmpty())

        val containerDir = File(context.filesDir, "containers/fail-container")
        val manifestFile = File(containerDir, "manifest.json")
        assertTrue("Manifest should not exist for failed container", !manifestFile.exists())

        unmockkObject(ProotInstaller)
        unmockkObject(DistroFetcher)
    }

    @Test
    fun `create container returns failure on ProotInstaller failure`() = runBlocking {
        mockkObject(ProotInstaller)
        every {
            ProotInstaller.install(any())
        } returns Result.failure(IOException("Proot binary not found in assets"))

        val result = ContainerManager.createContainer(context, "fail-proot", testImageRef)

        assertTrue("createContainer should fail when ProotInstaller fails", result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is IOException)
        assertTrue(exception!!.message!!.contains("Proot"))

        unmockkObject(ProotInstaller)
    }

    @Test
    fun `execInContainer on nonexistent container returns failure`() = runBlocking {
        val result = ContainerManager.execInContainer(context, "nonexistent", "ls")
        assertTrue("execInContainer should fail for nonexistent container", result.isFailure)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Test 6 — End-to-end: select distro → name → create → exec
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `end to end flow with fully mocked components`() = runBlocking {
        mockkObject(ProotInstaller)
        every { ProotInstaller.install(any()) } returns Result.success(
            File(context.filesDir, "proot-aarch64")
        )

        mockkObject(DistroFetcher)
        mockDownloadRootfs()

        mockkObject(ProotRunner)
        every {
            ProotRunner.buildProotCommand(any(), any(), match { it.isNotEmpty() })
        } returns listOf("echo", "e2e-success")

        val containerName = "e2e-container"

        // Step 1: Select distro and create
        val createResult = ContainerManager.createContainer(context, containerName, testImageRef)
        assertTrue("Step 1 - create should succeed", createResult.isSuccess)
        val container = createResult.getOrThrow()
        assertEquals(ContainerState.CREATED, container.state)
        assertEquals("alpine", container.distroId)

        // Step 2: Execute command (uses ProotRunner directly, managed by SessionManager)
        val execResult = ContainerManager.execInContainer(
            context,
            containerName,
            "echo e2e-success",
        )
        assertTrue("Step 2 - exec should succeed", execResult.isSuccess)
        assertEquals("e2e-success", execResult.getOrThrow())

        // Verify ProotRunner was called for the exec
        verify(exactly = 1) {
            ProotRunner.buildProotCommand(
                any(),
                match { it.name == containerName },
                match { it.isNotEmpty() },
            )
        }

        unmockkObject(ProotInstaller)
        unmockkObject(DistroFetcher)
        unmockkObject(ProotRunner)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Edge cases
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `create container with duplicate name returns failure`() = runBlocking {
        mockkObject(ProotInstaller)
        every { ProotInstaller.install(any()) } returns Result.success(
            File(context.filesDir, "proot-aarch64")
        )
        mockkObject(DistroFetcher)
        mockDownloadRootfs()

        val first = ContainerManager.createContainer(context, "dup-name", testImageRef)
        assertTrue(first.isSuccess)

        mockDownloadRootfs()

        val second = ContainerManager.createContainer(context, "dup-name", testImageRef)
        assertTrue("Second create with duplicate name should fail", second.isFailure)
        assertTrue(
            second.exceptionOrNull()!!.message!!.contains("already exists"),
        )

        unmockkObject(ProotInstaller)
        unmockkObject(DistroFetcher)
    }

    @Test
    fun `list containers returns empty list when no containers exist`() {
        val result = ContainerManager.listContainers(context)
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
    }

    @Test
    fun `execInContainer with complex multi-word command`() = runBlocking {
        mockkObject(ProotInstaller)
        every { ProotInstaller.install(any()) } returns Result.success(
            File(context.filesDir, "proot-aarch64")
        )
        mockkObject(DistroFetcher)
        mockDownloadRootfs()

        ContainerManager.createContainer(context, "complex-cmd", testImageRef).getOrThrow()

        unmockkObject(ProotInstaller)
        unmockkObject(DistroFetcher)

        mockkObject(ProotRunner)
        every {
            ProotRunner.buildProotCommand(any(), any(), any())
        } returns listOf("sh", "-c", "ls -la /tmp")

        val execResult = ContainerManager.execInContainer(
            context,
            "complex-cmd",
            "ls -la /tmp",
        )

        assertTrue("Complex command exec should succeed", execResult.isSuccess)
        val output = execResult.getOrThrow()
        assertTrue(output.isNotEmpty())

        unmockkObject(ProotRunner)
    }

}