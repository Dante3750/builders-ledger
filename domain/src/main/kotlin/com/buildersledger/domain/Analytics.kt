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

object Analytics {

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
