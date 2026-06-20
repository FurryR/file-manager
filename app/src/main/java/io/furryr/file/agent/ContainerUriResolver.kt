package io.furryr.file.agent

/**
 * Pure URI-parsing resolver for `container:///` URIs.
 *
 * Parses URIs of the form `container:///<container-name>/<path>`,
 * validates the container name (alphanumeric + hyphens), and returns
 * the extracted components as a [ContainerUriResult].
 *
 * The root URI `container:///` maps to the containers directory
 * and has `containerName` = null.
 *
 * No container runtime access — operates entirely on string URIs.
 */
object ContainerUriResolver {

    private const val CONTAINER_SCHEME = "container"
    private const val SCHEME_SEPARATOR = "://"

    /** Alphanumeric characters and hyphens are allowed; name must start with an alphanumeric character. */
    private val VALID_CONTAINER_NAME_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9-]*$")

    // ── URI resolution ────────────────────────────────────────────────

    /**
     * Parses a `container:///` URI, extracts and validates the container
     * name (non-empty, alphanumeric + hyphens) and the container path.
     *
     * Root URI `container:///` returns [ContainerUriResult] with
     * `containerName = null` and `containerPath = "/"`.
     *
     * @return [Result.success] with [ContainerUriResult] on valid URIs,
     *         [Result.failure] on invalid schemes or malformed URIs.
     */
    fun resolveContainerUri(uri: String): Result<ContainerUriResult> {
        val schemeEnd = uri.indexOf(SCHEME_SEPARATOR)
        if (schemeEnd == -1) {
            return Result.failure(
                IllegalArgumentException("Invalid URI format, expected container:///<name>/<path>: $uri")
            )
        }

        val scheme = uri.substring(0, schemeEnd)
        if (scheme != CONTAINER_SCHEME) {
            return Result.failure(
                IllegalArgumentException("Unsupported URI scheme: $scheme")
            )
        }

        val afterScheme = uri.substring(schemeEnd + SCHEME_SEPARATOR.length)

        // Root URI: container:/// or container://
        if (afterScheme.isEmpty() || afterScheme == "/") {
            return Result.success(
                ContainerUriResult(
                    containerName = null,
                    containerPath = "/",
                    originalUri = uri
                )
            )
        }

        // container:///something — strip leading slash(es)
        val cleaned = afterScheme.trimStart('/')

        // Find the first '/' which separates container name from path
        val firstSlash = cleaned.indexOf('/')
        val containerName: String
        val containerPath: String
        when {
            firstSlash == -1 -> {
                containerName = cleaned
                containerPath = "/"
            }
            else -> {
                containerName = cleaned.substring(0, firstSlash)
                containerPath = cleaned.substring(firstSlash)
            }
        }

        if (containerName.isEmpty()) {
            return Result.success(
                ContainerUriResult(
                    containerName = null,
                    containerPath = "/",
                    originalUri = uri,
                )
            )
        }

        if (!isValidContainerName(containerName)) {
            return Result.failure(
                IllegalArgumentException(
                    "Invalid container name, must be alphanumeric with optional hyphens: $containerName"
                )
            )
        }

        return Result.success(
            ContainerUriResult(
                containerName = containerName,
                containerPath = containerPath,
                originalUri = uri
            )
        )
    }

    // ── Validation ────────────────────────────────────────────────────

    /**
     * Returns `true` if [name] is a valid container name:
     * non-empty and consisting only of alphanumeric characters and hyphens.
     */
    fun isValidContainerName(name: String): Boolean {
        if (name.isEmpty()) return false
        return VALID_CONTAINER_NAME_REGEX.matches(name)
    }
}

/**
 * Result of resolving a `container:///` URI.
 *
 * @property containerName  The extracted container identifier (e.g., `"alpine"`),
 *                          or null for the root URI `container:///`.
 * @property containerPath  The path inside the container (e.g., `"/home/test"`).
 * @property originalUri    The raw URI as received.
 */
data class ContainerUriResult(
    val containerName: String?,
    val containerPath: String,
    val originalUri: String
)
