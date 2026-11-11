package io.furryr.file.core

/**
 * A file whose bytes are served by a live network endpoint instead of a
 * storage location.
 *
 * The node is still a plain [VFile]: providers, openers, renderers and the
 * copy/share paths treat it like any other file, because [open] and [describe]
 * answer exactly like they do for a bytes-on-disk file. The two extra facts
 * exist because a live resource is *addressed*, not *located*:
 *
 *  - [address] is the absolute address a document renderer must load itself, so
 *    the platform resolves the page's own subresources against the origin;
 *  - [referer] is the page the resource was reached from and must accompany the
 *    request (and survive a `content://` share) for hotlink-protected hosts.
 *
 * Nothing in the app inspects a scheme to find this out: a caller asks the node.
 */
interface RemoteFile : VFile {
    val address: String
    val referer: String?
}
