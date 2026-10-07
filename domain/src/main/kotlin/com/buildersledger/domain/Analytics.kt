package com.buildersledger.domain

data class PoolUtilization(
    val pool: WorkerPool,
    val busyMs: Long,
    val availableMs: Long,
) {
    /** 0..1: share of worker time that was spent on an upgrade. */
    val fraction: Float
        get() = if (availableMs <= 0L) 0f else (busyMs.toDouble() / availableMs.toDouble()).toFloat().coerceIn(0f, 1f)
}

/** A rolling seven-day digest for the Insights screen. */
data class WeeklySummary(
    val windowStartMs: Long,
    val windowEndMs: Long,
    val finished: Int,
    val cancelled: Int,
    /** Upgrades finished in the seven days before the window, for comparison. */
    val previousFinished: Int,
    val spend: Map<Resource, Long>,
    val utilization: List<PoolUtilization>,
    /** Worker time that sat idle across all pools, in milliseconds. */
    val idleMs: Long,
    val busiest: PoolUtilization?,
    val idlest: PoolUtilization?,
    val longestFinished: CompletedUpgrade?,
    /** Short human-readable lines, ready to show. */
    val highlights: List<String>,
) {
    val isEmpty: Boolean get() = finished == 0 && cancelled == 0 && spend.isEmpty() && utilization.all { it.busyMs == 0L }
}

object Analytics {

    const val WEEK_MS = 7L * 86_400_000L

    /**
     * Summarises the last seven days ending at [nowMs]. Cancelled upgrades are counted separately and do not
     * count as finished or as spend (the game refunds them).
     */
    fun weeklySummary(
        pools: List<WorkerPool>,
        completed: List<CompletedUpgrade>,
        active: List<ActiveUpgrade>,
        nowMs: Long,
    ): WeeklySummary {
        val start = nowMs - WEEK_MS
        val prevStart = start - WEEK_MS
        val doneThisWeek = completed.filter { !it.cancelled && it.endedAtMs in start..nowMs }
        val cancelled = completed.count { it.cancelled && it.endedAtMs in start..nowMs }
        val previous = completed.count { !it.cancelled && it.endedAtMs >= prevStart && it.endedAtMs < start }
        val spend = spend(completed, active, start, nowMs)
        val util = utilization(pools, completed, active, start, nowMs)
        val idle = util.sumOf { it.availableMs - it.busyMs }
        val measurable = util.filter { it.availableMs > 0L }
        val busiest = measurable.maxByOrNull { it.fraction }
        val idlest = measurable.minByOrNull { it.fraction }
        val longest = doneThisWeek.maxByOrNull { it.endedAtMs - it.startedAtMs }

        val lines = mutableListOf<String>()
        if (doneThisWeek.isEmpty()) {
            lines += "No upgrades finished in the last 7 days."
        } else {
            val delta = doneThisWeek.size - previous
            val cmp = when {
                previous == 0 && delta > 0 -> ""
                delta > 0 -> " ($delta more than the week before)"
                delta < 0 -> " (${-delta} fewer than the week before)"
                else -> " (same as the week before)"
            }
            lines += "${doneThisWeek.size} upgrade${if (doneThisWeek.size == 1) "" else "s"} finished$cmp."
        }
        if (cancelled > 0) lines += "$cancelled cancelled."
        if (spend.isNotEmpty()) {
            lines += "Spent " + spend.entries.joinToString(", ") { "${TimeFormat.compact(it.value)} ${it.key.label}" } + "."
        }
        if (busiest != null && idlest != null) {
            val pct = { u: PoolUtilization -> (u.fraction * 100f).toInt() }
            if (busiest.pool.id == idlest.pool.id) {
                lines += "${busiest.pool.name} were busy ${pct(busiest)}% of the time."
            } else {
                lines += "${busiest.pool.name} were busiest (${pct(busiest)}%); ${idlest.pool.name} had the most idle time (${pct(idlest)}% busy)."
            }
        }
        if (idle > 0L && measurable.isNotEmpty()) {
            lines += "${TimeFormat.duration(idle / 1000L)} of worker time sat idle."
        }
        if (longest != null) {
            lines += "Longest finished: ${longest.name} (${TimeFormat.duration((longest.endedAtMs - longest.startedAtMs) / 1000L)})."
        }
        return WeeklySummary(
            start, nowMs, doneThisWeek.size, cancelled, previous, spend, util, idle, busiest, idlest, longest, lines,
        )
    }


    private fun overlap(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Long =
        (minOf(aEnd, bEnd) - maxOf(aStart, bStart)).coerceAtLeast(0L)

    /**
     * How busy each pool was inside the window. Busy time counts both finished and running upgrades.
     * Note that upgrades created by an import start counting at the import time, because the export
     * does not say when they began.
     */
    fun utilization(
        pools: List<WorkerPool>,
        completed: List<CompletedUpgrade>,
        active: List<ActiveUpgrade>,
        windowStartMs: Long,
        windowEndMs: Long,
    ): List<PoolUtilization> {
        val windowMs = (windowEndMs - windowStartMs).coerceAtLeast(0L)
        return pools.map { pool ->
            var busy = 0L
            for (c in completed) {
                if (c.poolId == pool.id) busy += overlap(c.startedAtMs, c.endedAtMs, windowStartMs, windowEndMs)
            }
            for (a in active) {
                if (a.poolId == pool.id) busy += overlap(a.startedAtMs, minOf(a.endsAtMs, windowEndMs), windowStartMs, windowEndMs)
            }
            val available = windowMs * pool.slots.coerceAtLeast(0)
            PoolUtilization(pool, busy.coerceAtMost(available), available)
        }
    }

    /** Resources spent on upgrades that started inside the window. Cancelled upgrades are refunded, so they are skipped. */
    fun spend(
        completed: List<CompletedUpgrade>,
        active: List<ActiveUpgrade>,
        windowStartMs: Long,
        windowEndMs: Long,
    ): Map<Resource, Long> {
        val out = LinkedHashMap<Resource, Long>()
        for (c in completed) {
            val res = c.costResource ?: continue
            if (c.cancelled || c.costAmount <= 0L) continue
            if (c.startedAtMs in windowStartMs..windowEndMs) out[res] = (out[res] ?: 0L) + c.costAmount
        }
        for (a in active) {
            val res = a.costResource ?: continue
            if (a.costAmount <= 0L) continue
            if (a.startedAtMs in windowStartMs..windowEndMs) out[res] = (out[res] ?: 0L) + a.costAmount
        }
        return out
    }
}
