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
    /** Plain-language "why now / why this order". Durations are relative to the plan's `nowMs`. */
    val explanation: String = "",
    /** How far past the item's finish-by deadline this entry ends, or null when on time or no deadline. */
    val lateByMs: Long? = null,
)

data class IdleGap(
    val poolId: Long,
    val slotIndex: Int,
    val fromMs: Long,
    val toMs: Long,
)

enum class UnplannedReason { NO_WORKER_POOL, NO_SLOTS, NEVER_AFFORDABLE, EXCEEDS_CAP }

data class UnplannedItem(val item: WishlistItem, val reason: UnplannedReason)

enum class DeadlineIssue {
    /** Even starting right now the upgrade could not finish in time (or the deadline already passed). */
    IMPOSSIBLE,
    /** It could be done in time on its own, but the plan finishes it late. */
    LATE,
    /** The upgrade could not be scheduled at all. */
    UNPLANNED,
}

data class DeadlineWarning(
    val item: WishlistItem,
    val issue: DeadlineIssue,
    /** How late the plan finishes it, when known. */
    val lateByMs: Long?,
    val message: String,
)

/**
 * A resource sat (or will sit) at its storage cap while income kept arriving, so income was lost.
 * [spendAtMs] is the next planned spend that frees room, or null when the plan never spends it.
 */
data class CapWarning(
    val resource: Resource,
    val reachedAtMs: Long,
    val spendAtMs: Long?,
    /** Income that is lost between [reachedAtMs] and [spendAtMs] (zero when [spendAtMs] is null). */
    val lostAmount: Long,
    val message: String,
)

