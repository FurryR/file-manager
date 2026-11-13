package io.furryr.file.render

/**
 * Marker base for a page's durable, non-view state.
 *
 * Subclasses carry their own fields; this base deliberately has no shared
 * data. RendererBase exposes the concrete state, never the private session.
 */
abstract class PageState : java.io.Serializable
