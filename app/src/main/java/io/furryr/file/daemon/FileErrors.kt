package io.furryr.file.daemon
import io.furryr.file.ui.theme.*
import io.furryr.file.ui.screens.*
import io.furryr.file.ui.components.*
import io.furryr.file.ui.util.*
import io.furryr.file.daemon.*
import io.furryr.file.model.*

class PermissionDeniedException(path: String) : IllegalStateException("Permission denied: $path")
