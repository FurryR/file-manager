package io.furryr.file.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerModelsTest {

    @Test
    fun `Container can be instantiated with all fields`() {
        val bindMounts = listOf(
            BindMount(srcAndroidPath = "/storage/emulated/0/Downloads", dstContainerPath = "/android/sdcard"),
            BindMount(srcAndroidPath = "/storage/emulated/0/Documents", dstContainerPath = "/android/documents"),
        )
        val container = Container(
            name = "alpine-dev",
            distroId = "alpine",
            state = ContainerState.CREATED,
            createdAt = 1_000_000L,
            rootfsPath = "/data/data/io.furryr.file/files/containers/alpine-dev",
            bindMounts = bindMounts,
        )
        assertEquals("alpine-dev", container.name)
        assertEquals("alpine", container.distroId)
        assertEquals(ContainerState.CREATED, container.state)
        assertEquals(1_000_000L, container.createdAt)
        assertEquals("/data/data/io.furryr.file/files/containers/alpine-dev/rootfs", container.rootfsPath)
        assertEquals(2, container.bindMounts.size)
        assertEquals("/storage/emulated/0/Downloads", container.bindMounts[0].srcAndroidPath)
        assertEquals("/android/sdcard", container.bindMounts[0].dstContainerPath)
    }

    @Test
    fun `Container supports empty bindMounts default`() {
        val container = Container(
            name = "minimal",
            distroId = "ubuntu",
            state = ContainerState.STOPPED,
            createdAt = 2_000_000L,
            rootfsPath = "/data/data/io.furryr.file/files/containers/minimal/rootfs",
        )
        assertTrue(container.bindMounts.isEmpty())
    }

    @Test
    fun `Container supports all states`() {
        for (state in ContainerState.values()) {
            val container = Container(
                name = "test",
                distroId = "test",
                state = state,
                createdAt = 1L,
                rootfsPath = "/tmp/rootfs",
            )
            assertEquals(state, container.state)
        }
    }

    @Test
    fun `Container states count is 7`() {
        assertEquals(7, ContainerState.values().size)
        val expected = setOf(
            ContainerState.CREATED,
            ContainerState.STARTING,
            ContainerState.RUNNING,
            ContainerState.STOPPING,
            ContainerState.STOPPED,
            ContainerState.FAILED,
            ContainerState.DELETED,
        )
        assertTrue(ContainerState.values().toSet().containsAll(expected))
    }

    @Test
    fun `BindMount can be instantiated`() {
        val mount = BindMount(
            srcAndroidPath = "/storage/emulated/0",
            dstContainerPath = "/android/sdcard",
        )
        assertEquals("/storage/emulated/0", mount.srcAndroidPath)
        assertEquals("/android/sdcard", mount.dstContainerPath)
    }

    @Test
    fun `DistroInfo can be instantiated with all fields`() {
        val distro = DistroInfo(
            id = "alpine",
            name = "Alpine Linux 3.24",
            sizeMb = 5,
            downloadUrl = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.0-aarch64.tar.gz",
            checksumSha256 = "abc123def456abc123def456abc123def456abc123def456abc123def456abc1",
        )
        assertEquals("alpine", distro.id)
        assertEquals("Alpine Linux 3.24", distro.name)
        assertEquals(5, distro.sizeMb)
        assertEquals("https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.0-aarch64.tar.gz", distro.downloadUrl)
        assertEquals("abc123def456abc123def456abc123def456abc123def456abc123def456abc1", distro.checksumSha256)
    }

    @Test
    fun `DistroInfo supports multiple distros`() {
        val distros = listOf(
            DistroInfo("alpine", "Alpine Linux 3.24", 5, "https://example.com/alpine.tar.gz", "aaaa"),
            DistroInfo("ubuntu", "Ubuntu 24.04", 400, "https://example.com/ubuntu.tar.gz", "bbbb"),
            DistroInfo("debian", "Debian Bookworm", 350, "https://example.com/debian.tar.gz", "cccc"),
        )
        assertEquals(3, distros.size)
        assertEquals("ubuntu", distros[1].id)
        assertEquals(400, distros[1].sizeMb)
    }
}
