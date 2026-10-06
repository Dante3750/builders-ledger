package com.buildersledger.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerTest {
    private val now = 1_000_000L
    private val h = Planner.HOUR_MS

    private fun pool(id: Long, slots: Int, order: Int = 0) = WorkerPool(id, 1, "Pool $id", slots, order)

    private fun item(
        id: Long,
        pool: Long,
        hours: Long,
        priority: Int = 3,
        cost: Long = 0,
        res: Resource? = null,
    ) = WishlistItem(id, 1, pool, "Item $id", null, null, hours * 3600, cost, res, priority)

    private fun active(pool: Long, endsAt: Long) =
        ActiveUpgrade(100 + endsAt, 1, pool, "Running", null, null, now - h, endsAt, 0, null)

    private fun run(
        pools: List<WorkerPool>,
        wishlist: List<WishlistItem>,
        active: List<ActiveUpgrade> = emptyList(),
        resources: Map<Resource, ResourceState> = emptyMap(),
        patience: Long = Planner.DEFAULT_PATIENCE_MS,
    ) = Planner.plan(PlanInput(now, pools, active, wishlist, resources), patience)

    private fun gold(amount: Long, income: Long, updatedAt: Long = now) =
        mapOf(Resource.GOLD to ResourceState(Resource.GOLD, amount, income, updatedAt))

    @Test
    fun balanceKeepsGrowingWithIncomeSinceItWasEntered() {
        // Entered 10h ago with 0 gold and 100/h income => 1000 gold available now
        val r = run(
            listOf(pool(1, 1)),
            listOf(item(1, 1, 1, cost = 1000, res = Resource.GOLD)),
            resources = gold(0, 100, updatedAt = now - 10 * h),
        )
        assertEquals(now, r.entries.single().startMs)
        assertEquals(1000L, ResourceState(Resource.GOLD, 0, 100, now - 10 * h).amountAt(now))
    }

    @Test
    fun longestFirstKeepsMakespanShortWithTwoBuilders() {
        val r = run(listOf(pool(1, 2)), listOf(item(1, 1, 10), item(2, 1, 5), item(3, 1, 5)))
        assertEquals(10 * h, r.completionMs!! - now)
        assertEquals(listOf(now, now, now + 5 * h), r.entries.map { it.startMs }.sorted())
    }

    @Test
    fun higherPriorityGoesFirst() {
        val r = run(listOf(pool(1, 1)), listOf(item(1, 1, 10, priority = 1), item(2, 1, 5, priority = 5)))
        assertEquals(listOf(2L, 1L), r.entries.map { it.item.id })
        assertEquals(r.entries[0].endMs, r.entries[1].startMs)
    }

    @Test
    fun waitsForResourcesAndRecordsIdleGap() {
        val r = run(listOf(pool(1, 1)), listOf(item(1, 1, 1, cost = 200, res = Resource.GOLD)), resources = gold(100, 10))
        assertEquals(now + 10 * h, r.entries[0].startMs)
        assertEquals(10 * h, r.entries[0].waitedMs)
        assertEquals(listOf(IdleGap(1, 0, now, now + 10 * h)), r.idleGaps)
    }

    @Test
    fun cheapLowPriorityItemFillsALongWait() {
        val items = listOf(
            item(1, 1, 1, priority = 5, cost = 500, res = Resource.GOLD),
            item(2, 1, 1, priority = 1, cost = 50, res = Resource.GOLD),
        )
        val r = run(listOf(pool(1, 1)), items, resources = gold(100, 80))
        assertEquals(2L, r.entries[0].item.id)
        assertEquals(now, r.entries[0].startMs)
        assertTrue(r.entries.any { it.item.id == 1L })
    }

    @Test
    fun largePatienceWaitsForTheTopPriorityItem() {
        val items = listOf(
            item(1, 1, 1, priority = 5, cost = 500, res = Resource.GOLD),
            item(2, 1, 1, priority = 1, cost = 50, res = Resource.GOLD),
        )
        val r = run(listOf(pool(1, 1)), items, resources = gold(100, 80), patience = 10 * h)
        assertEquals(1L, r.entries[0].item.id)
    }

    @Test
    fun itemThatCanNeverBeAffordedIsReported() {
        val r = run(listOf(pool(1, 1)), listOf(item(1, 1, 1, cost = 999, res = Resource.GOLD)), resources = gold(100, 0))
        assertTrue(r.entries.isEmpty())
        assertEquals(UnplannedReason.NEVER_AFFORDABLE, r.unplanned.single().reason)
    }

    @Test
    fun spendingIsSharedAcrossBuilders() {
        val items = listOf(
            item(1, 1, 1, cost = 100, res = Resource.GOLD),
            item(2, 1, 1, cost = 100, res = Resource.GOLD),
        )
        val noIncome = run(listOf(pool(1, 2)), items, resources = gold(100, 0))
        assertEquals(1, noIncome.entries.size)
        assertEquals(1, noIncome.unplanned.size)

        val withIncome = run(listOf(pool(1, 2)), items, resources = gold(100, 100))
        assertEquals(listOf(now, now + h), withIncome.entries.map { it.startMs }.sorted())
    }

    @Test
    fun runningUpgradesOccupySlots() {
        val twoSlots = run(listOf(pool(1, 2)), listOf(item(1, 1, 1)), active = listOf(active(1, now + 4 * h)))
        assertEquals(now, twoSlots.entries[0].startMs)

        val oneSlot = run(listOf(pool(1, 1)), listOf(item(1, 1, 1)), active = listOf(active(1, now + 4 * h)))
        assertEquals(now + 4 * h, oneSlot.entries[0].startMs)
    }

    @Test
    fun finishedUpgradeFreesItsSlotImmediately() {
        val r = run(listOf(pool(1, 1)), listOf(item(1, 1, 1)), active = listOf(active(1, now - 5 * h)))
        assertEquals(now, r.entries[0].startMs)
    }

    @Test
    fun unknownPoolAndZeroSlotsAreReported() {
        val r = run(listOf(pool(1, 0)), listOf(item(1, 99, 1), item(2, 1, 1)))
        val reasons = r.unplanned.associate { it.item.id to it.reason }
        assertEquals(UnplannedReason.NO_WORKER_POOL, reasons[1L])
        assertEquals(UnplannedReason.NO_SLOTS, reasons[2L])
    }

    @Test
    fun pools_areIndependentButShareTheResourceBalance() {
        val pools = listOf(pool(1, 1, order = 0), pool(2, 1, order = 1))
        val items = listOf(
            item(1, 1, 1, cost = 300, res = Resource.GOLD),
            item(2, 2, 1, cost = 100, res = Resource.GOLD),
        )
        val r = run(pools, items, resources = gold(100, 100), patience = 0)
        val byId = r.entries.associateBy { it.item.id }
        assertEquals(now, byId.getValue(2L).startMs)
        assertEquals(now + 3 * h, byId.getValue(1L).startMs)
    }

    @Test
    fun totalCostAddsUpPlannedSpend() {
        val items = listOf(
            item(1, 1, 1, cost = 100, res = Resource.GOLD),
            item(2, 1, 1, cost = 50, res = Resource.GOLD),
        )
        val r = run(listOf(pool(1, 2)), items, resources = gold(1000, 0))
        assertEquals(150L, r.totalCost[Resource.GOLD])
    }
}
