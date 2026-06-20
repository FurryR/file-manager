package io.furryr.file.agent

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProotRunnerTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val expectedBinaryPath: String by lazy {
        File(context.filesDir, "proot-aarch64").absolutePath
    }

    private val testContainer = Container(
        name = "alpine-dev",
        distroId = "alpine",
        state = ContainerState.CREATED,
        createdAt = 1_000_000L,
        rootfsPath = "/data/data/io.furryr.file/files/containers/alpine-dev",
    )

    // ── basic command with no custom bind mounts ────────────────────────

    @Test
    fun `basic command with no bind mounts`() {
        val command = listOf("echo", "hello")
        val result = ProotRunner.buildProotCommand(context, testContainer, command)

        // [binary, -r, rootfs, (-b, src:dst)x3, -w, /, /bin/sh, -c, echo, hello]
        assertEquals(expectedBinaryPath, result[0])
        assertEquals("-r", result[1])
        assertEquals(testContainer.rootfsPath, result[2])

        // Default bind mounts — 3 mounts = 6 positional args
        assertEquals("-b", result[3])
        assertEquals("/storage/emulated/0/:/android/sdcard", result[4])
        assertEquals("-b", result[5])
        assertEquals("/system/:/android/system", result[6])
        assertEquals("-b", result[7])
        assertEquals("/data/data/io.furryr.file/:/android/data", result[8])

        // -w /
        assertEquals("-w", result[9])
        assertEquals("/", result[10])

        // Shell + command
        assertEquals("/bin/sh", result[11])
        assertEquals("-c", result[12])
        assertEquals("echo", result[13])
        assertEquals("hello", result[14])
        assertEquals(15, result.size)
    }

    // ── command with custom bind mounts ─────────────────────────────────

    @Test
    fun `command with custom bind mounts`() {
        val customMounts = listOf(
            BindMount("/storage/emulated/0/Download", "/mnt/download"),
            BindMount("/storage/emulated/0/Documents", "/mnt/docs"),
        )
        val container = testContainer.copy(bindMounts = customMounts)
        val command = listOf("ls", "-la")
        val result = ProotRunner.buildProotCommand(context, container, command)

        // Count all -b entries
        val bindIndices = result.mapIndexedNotNull { i, s -> if (s == "-b") i else null }
        assertEquals(5, bindIndices.size) // 3 default + 2 custom

        // Default mounts come first, in order
        assertEquals("/storage/emulated/0/:/android/sdcard", result[bindIndices[0] + 1])
        assertEquals("/system/:/android/system", result[bindIndices[1] + 1])
        assertEquals("/data/data/io.furryr.file/:/android/data", result[bindIndices[2] + 1])

        // Custom mounts follow, preserving order
        assertEquals("/storage/emulated/0/Download:/mnt/download", result[bindIndices[3] + 1])
        assertEquals("/storage/emulated/0/Documents:/mnt/docs", result[bindIndices[4] + 1])

        // Verify shell + command after last bind
        val tail = result.drop(bindIndices.last() + 2)
        assertEquals("-w", tail[0])
        assertEquals("/", tail[1])
        assertEquals("/bin/sh", tail[2])
        assertEquals("-c", tail[3])
        assertEquals("ls", tail[4])
        assertEquals("-la", tail[5])
    }

    // ── interactive shell mode (no command) ────────────────────────────

    @Test
    fun `interactive shell mode`() {
        val result = ProotRunner.buildProotCommand(context, testContainer, emptyList())

        assertEquals(expectedBinaryPath, result[0])
        // No -c flag should be present
        assertFalse(result.contains("-c"))
        // Last element is the shell
        assertEquals("/bin/sh", result.last())

        // Expected structure: binary, -r, rootfs, 3 binds*2, -w, /, /bin/sh
        val expectedSize = 1 + 2 + (3 * 2) + 2 + 1 // 1 + 2 + 6 + 2 + 1 = 12
        assertEquals(expectedSize, result.size)
    }

    // ── rootfs path format ─────────────────────────────────────────────

    @Test
    fun `rootfs path format check`() {
        val container = Container(
            name = "ubuntu-dev",
            distroId = "ubuntu",
            state = ContainerState.CREATED,
            createdAt = 2_000_000L,
            rootfsPath = "/data/data/io.furryr.file/files/containers/ubuntu-dev/rootfs",
        )
        val result = ProotRunner.buildProotCommand(context, container, listOf("id"))

        assertEquals("-r", result[1])
        assertEquals(
            "/data/data/io.furryr.file/files/containers/ubuntu-dev/rootfs",
            result[2],
        )
    }

    // ── buildProotArgs returns args without binary path ─────────────────

    @Test
    fun `buildProotArgs returns args without binary path`() {
        val result = ProotRunner.buildProotArgs(context, testContainer)

        // First argument is -r (not the binary path)
        assertEquals("-r", result[0])
        assertEquals(testContainer.rootfsPath, result[1])
        assertEquals("/bin/sh", result.last())
        // Binary path must NOT appear in args
        assertFalse(result.any { it == expectedBinaryPath })
    }

    // ── multiple bind mount ordering ───────────────────────────────────

    @Test
    fun `multiple bind mount ordering`() {
        val customMounts = listOf(
            BindMount("/custom/a", "/mnt/a"),
            BindMount("/custom/b", "/mnt/b"),
            BindMount("/custom/c", "/mnt/c"),
        )
        val container = testContainer.copy(bindMounts = customMounts)
        // Test through buildProotCommand since buildBindMountArgs is private
        val result = ProotRunner.buildProotCommand(context, container, emptyList())

        val bindArgs = result
            .mapIndexedNotNull { i, s -> if (s == "-b") result[i + 1] else null }

        assertEquals(6, bindArgs.size) // 3 default + 3 custom
        assertEquals("/storage/emulated/0/:/android/sdcard", bindArgs[0])
        assertEquals("/system/:/android/system", bindArgs[1])
        assertEquals("/data/data/io.furryr.file/:/android/data", bindArgs[2])
        assertEquals("/custom/a:/mnt/a", bindArgs[3])
        assertEquals("/custom/b:/mnt/b", bindArgs[4])
        assertEquals("/custom/c:/mnt/c", bindArgs[5])
    }
}
