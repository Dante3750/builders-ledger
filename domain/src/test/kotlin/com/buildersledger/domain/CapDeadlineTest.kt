package com.buildersledger.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapDeadlineTest {
    private val now = 10_000_000L
    private val h = Planner.HOUR_MS

    private fun pool(id: Long, slots: Int, order: Int = 0) = WorkerPool(id, 1, "Pool $id", slots, order)

    private fun item(
        id: Long,
        pool: Long,
        hours: Long,
        priority: Int = 3,
        cost: Long = 0,
        res: Resource? = null,
        by: Long? = null,
    ) = WishlistItem(id, 1, pool, "Item $id", null, null, hours * 3600, cost, res, priority, by)

    private fun gold(amount: Long, income: Long, cap: Long?) =
        mapOf(Resource.GOLD to ResourceState(Resource.GOLD, amount, income, now, cap))

    private fun run(
        wishlist: List<WishlistItem>,
        resources: Map<Resource, ResourceState> = emptyMap(),
        pools: List<WorkerPool> = listOf(pool(1, 1)),
        active: List<ActiveUpgrade> = emptyList(),
    ) = Planner.plan(PlanInput(now, pools, active, wishlist, resources))

    // ---- ResourceState with a cap ----

    @Test
    fun incomeStopsAtTheCap() {
        val s = ResourceState(Resource.GOLD, 900, 100, now, capacity = 1000)
        assertEquals(1000L, s.amountAt(now + 5 * h))
        assertEquals(950L, s.amountAt(now + h / 2))
    }

    @Test
    fun balanceEnteredAboveTheCapIsKept() {
        val s = ResourceState(Resource.GOLD, 1200, 100, now, capacity = 1000)
        assertEquals(1200L, s.amountAt(now + 3 * h))
    }

    @Test
    fun capReachedTimeIsComputed() {
        val s = ResourceState(Resource.GOLD, 0, 100, now, capacity = 1000)
        assertEquals(now + 10 * h, s.capReachedAtMs(now))
        // Asked 6h later, 4h of income remain, so the answer is still the same absolute moment.
        assertEquals(now + 10 * h, s.capReachedAtMs(now + 6 * h))
        assertNull(ResourceState(Resource.GOLD, 0, 100, now).capReachedAtMs(now))
        assertNull(ResourceState(Resource.GOLD, 0, 0, now, 1000).capReachedAtMs(now))
    }

    @Test
    fun alreadyFullStorageReportsNow() {
        val s = ResourceState(Resource.GOLD, 1000, 100, now, capacity = 1000)
        assertEquals(now, s.capReachedAtMs(now))
    }

    @Test
    fun zeroOrNegativeCapMeansNoCap() {
        val s = ResourceState(Resource.GOLD, 0, 100, now, capacity = 0)
        assertEquals(5000L, s.amountAt(now + 50 * h))
        assertNull(s.capReachedAtMs(now))
    }

    // ---- planner with caps ----

    @Test
    fun plannerStopsAccruingAboveTheCap() {
        // 500 + 100/h, cap 1000. A 2000-gold item can never be paid for: it exceeds the cap.
        val r = run(listOf(item(1, 1, 1, cost = 2000, res = Resource.GOLD)), gold(500, 100, cap = 1000))
        assertTrue(r.entries.isEmpty())
        assertEquals(UnplannedReason.EXCEEDS_CAP, r.unplanned.single().reason)
    }

    @Test
    fun itemThatFitsTheCapIsStillAffordedWhenIncomeArrives() {
        val r = run(listOf(item(1, 1, 1, cost = 1000, res = Resource.GOLD)), gold(500, 100, cap = 1000))
        assertEquals(now + 5 * h, r.entries.single().startMs)
    }

    @Test
    fun capWarningWhenNothingSpendsTheResource() {
        val r = run(emptyList(), gold(0, 100, cap = 1000))
        val w = r.capWarnings.single()
        assertEquals(Resource.GOLD, w.resource)
        assertEquals(now + 10 * h, w.reachedAtMs)
        assertNull(w.spendAtMs)
        assertTrue(w.message.contains("spend or lose income"))
    }

    @Test
    fun noCapWarningWithoutIncomeOrCap() {
        assertTrue(run(emptyList(), gold(0, 0, cap = 1000)).capWarnings.isEmpty())
        assertTrue(run(emptyList(), gold(0, 100, cap = null)).capWarnings.isEmpty())
    }

    @Test
    fun capReachedFarInTheFutureIsNotNoise() {
        // 100 days to fill
        assertTrue(run(emptyList(), gold(0, 10, cap = 24_000)).capWarnings.isEmpty())
    }

    @Test
    fun capWarningReportsLostIncomeUntilTheNextSpend() {
        // Full storage (1000/1000) now, income 100/h. The only gold item is gated by a busy worker for 5h.
        val busy = ActiveUpgrade(77, 1, 1, "Running", null, null, now - h, now + 5 * h, 0, null)
        val r = run(
            listOf(item(1, 1, 2, cost = 400, res = Resource.GOLD)),
            gold(1000, 100, cap = 1000),
            active = listOf(busy),
        )
        // First window: full now until the worker frees up. Afterwards the storage refills (600 -> 1000 in 4h).
        assertEquals(2, r.capWarnings.size)
        val w = r.capWarnings.first()
        assertEquals(now, w.reachedAtMs)
        assertEquals(now + 5 * h, w.spendAtMs)
        assertEquals(500L, w.lostAmount) // 5h of 100/h wasted
        assertTrue(r.entries.single().explanation.contains("storage cap"))
    }

    @Test
    fun spendingBeforeTheCapAvoidsAWarning() {
        // Fills in 10h; the 600-gold item starts now and brings the balance down.
        val r = run(listOf(item(1, 1, 1, cost = 600, res = Resource.GOLD)), gold(0, 100, cap = 1000))
        // 0 gold -> must wait 6h; after the spend the storage refills later than the 7-day horizon? 10h -> yes within horizon.
        assertEquals(now + 6 * h, r.entries.single().startMs)
        val w = r.capWarnings.single()
        assertNull(w.spendAtMs) // after the one spend it fills again with nothing left to spend
    }

    // ---- deadlines ----

    @Test
    fun deadlineThatFitsRaisesNoWarning() {
        val r = run(listOf(item(1, 1, 2, by = now + 3 * h)))
        assertTrue(r.deadlineWarnings.isEmpty())
        assertNull(r.entries.single().lateByMs)
        assertTrue(r.entries.single().explanation.contains("before its deadline"))
    }

    @Test
    fun impossibleDeadlineIsFlagged() {
        val r = run(listOf(item(1, 1, 10, by = now + 3 * h)))
        val w = r.deadlineWarnings.single()
        assertEquals(DeadlineIssue.IMPOSSIBLE, w.issue)
        assertEquals(7 * h, w.lateByMs)
        assertEquals(7 * h, r.entries.single().lateByMs)
    }

    @Test
    fun deadlineAlreadyInThePastIsImpossible() {
        val r = run(listOf(item(1, 1, 1, by = now - h)))
        val w = r.deadlineWarnings.single()
        assertEquals(DeadlineIssue.IMPOSSIBLE, w.issue)
        assertTrue(w.message.contains("already passed"))
    }

    @Test
    fun deadlineMissedBecauseOfQueueingIsLate() {
        // One builder. A (5h, priority 5) runs first, B (5h, priority 1) must finish within 7h but ends at 10h.
        val r = run(
            listOf(item(1, 1, 5, priority = 5), item(2, 1, 5, priority = 1, by = now + 7 * h)),
        )
        // B has 2h of slack (<24h window), so it is urgent and jumps the queue: no warning.
        assertEquals(listOf(2L, 1L), r.entries.map { it.item.id })
        assertTrue(r.deadlineWarnings.isEmpty())
    }

    @Test
    fun urgentWindowOfZeroDisablesQueueJumping() {
        val r = Planner.plan(
            PlanInput(
                now, listOf(pool(1, 1)), emptyList(),
                listOf(item(1, 1, 5, priority = 5), item(2, 1, 5, priority = 1, by = now + 7 * h)),
                emptyMap(),
            ),
            urgencyWindowMs = -1L,
        )
        assertEquals(listOf(1L, 2L), r.entries.map { it.item.id })
        val w = r.deadlineWarnings.single()
        assertEquals(DeadlineIssue.LATE, w.issue)
        assertEquals(3 * h, w.lateByMs)
    }

    @Test
    fun earlierDeadlineGoesFirstAtEqualPriority() {
        val r = run(
            listOf(
                item(1, 1, 4, priority = 3, by = now + 100 * h),
                item(2, 1, 4, priority = 3, by = now + 50 * h),
            ),
        )
        assertEquals(listOf(2L, 1L), r.entries.map { it.item.id })
    }

    @Test
    fun unaffordableItemWithDeadlineIsReportedAsUnplanned() {
        val r = run(listOf(item(1, 1, 1, cost = 500, res = Resource.GOLD, by = now + 5 * h)), gold(0, 0, null))
        assertEquals(DeadlineIssue.UNPLANNED, r.deadlineWarnings.single().issue)
    }

    @Test
    fun deadlineMissedBecauseOfResourcesIsLate() {
        // Needs 1000 gold at 100/h => starts in 10h, runs 2h, deadline in 6h (slack is fine alone: 4h).
        val r = run(listOf(item(1, 1, 2, cost = 1000, res = Resource.GOLD, by = now + 6 * h)), gold(0, 100, null))
        val w = r.deadlineWarnings.single()
        assertEquals(DeadlineIssue.LATE, w.issue)
        assertEquals(6 * h, w.lateByMs)
    }

    // ---- explanations ----

    @Test
    fun explanationsDescribeWhyNowAndWhyThisOrder() {
        val r = run(listOf(item(1, 1, 3, priority = 4, cost = 100, res = Resource.GOLD)), gold(500, 0, null))
        val e = r.entries.single().explanation
        assertTrue(e, e.startsWith("Starts now"))
        assertTrue(e, e.contains("Priority 4"))
    }

    @Test
    fun explanationMentionsWaitingForResources() {
        val r = run(listOf(item(1, 1, 3, cost = 1000, res = Resource.GOLD)), gold(0, 100, null))
        val e = r.entries.single().explanation
        assertTrue(e, e.contains("Waits 10h 00m for Gold"))
    }

    @Test
    fun explanationMentionsAnItemItWasTakenBefore() {
        // High priority item needs 10h of income; the cheap one fills the gap (patience 1h).
        val r = run(
            listOf(
                item(1, 1, 4, priority = 5, cost = 1000, res = Resource.GOLD),
                item(2, 1, 2, priority = 1, cost = 0),
            ),
            gold(0, 100, null),
        )
        val cheap = r.entries.first { it.item.id == 2L }
        assertTrue(cheap.explanation, cheap.explanation.contains("Taken before \"Item 1\""))
    }

    @Test
    fun everyEntryHasAnExplanation() {
        val r = run(
            listOf(item(1, 1, 3), item(2, 1, 4), item(3, 1, 1)),
            pools = listOf(pool(1, 2)),
        )
        assertEquals(3, r.entries.size)
        assertFalse(r.entries.any { it.explanation.isBlank() })
        assertNotNull(r.entries.first())
    }

    @Test
    fun compactNumbers() {
        assertEquals("900", TimeFormat.compact(900))
        assertEquals("45k", TimeFormat.compact(45_000))
        assertEquals("1.25M", TimeFormat.compact(1_250_000))
        assertEquals("3M", TimeFormat.compact(3_000_000))
        assertEquals("-12k", TimeFormat.compact(-12_000))
    }
}
