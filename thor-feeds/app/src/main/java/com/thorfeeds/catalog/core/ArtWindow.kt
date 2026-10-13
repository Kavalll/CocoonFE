package com.thorfeeds.catalog.core

/**
 * Steam art is requested one page at a time. The next page starts only when
 * the user has scrolled or focused near the end of the rows already covered.
 * A viewport that contains the whole catalog is ignored so a bad layout pass
 * cannot request every game at once.
 */
object ArtWindow {
    const val PAGE = 50
    private const val PREFETCH = 8
    private const val MAX_VIEWPORT_PX = 4_000

    data class Viewport(
        val lastVisibleIndex: Int,
        val visibleCount: Int,
        val heightPx: Int,
    )

    fun nextPage(window: Int, total: Int, viewport: Viewport): Int {
        if (!shouldAdvance(window, total, viewport)) return window
        val covered = ((viewport.lastVisibleIndex + PREFETCH) / PAGE + 1) * PAGE
        return covered.coerceAtLeast(window + PAGE).coerceAtMost(total)
    }

    fun shouldAdvance(window: Int, total: Int, viewport: Viewport): Boolean {
        if (total <= 0 || window >= total) return false
        if (viewport.lastVisibleIndex < 0 || viewport.visibleCount <= 0) return false
        if (viewport.heightPx <= 0 || viewport.heightPx > MAX_VIEWPORT_PX) return false
        if (total > PAGE && viewport.visibleCount >= total) return false
        return viewport.lastVisibleIndex >= window - PREFETCH
    }

    fun focusedIndexRequestsNext(index: Int, window: Int, total: Int): Boolean {
        if (index < 0 || window >= total) return false
        return index >= window - PREFETCH
    }
}
