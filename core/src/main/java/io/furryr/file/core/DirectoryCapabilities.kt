package io.furryr.file.core

/**
 * Bound operations of one folder. A null callback means the folder cannot
 * perform that operation here; the UI greys the action instead of discovering
 * the refusal after the fact.
 */
data class DirectoryCapabilities(
    val createFile: (suspend (String) -> Result<VFile>)? = null,
    val createDirectory: (suspend (String) -> Result<VFolder>)? = null,
    val rename: (suspend (String, String) -> Result<VNode>)? = null,
    val delete: (suspend (String) -> Result<Unit>)? = null,
)
