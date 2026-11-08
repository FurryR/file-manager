package io.furryr.file.render

import io.furryr.file.core.Navigator
import io.furryr.file.core.VNode

/** One segment of the address bar / breadcrumb. */
data class AddressCrumb(
    val label: String,
    val node: VNode,
)

/**
 * Address-bar projection of the navigator. There is intentionally no synthetic
 * URI here: entering a container is represented by a deeper ancestry. Typed
 * addresses are parsed by [LocationAddress], which resolves a plain Vfs path by
 * descending through registered openers.
 */
fun Navigator.address(): List<AddressCrumb> =
    ancestry.map { AddressCrumb(label = it.name, node = it) }
