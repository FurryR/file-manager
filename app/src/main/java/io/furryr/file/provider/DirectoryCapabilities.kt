package io.furryr.file.provider

data class DirectoryCapabilities(
    val canCreate: Boolean = false,
    val canOpenTerminal: Boolean = false,
)
