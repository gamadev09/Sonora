package com.music.bitchord.data.recommendations

/**
 * Bounded continuation state for Home feed pagination.
 *
 * Tracks seen continuation tokens, consecutive pages that did not add any
 * visible personalised items (whether due to irrelevant content or duplicate
 * shelves), and total pages fetched so pagination can walk past a small run
 * of irrelevant or duplicate pages without spinning indefinitely or wasting
 * bandwidth.
 */
internal class HomePaginationState(
    private val maxUnproductivePages: Int = DEFAULT_MAX_UNPRODUCTIVE_PAGES,
    private val maxTotalPages: Int = DEFAULT_MAX_TOTAL_PAGES,
) {
    private val seenTokens = HashSet<String>()

    var nextToken: String? = null
        private set

    var unproductivePages: Int = 0
        private set

    var totalPagesFetched: Int = 0
        private set

    fun reset(initialToken: String? = null) {
        seenTokens.clear()
        nextToken = initialToken?.takeIf { it.isNotBlank() }
        unproductivePages = 0
        totalPagesFetched = 0
    }

    /**
     * Records the outcome of fetching [usedToken] and returns the next valid
     * continuation token, or null when pagination should stop.
     */
    fun onPageResult(
        usedToken: String,
        incomingToken: String?,
        addedRawShelves: Int,
        visibleItemsBefore: Int,
        visibleItemsAfter: Int,
    ): String? {
        seenTokens.add(usedToken)
        totalPagesFetched += 1

        val gainedVisible = visibleItemsAfter > visibleItemsBefore
        unproductivePages = if (gainedVisible) 0 else unproductivePages + 1

        val candidate = incomingToken?.takeIf { it.isNotBlank() }
        val shouldContinue = candidate != null &&
            candidate !in seenTokens &&
            unproductivePages < maxUnproductivePages &&
            totalPagesFetched < maxTotalPages &&
            (gainedVisible || addedRawShelves > 0 || unproductivePages < DUPLICATE_PAGE_TOLERANCE)

        nextToken = if (shouldContinue) candidate else null
        return nextToken
    }

    companion object {
        const val DEFAULT_MAX_UNPRODUCTIVE_PAGES = 4
        const val DEFAULT_MAX_TOTAL_PAGES = 12
        private const val DUPLICATE_PAGE_TOLERANCE = 2
    }
}
