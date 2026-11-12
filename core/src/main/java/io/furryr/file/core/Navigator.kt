package io.furryr.file.core

/**
 * Navigation state for one tab. It owns the current node, history and the
 * breadcrumb ancestry; renderers must never parse or format a location.
 */
class Navigator(
    initial: VNode,
    initialRendererId: String? = null,
) {
    var current: VNode = initial
        private set

    var rendererId: String? = initialRendererId
        private set

    private val backStack = ArrayDeque<VNode>()
    private val forwardStack = ArrayDeque<VNode>()

    val canGoBack: Boolean get() = backStack.isNotEmpty()
    val canGoForward: Boolean get() = forwardStack.isNotEmpty()

    /** Breadcrumb from the root to [current], including container roots. */
    val ancestry: List<VNode>
        get() = generateSequence(current) { it.parent }.toList().asReversed()

    fun enter(node: VNode, rendererId: String? = null) {
        if (node === current) {
            this.rendererId = rendererId ?: this.rendererId
            return
        }
        backStack.addLast(current)
        forwardStack.clear()
        current = node
        this.rendererId = rendererId
    }

    fun goBack(): VNode? {
        val node = backStack.removeLastOrNull() ?: return null
        forwardStack.addLast(current)
        current = node
        return node
    }

    fun goForward(): VNode? {
        val node = forwardStack.removeLastOrNull() ?: return null
        backStack.addLast(current)
        current = node
        return node
    }

    /** Move to [current]'s parent as an ordinary navigation. */
    fun goParent(): VNode? {
        val node = current.parent ?: return null
        enter(node)
        return node
    }

    fun setRenderer(rendererId: String?) {
        this.rendererId = rendererId
    }

    /**
     * Rebuild the current node from a persisted address. [Vfs.node] descends
     * every container level; history is intentionally not restored here, only
     * the current location.
     */
    suspend fun restore(
        path: Path,
        rendererId: String? = null,
    ): Result<VNode> = runCatching {
        val node = Vfs.node(path).getOrThrow()
        current = node
        this.rendererId = rendererId
        backStack.clear()
        forwardStack.clear()
        node
    }

    /** Addresses for persisting history; resolution happens on restore. */
    fun backRefs(): List<Path> = backStack.map { it.path }
    fun forwardRefs(): List<Path> = forwardStack.map { it.path }
}
