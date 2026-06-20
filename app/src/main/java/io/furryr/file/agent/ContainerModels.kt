package io.furryr.file.agent

/**
 * Lifecycle state of a container.
 *
 * State machine: CREATED → STARTING → RUNNING → STOPPING → STOPPED
 * FAILED may transition from any state.
 * DELETED may transition only from STOPPED.
 */
enum class ContainerState {
    CREATED,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED,
    DELETED,
}

/**
 * A container instance running under the app-managed proot environment.
 *
 * @property name Unique container name (e.g., "alpine-dev").
 * @property distroId Identifies the distro from which this container was created.
 * @property state Current lifecycle state.
 * @property createdAt Unix-millis timestamp when the container was created.
 * @property rootfsPath Absolute path to the container's rootfs on Android filesystem.
 * @property bindMounts List of bind mounts mapping Android paths into the container.
 */
data class Container(
    val name: String,
    val distroId: String,
    val state: ContainerState,
    val createdAt: Long,
    val rootfsPath: String,
    val bindMounts: List<BindMount> = emptyList(),
)

/**
 * A bind mount mapping an Android-side path into the container's filesystem.
 *
 * @property srcAndroidPath Host-side absolute path (e.g., /storage/emulated/0/Downloads).
 * @property dstContainerPath Target path inside the container (e.g., /android/sdcard).
 */
data class BindMount(
    val srcAndroidPath: String,
    val dstContainerPath: String,
)

/**
 * Metadata for a downloadable Linux root filesystem distribution.
 *
 * @property id Short identifier (e.g., "alpine").
 * @property name Human-readable name (e.g., "Alpine Linux (latest)").
 * @property sizeMb Approximate download size in megabytes.
 * @property image Docker image reference (e.g. "library/alpine:latest").
 *            When set, rootfs is pulled from Docker Registry.
 * @property downloadUrl Direct URL to the rootfs tarball (fallback when image is empty).
 * @property checksumSha256 SHA-256 hex digest of the tarball (optional, skipped when blank).
 */
data class DistroInfo(
    val id: String,
    val name: String,
    val sizeMb: Int,
    val image: String = "",
    val downloadUrl: String = "",
    val checksumSha256: String = "",
)