data class PlanResult(
    val entries: List<PlanEntry>,
    val unplanned: List<UnplannedItem>,
    val idleGaps: List<IdleGap>,
    /** When the last planned upgrade finishes, or null when nothing could be planned. */
    val completionMs: Long?,
    /** Total planned spend per resource. */
    val totalCost: Map<Resource, Long>,
    val deadlineWarnings: List<DeadlineWarning> = emptyList(),
    val capWarnings: List<CapWarning> = emptyList(),
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
 *  - Resources grow linearly with the player's entered income per hour. If a resource has a storage
 *    capacity, income stops while the balance sits at the cap, and the plan reports "cap reached" warnings.
 *  - Within a pool, higher priority goes first, then the earliest finish-by deadline, then longer duration
 *    (longest-first keeps the finish time short). An upgrade whose deadline is close but still reachable
 *    (see `urgencyWindowMs`) jumps the queue.
 *  - If the top choice would make a worker wait longer than `patienceMs` for resources, a lower
 *    priority upgrade that can start sooner is used to fill the gap instead.
 *  - Commitments are made in chronological order, so spending by one pool is always visible to the others.
 */
object Planner {
    const val HOUR_MS = 3_600_000L
    const val DAY_MS = 24 * HOUR_MS
    const val DEFAULT_PATIENCE_MS = HOUR_MS

    /** Deadline items whose slack is below this (but not negative) are moved to the front of their pool. */
    const val DEFAULT_URGENCY_WINDOW_MS = DAY_MS

    /** Open-ended cap warnings are only shown when the cap is hit within this horizon. */
    const val CAP_HORIZON_MS = 7 * DAY_MS

    private data class Candidate(
        val startMs: Long,
        val rank: Int,
        val poolOrder: Int,
        val slot: Int,
        val poolId: Long,
        val item: WishlistItem,
        val freeAtMs: Long,
        val passedOver: WishlistItem?,
        val passedOverWaitMs: Long,
    )

    private fun better(a: Candidate, b: Candidate): Boolean {
        if (a.startMs != b.startMs) return a.startMs < b.startMs
        if (a.rank != b.rank) return a.rank > b.rank
        if (a.poolOrder != b.poolOrder) return a.poolOrder < b.poolOrder
        return a.slot < b.slot
    }

    private fun ceilDiv(a: Long, b: Long): Long = (a + b - 1) / b

    private class CapWindow(val resource: Resource, val reachedAtMs: Long, var endedAtMs: Long?, var lost: Long)

    /** Tracks one resource's balance through the planned spends, including the storage cap. */
    private class Ledger(val resource: Resource, state: ResourceState?, now: Long) {
        val income: Long = (state?.incomePerHour ?: 0L).coerceAtLeast(0L)
        val cap: Long? = state?.capacity?.takeIf { it > 0L }
        var cursor: Long = now
        var balance: Long = state?.amountAt(now) ?: 0L
        val windows = mutableListOf<CapWindow>()
        private var open: CapWindow? = null

        init {
            val c = cap
            if (c != null && income > 0L && balance >= c) {
                open = CapWindow(resource, now, null, 0L).also { windows += it }
            }
        }

        fun balanceAt(t: Long): Long {
            if (t <= cursor) return balance
            val grown = balance + income * (t - cursor) / HOUR_MS
            val c = cap ?: return grown
            return minOf(grown, maxOf(c, balance))
        }

        fun advance(t: Long) {
            if (t <= cursor) return
            val before = balance
            val c = cap
            if (c != null && income > 0L && open == null) {
                val grown = before + income * (t - cursor) / HOUR_MS
                if (before >= c) {
                    open = CapWindow(resource, cursor, null, 0L).also { windows += it }
                } else if (grown >= c) {
                    val at = cursor + ceilDiv((c - before) * HOUR_MS, income)
                    open = CapWindow(resource, at, null, 0L).also { windows += it }
                }
            }
            balance = balanceAt(t)
            cursor = t
        }

        fun spend(t: Long, amount: Long) {
            advance(t)
            balance = (balance - amount).coerceAtLeast(0L)
            val w = open ?: return
            val c = cap ?: return
            if (balance < c) {
                val ended = maxOf(t, cursor)
                w.endedAtMs = ended
                w.lost = income * (ended - w.reachedAtMs).coerceAtLeast(0L) / HOUR_MS
                open = null
            }
        }
    }

    private fun urgent(item: WishlistItem, now: Long, windowMs: Long): Boolean {
        val by = item.finishByMs ?: return false
        val slack = by - now - item.durationSeconds * 1000L
        return slack in 0..windowMs
    }

    fun plan(
        input: PlanInput,
        patienceMs: Long = DEFAULT_PATIENCE_MS,
        urgencyWindowMs: Long = DEFAULT_URGENCY_WINDOW_MS,
    ): PlanResult {
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
        val urgentIds = HashSet<Long>()
        for (item in input.wishlist) if (urgent(item, now, urgencyWindowMs)) urgentIds += item.id
        val sorted = input.wishlist.sortedWith(
            compareByDescending<WishlistItem> { it.id in urgentIds }
                .thenByDescending { it.priority }
                .thenBy { it.finishByMs ?: Long.MAX_VALUE }
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

        val ledgers = HashMap<Resource, Ledger>()
        fun ledger(res: Resource) = ledgers.getOrPut(res) { Ledger(res, input.resources[res], now) }
        // Resources with a cap are tracked even when nothing is spent, so idle capped storage is reported too.
        for ((res, state) in input.resources) {
            val c = state.capacity
            if (c != null && c > 0L) ledger(res)
        }

        fun rankOf(item: WishlistItem): Int = if (item.id in urgentIds) 6 else item.priority

        fun earliestAffordable(item: WishlistItem, freeAt: Long): Long? {
            val res = item.costResource
            if (item.costAmount <= 0L || res == null) return freeAt
            val l = ledger(res)
            val t0 = maxOf(freeAt, l.cursor)
            val have = l.balanceAt(t0)
            if (have >= item.costAmount) return freeAt
            val cap = l.cap
            if (cap != null && item.costAmount > cap) return null
            if (l.income <= 0L) return null
            val waitMs = ceilDiv((item.costAmount - have) * HOUR_MS, l.income)
            return maxOf(freeAt, t0 + waitMs)
        }

        val entries = mutableListOf<PlanEntry>()
        val entryWhy = HashMap<Long, Candidate>()
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
                    var passedOver: WishlistItem? = null
                    var passedWait = 0L
                    for (item in candidates) {
                        val start = earliestAffordable(item, freeAt)
                        if (start == null) {
                            if (chosen == null && passedOver == null) {
                                passedOver = item
                                passedWait = -1L
                            }
                            continue
                        }
                        if (chosen == null && start - freeAt <= patienceMs) {
                            chosen = item
                            chosenStart = start
                        } else if (chosen == null && passedOver == null) {
                            passedOver = item
                            passedWait = start - freeAt
                        }
                        if (start < earliestStart) {
                            earliest = item
                            earliestStart = start
                        }
                    }
                    val pick = chosen ?: earliest ?: continue
                    val pickStart = if (chosen != null) chosenStart else earliestStart
                    val po = if (chosen != null) passedOver else null
                    val cand = Candidate(pickStart, rankOf(pick), pool.sortOrder, s, pid, pick, freeAt, po, passedWait)
                    val current = best
                    if (current == null || better(cand, current)) best = cand
                }
            }
            val winner = best ?: break

            val end = winner.startMs + winner.item.durationSeconds * 1000L
            val deadline = winner.item.finishByMs
            entries += PlanEntry(
                item = winner.item,
                poolId = winner.poolId,
                slotIndex = winner.slot,
                startMs = winner.startMs,
                endMs = end,
                waitedMs = winner.startMs - winner.freeAtMs,
                lateByMs = if (deadline != null && end > deadline) end - deadline else null,
            )
            entryWhy[winner.item.id] = winner
            if (winner.startMs > winner.freeAtMs) {
                gaps += IdleGap(winner.poolId, winner.slot, winner.freeAtMs, winner.startMs)
            }
            free.getValue(winner.poolId)[winner.slot] = end
            val res = winner.item.costResource
            if (winner.item.costAmount > 0L && res != null) {
                ledger(res).spend(winner.startMs, winner.item.costAmount)
                totalCost[res] = (totalCost[res] ?: 0L) + winner.item.costAmount
            }
            pending.remove(winner.item)
        }

        for (item in pending) {
            val res = item.costResource
            val cap = res?.let { ledger(it).cap }
            val reason = if (res != null && cap != null && item.costAmount > cap) UnplannedReason.EXCEEDS_CAP
            else UnplannedReason.NEVER_AFFORDABLE
            unplanned += UnplannedItem(item, reason)
        }

        // ---- cap warnings ----
        val capWarnings = mutableListOf<CapWarning>()
        for (l in ledgers.values.sortedBy { it.resource.ordinal }) {
            for (w in l.windows) {
                val end = w.endedAtMs
                if (end != null && end <= w.reachedAtMs) continue
                if (end == null && w.reachedAtMs - now > CAP_HORIZON_MS) continue
                val lost = if (end != null) w.lost else 0L
                capWarnings += CapWarning(
                    resource = w.resource,
                    reachedAtMs = w.reachedAtMs,
                    spendAtMs = end,
                    lostAmount = lost,
                    message = capMessage(w.resource, w.reachedAtMs, end, lost, now),
                )
            }
            // A cap that is first reached after the last planned spend never became a window; cover that too.
            if (l.windows.none { it.endedAtMs == null }) {
                val c = l.cap
                if (c != null && l.income > 0L && l.balance < c) {
                    val at = l.cursor + ceilDiv((c - l.balance) * HOUR_MS, l.income)
                    if (at - now <= CAP_HORIZON_MS) {
                        capWarnings += CapWarning(
                            l.resource, at, null, 0L, capMessage(l.resource, at, null, 0L, now),
                        )
                    }
                }
            }
        }
        capWarnings.sortBy { it.reachedAtMs }

        val ordered = entries.sortedWith(compareBy<PlanEntry>({ it.startMs }, { it.poolId }, { it.slotIndex }))

        // ---- deadline warnings ----
        val deadlineWarnings = mutableListOf<DeadlineWarning>()
        val entryByItem = ordered.associateBy { it.item.id }
        val unplannedById = unplanned.associateBy { it.item.id }
        for (item in input.wishlist) {
            val by = item.finishByMs ?: continue
            val entry = entryByItem[item.id]
            val aloneEnd = now + item.durationSeconds * 1000L
            if (aloneEnd > by) {
                val late = aloneEnd - by
                val msg = if (by <= now) "Deadline has already passed."
                else "Takes ${TimeFormat.duration(item.durationSeconds)}, so even starting right now it finishes " +
                    "${spanText(late)} after the deadline."
                deadlineWarnings += DeadlineWarning(item, DeadlineIssue.IMPOSSIBLE, late, msg)
            } else if (entry == null) {
                val why = unplannedById[item.id]?.reason
                deadlineWarnings += DeadlineWarning(
                    item, DeadlineIssue.UNPLANNED, null,
                    "Cannot be scheduled (${why?.let { reasonText(it) } ?: "no free worker"}), so the deadline cannot be met.",
                )
            } else {
                val late = entry.lateByMs
                if (late != null) {
                    val latestStart = by - item.durationSeconds * 1000L
                    deadlineWarnings += DeadlineWarning(
                        item, DeadlineIssue.LATE, late,
                        "Planned to finish ${spanText(late)} after the deadline. It would have to start " +
                            "${relative(latestStart, now)} at the latest; raise its priority or free a worker or resources.",
                    )
                }
            }
        }

        // ---- explanations ----
        val closedWindows = ledgers.values.flatMap { it.windows }.filter { it.endedAtMs != null }
        val withWhy = ordered.map { e ->
            e.copy(explanation = explain(e, entryWhy[e.item.id], input, now, urgentIds, closedWindows))
        }

        return PlanResult(
            entries = withWhy,
            unplanned = unplanned,
            idleGaps = gaps,
            completionMs = ordered.maxOfOrNull { it.endMs },
            totalCost = totalCost,
            deadlineWarnings = deadlineWarnings,
            capWarnings = capWarnings,
        )
    }

    // ---- text helpers ----------------------------------------------------------------------------------------

    private fun spanText(ms: Long): String = TimeFormat.duration(ms / 1000L)

    private fun relative(atMs: Long, nowMs: Long): String =
        if (atMs <= nowMs) "now" else "in ${spanText(atMs - nowMs)}"

    private fun reasonText(r: UnplannedReason): String = when (r) {
        UnplannedReason.NO_WORKER_POOL -> "its worker group no longer exists"
        UnplannedReason.NO_SLOTS -> "its worker group has no workers"
        UnplannedReason.NEVER_AFFORDABLE -> "there is no income to pay for it"
        UnplannedReason.EXCEEDS_CAP -> "it costs more than the storage cap"
    }

    private fun capMessage(res: Resource, reachedAt: Long, spendAt: Long?, lost: Long, now: Long): String {
        val head = "${res.label} storage ${if (reachedAt <= now) "is full" else "fills up ${relative(reachedAt, now)}"}"
        return if (spendAt == null) {
            "$head, and nothing is planned to spend it: spend or lose income."
        } else {
            "$head; the next planned spend is ${relative(spendAt, now)}, so about ${TimeFormat.compact(lost)} " +
                "${res.label} income is lost. Spend sooner."
        }
    }

    private fun explain(
        e: PlanEntry,
        w: Candidate?,
        input: PlanInput,
        now: Long,
        urgentIds: Set<Long>,
        windows: List<CapWindow>,
    ): String {
        val item = e.item
        val parts = mutableListOf<String>()
        val res = item.costResource
        val income = res?.let { input.resources[it]?.incomePerHour } ?: 0L

        // Why this time.
        if (e.waitedMs > 0L && res != null) {
            parts += "Waits ${spanText(e.waitedMs)} for ${res.label} " +
                "(needs ${TimeFormat.compact(item.costAmount)}, income +${TimeFormat.compact(income)}/h), " +
                "so it starts ${relative(e.startMs, now)}."
        } else if (e.startMs <= now) {
            parts += if (item.costAmount > 0L && res != null) {
                "Starts now: a worker is free and you can pay ${TimeFormat.compact(item.costAmount)} ${res.label}."
            } else {
                "Starts now: a worker is free."
            }
        } else {
            parts += "Starts ${relative(e.startMs, now)}, when a worker frees up."
        }

        // Why this order.
        val po = w?.passedOver
        if (w != null && po != null) {
            parts += if (w.passedOverWaitMs < 0L) {
                "Taken before \"${po.name}\" (priority ${po.priority}), which cannot be paid for yet."
            } else {
                "Taken before \"${po.name}\" (priority ${po.priority}) so the worker is not idle for " +
                    "${spanText(w.passedOverWaitMs)}."
            }
        } else if (item.id in urgentIds) {
            parts += "Moved to the front because its deadline is close."
        } else {
            val rivals = input.wishlist.filter { it.id != item.id && it.poolId == item.poolId && it.priority == item.priority }
            val by = item.finishByMs
            parts += if (rivals.isEmpty()) {
                "Priority ${item.priority}."
            } else if (by != null && rivals.any { (it.finishByMs ?: Long.MAX_VALUE) > by }) {
                "Priority ${item.priority}; the earlier deadline goes first."
            } else {
                "Priority ${item.priority}; longer upgrades go first so the whole list finishes sooner."
            }
        }

        // Deadline.
        val by = item.finishByMs
        if (by != null) {
            parts += if (e.endMs <= by) "Finishes ${spanText(by - e.endMs)} before its deadline."
            else "Late: finishes ${spanText(e.endMs - by)} after its deadline."
        }

        // Cap.
        if (res != null && item.costAmount > 0L) {
            if (windows.any { it.resource == res && it.endedAtMs == e.startMs }) {
                parts += "Spending ${res.label} here stops income being lost at the storage cap."
            }
        }
        return parts.joinToString(" ")
    }
}
