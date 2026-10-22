package io.furryr.file.core

data class DirectoryCapabilities(
    val canCreate: Boolean = false,
    val canOpenTerminal: Boolean = false,
)
