package com.buildersledger.domain

data class PlanInput(
    val nowMs: Long,
    val pools: List<WorkerPool>,
    val active: List<ActiveUpgrade>,
    val wishlist: List<WishlistItem>,
    val resources: Map<Resource, ResourceState>,
)

data class PlanEntry(
    val item: WishlistItem,
    val poolId: Long,
    val slotIndex: Int,
    val startMs: Long,
    val endMs: Long,
    /** How long the worker sat idle before this start because resources were short. */
    val waitedMs: Long,
)

data class IdleGap(
    val poolId: Long,
    val slotIndex: Int,
    val fromMs: Long,
    val toMs: Long,
)

enum class UnplannedReason { NO_WORKER_POOL, NO_SLOTS, NEVER_AFFORDABLE }

data class UnplannedItem(val item: WishlistItem, val reason: UnplannedReason)

data class PlanResult(
    val entries: List<PlanEntry>,
    val unplanned: List<UnplannedItem>,
    val idleGaps: List<IdleGap>,
    /** When the last planned upgrade finishes, or null when nothing could be planned. */
    val completionMs: Long?,
    /** Total planned spend per resource. */
    val totalCost: Map<Resource, Long>,
) {
    /** Entries that should be started right now. */
    fun startNow(nowMs: Long): List<PlanEntry> = entries.filter { it.startMs <= nowMs }
}

/**
 * Builds a schedule for the wishlist.
 *
 * Model:
 *  - Each pool has N interchangeable slots; running upgrades occupy slots until they end.
 *  - An upgrade pays its full cost when it starts.
 *  - Resources grow linearly with the player's entered income per hour; storage caps are ignored.
 *  - Within a pool, higher priority goes first, then longer duration (longest-first keeps the finish time short).
 *  - If the top choice would make a worker wait longer than [patienceMs] for resources, a lower
 *    priority upgrade that can start sooner is used to fill the gap instead.
 *  - Commitments are made in chronological order, so spending by one pool is always visible to the others.
 */
object Planner {
    const val HOUR_MS = 3_600_000L
    const val DEFAULT_PATIENCE_MS = HOUR_MS

    private data class Candidate(
        val startMs: Long,
        val priority: Int,
        val poolOrder: Int,
        val slot: Int,
        val poolId: Long,
        val item: WishlistItem,
        val freeAtMs: Long,
    )

    private fun better(a: Candidate, b: Candidate): Boolean {
        if (a.startMs != b.startMs) return a.startMs < b.startMs
        if (a.priority != b.priority) return a.priority > b.priority
        if (a.poolOrder != b.poolOrder) return a.poolOrder < b.poolOrder
        return a.slot < b.slot
    }

    private fun ceilDiv(a: Long, b: Long): Long = (a + b - 1) / b

    fun plan(input: PlanInput, patienceMs: Long = DEFAULT_PATIENCE_MS): PlanResult {
        val now = input.nowMs
        val poolsById = input.pools.associateBy { it.id }

        // When does each slot become free?
        val free = HashMap<Long, LongArray>()
        for (pool in input.pools) {
            val ends = input.active.filter { it.poolId == pool.id }.map { it.endsAtMs }.sorted()
            val n = maxOf(pool.slots, ends.size)
            free[pool.id] = LongArray(n) { i -> if (i < ends.size) maxOf(ends[i], now) else now }
        }

        val unplanned = mutableListOf<UnplannedItem>()
        val pending = mutableListOf<WishlistItem>()
        val sorted = input.wishlist.sortedWith(
            compareByDescending<WishlistItem> { it.priority }
                .thenByDescending { it.durationSeconds }
                .thenBy { it.id }
        )
        for (item in sorted) {
            val slots = free[item.poolId]
            when {
                poolsById[item.poolId] == null -> unplanned += UnplannedItem(item, UnplannedReason.NO_WORKER_POOL)
                slots == null || slots.isEmpty() -> unplanned += UnplannedItem(item, UnplannedReason.NO_SLOTS)
                else -> pending += item
            }
        }

        val spent = HashMap<Resource, Long>()

        fun earliestAffordable(item: WishlistItem, freeAt: Long): Long? {
            val res = item.costResource
            if (item.costAmount <= 0L || res == null) return freeAt
            val state = input.resources[res]
            val amount = state?.amountAt(now) ?: 0L
            val income = state?.incomePerHour ?: 0L
            val have = amount - (spent[res] ?: 0L)
            if (have >= item.costAmount) return freeAt
            if (income <= 0L) return null
            val need = item.costAmount - have
            val waitMs = ceilDiv(need * HOUR_MS, income)
            return maxOf(freeAt, now + waitMs)
        }

        val entries = mutableListOf<PlanEntry>()
        val gaps = mutableListOf<IdleGap>()
        val totalCost = HashMap<Resource, Long>()

        while (pending.isNotEmpty()) {
            var best: Candidate? = null
            val poolIds = pending.map { it.poolId }.distinct()
            for (pid in poolIds) {
                val pool = poolsById.getValue(pid)
                val candidates = pending.filter { it.poolId == pid } // already in priority order
                val slots = free.getValue(pid)
                for (s in slots.indices) {
                    val freeAt = slots[s]
                    var chosen: WishlistItem? = null
                    var chosenStart = 0L
                    var earliest: WishlistItem? = null
                    var earliestStart = Long.MAX_VALUE
                    for (item in candidates) {
                        val start = earliestAffordable(item, freeAt) ?: continue
                        if (chosen == null && start - freeAt <= patienceMs) {
                            chosen = item
                            chosenStart = start
                        }
                        if (start < earliestStart) {
                            earliest = item
                            earliestStart = start
                        }
                    }
                    val pick = chosen ?: earliest ?: continue
                    val pickStart = if (chosen != null) chosenStart else earliestStart
                    val cand = Candidate(pickStart, pick.priority, pool.sortOrder, s, pid, pick, freeAt)
                    val current = best
                    if (current == null || better(cand, current)) best = cand
                }
            }
            val winner = best ?: break

            val end = winner.startMs + winner.item.durationSeconds * 1000L
            entries += PlanEntry(
                item = winner.item,
                poolId = winner.poolId,
                slotIndex = winner.slot,
                startMs = winner.startMs,
                endMs = end,
                waitedMs = winner.startMs - winner.freeAtMs,
            )
            if (winner.startMs > winner.freeAtMs) {
                gaps += IdleGap(winner.poolId, winner.slot, winner.freeAtMs, winner.startMs)
            }
            free.getValue(winner.poolId)[winner.slot] = end
            val res = winner.item.costResource
            if (winner.item.costAmount > 0L && res != null) {
                spent[res] = (spent[res] ?: 0L) + winner.item.costAmount
                totalCost[res] = (totalCost[res] ?: 0L) + winner.item.costAmount
            }
            pending.remove(winner.item)
        }

        for (item in pending) unplanned += UnplannedItem(item, UnplannedReason.NEVER_AFFORDABLE)

        val ordered = entries.sortedWith(compareBy<PlanEntry>({ it.startMs }, { it.poolId }, { it.slotIndex }))
        return PlanResult(
            entries = ordered,
            unplanned = unplanned,
            idleGaps = gaps,
            completionMs = ordered.maxOfOrNull { it.endMs },
            totalCost = totalCost,
        )
    }
}
