package io.furryr.file.core

/**
 * Icon a generic loader shows for an opener. The registry module owns this
 * vocabulary so an opener can describe itself without importing UI code; the
 * app maps each value onto its own icon set.
 */
enum class OpenIcon {
    ARCHIVE,
    PAGE,
    EDITOR,
    SYSTEM,
    GENERIC,
}

/**
 * One embedded open-with entry. [label] is the localized name its opener
 * chose, [icon] the shape the loader draws above it.
 */
data class OpenOption(
    val id: String,
    val label: String,
    val icon: OpenIcon,
)
