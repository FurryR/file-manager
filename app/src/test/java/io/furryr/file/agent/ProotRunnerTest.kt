package io.furryr.file.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProotRunnerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val container = Container(
        name = "alpine-dev",
        distroId = "alpine",
        state = ContainerState.CREATED,
        createdAt = 1_000_000L,
        rootfsPath = "/data/data/io.furryr.file/files/containers/alpine-dev",
    )

    @Test
    fun `bind mounts use individual devices and bind data`() {
        val mounts = ProotRunner.bindMounts(context, container)
        val specs = mounts.map { "${it.srcAndroidPath}:${it.dstContainerPath}" }
        assertTrue(specs.contains("/dev/null:/dev/null"))
        assertTrue(specs.contains("/dev/pts:/dev/pts"))
        assertTrue(specs.contains("/data:/data"))
        assertFalse(specs.contains("/dev:/dev"))
    }

    @Test
    fun `chroot binds include resolver and user mounts`() {
        // Use a source that exists on the test host (bind sources are filtered
        // by existence before being forwarded).
        val withUser = container.copy(bindMounts = listOf(BindMount("/tmp", "/mnt/dl")))
        val binds = ProotRunner.chrootBinds(context, withUser).toMap()
        assertTrue(binds.containsKey("/tmp"))
        assertTrue(binds.values.any { it == "/etc/resolv.conf" })
    }

    @Test
    fun `proot daemon command launches daemon with proot flag`() {
        val cmd = ProotRunner.buildProotDaemonCommand(
            context = context,
            container = container,
            prootPath = "/data/lib/libproot.so",
            daemonPath = "/data/lib/libfiledaemon.so",
            publicKey = "aa".repeat(32),
            id = "test-container",
        )
        assertEquals("/data/lib/libproot.so", cmd.first())
        assertTrue(cmd.contains("--proot"))
        assertTrue(cmd.contains("--key"))
        assertEquals("aa".repeat(32), cmd[cmd.indexOf("--key") + 1])
        assertTrue(cmd.contains("--id"))
        assertEquals("test-container", cmd[cmd.indexOf("--id") + 1])
        assertEquals("/data/lib/libfiledaemon.so", cmd[cmd.indexOf("--key") - 1])
        // Device binds that exist on any host are forwarded individually.
        assertTrue(cmd.contains("/dev/null:/dev/null"))
        assertFalse(cmd.contains("/dev:/dev"))
    }
}
