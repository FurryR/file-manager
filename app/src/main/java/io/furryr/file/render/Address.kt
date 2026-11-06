package io.furryr.file.render

import io.furryr.file.core.Navigator
import io.furryr.file.core.VNode

/** One segment of the address bar / breadcrumb. */
data class AddressCrumb(
    val label: String,
    val node: VNode,
)

/**
 * Address-bar projection of the navigator. There is intentionally no text
 * parser and no synthetic URI here: entering a container is represented by a
 * deeper ancestry, and editing the address bar only accepts a real Vfs path.
 */
fun Navigator.address(): List<AddressCrumb> =
    ancestry.map { AddressCrumb(label = it.name, node = it) }
