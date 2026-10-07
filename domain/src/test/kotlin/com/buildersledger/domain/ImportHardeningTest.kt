package com.buildersledger.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ImportHardeningTest {
    private fun parse(json: String) = VillageImport.parse(json)

    @Test
    fun timestampInSecondsMillisecondsAndMicroseconds() {
        val sec = parse("""{"timestamp": 1700000000, "buildings":[{"data":1,"timer":60}]}""")
        val ms = parse("""{"timestamp": 1700000000000, "buildings":[{"data":1,"timer":60}]}""")
        val us = parse("""{"timestamp": 1700000000000000, "buildings":[{"data":1,"timer":60}]}""")
        assertEquals(1_700_000_000_000L, sec.exportedAtMs)
        assertEquals(1_700_000_000_000L, ms.exportedAtMs)
        assertEquals(1_700_000_000_000L, us.exportedAtMs)
    }

    @Test
    fun timestampAsStringOrFloat() {
        assertEquals(
            1_700_000_000_000L,
            parse("""{"timestamp": "1700000000", "buildings":[{"data":1,"timer":60}]}""").exportedAtMs,
        )
        assertEquals(
            1_700_000_000_000L,
            parse("""{"timestamp": 1700000000.0, "buildings":[{"data":1,"timer":60}]}""").exportedAtMs,
        )
    }

    @Test
    fun badTimestampIsReportedNotFatal() {
        val r = parse("""{"timestamp": "yesterday", "buildings":[{"data":1,"timer":60}]}""")
        assertNull(r.exportedAtMs)
        assertTrue(r.warnings.any { it.contains("timestamp") })
        val zero = parse("""{"timestamp": 0, "buildings":[{"data":1,"timer":60}]}""")
        assertNull(zero.exportedAtMs)
    }

    @Test
    fun normalizeTimestampBoundaries() {
        assertNull(VillageImport.normalizeTimestamp(0))
        assertNull(VillageImport.normalizeTimestamp(-5))
        assertEquals(99_999_999_999L * 1000L, VillageImport.normalizeTimestamp(99_999_999_999L))
        assertEquals(100_000_000_000L, VillageImport.normalizeTimestamp(100_000_000_000L))
    }

    @Test
    fun numbersAsStringsAreAccepted() {
        val r = parse("""{"buildings":[{"data":"1000008","lvl":"12","cnt":"2","timer":"7200"}]}""")
        val e = r.active.single()
        assertEquals(1000008L, e.dataId)
        assertEquals(12, e.level)
        assertEquals(2, e.count)
        assertEquals(7200L, e.timerSeconds)
    }

    @Test
    fun missingOptionalFieldsDefault() {
        val r = parse("""{"buildings":[{"data":5,"timer":30}]}""")
        val e = r.active.single()
        assertEquals(0, e.level)
        assertEquals(1, e.count)
    }

    @Test
    fun nullAndGarbageFieldsAreIgnored() {
        val r = parse(
            """{"buildings":[
                {"data":1,"timer":null,"lvl":null},
                {"data":2,"timer":"soon"},
                {"data":3,"timer":-5},
                {"data":null,"timer":99},
                {"data":"abc","timer":99},
                {"data":4,"timer":120,"extra":{"deep":[1,2,3]}}
            ]}""",
        )
        assertEquals(listOf(4L), r.active.map { it.dataId })
        assertTrue(r.warnings.any { it.contains("not a number") })
    }

    @Test
    fun implausiblyLongTimersAreDropped() {
        val r = parse("""{"buildings":[{"data":1,"timer":999999999},{"data":2,"timer":3600}]}""")
        assertEquals(listOf(2L), r.active.map { it.dataId })
        assertTrue(r.warnings.any { it.contains("longer than a year") })
    }

    @Test
    fun negativeLevelAndCountAreClamped() {
        val e = parse("""{"buildings":[{"data":1,"lvl":-3,"cnt":0,"timer":10}]}""").active.single()
        assertEquals(0, e.level)
        assertEquals(1, e.count)
    }

    @Test
    fun extraTopLevelKeysOfAnyShapeAreIgnored() {
        val r = parse(
            """{"version": 3, "meta": {"a": [1,2]}, "note": "hello", "flag": true, "nothing": null,
                "buildings":[{"data":1,"timer":60}], "weird_array": [1,2,3], "strings": ["a"], "objs": [{"x":1}]}""",
        )
        assertEquals(1, r.active.size)
        assertEquals(listOf("buildings"), r.categories)
    }

    @Test
    fun unknownCategoriesAreCountedButNeverTreatedAsUpgrades() {
        val r = parse("""{"mystery":[{"data":1,"timer":60}], "buildings":[{"data":2,"timer":60}]}""")
        assertEquals(listOf(2L), r.active.map { it.dataId })
        assertTrue("mystery" in r.categories)
    }

    @Test
    fun tagMayBeMissingOrBlank() {
        assertNull(parse("""{"buildings":[{"data":1,"timer":60}]}""").playerTag)
        assertNull(parse("""{"tag":"  ","buildings":[{"data":1,"timer":60}]}""").playerTag)
        assertEquals("#X1", parse("""{"tag":" #X1 ","buildings":[{"data":1,"timer":60}]}""").playerTag)
    }

    @Test
    fun markdownFenceAndChatterAroundTheJsonAreStripped() {
        val body = """{"buildings":[{"data":1,"timer":60}]}"""
        assertEquals(1, parse("```json\n$body\n```").active.size)
        assertEquals(1, parse("Here is my export:\n$body\nthanks!").active.size)
        assertEquals(1, parse("﻿$body").active.size)
    }

    @Test
    fun exportWrappedInOneExtraObjectIsFound() {
        val r = parse("""{"status":"ok","export":{"tag":"#A","timestamp":1700000000,"buildings":[{"data":1,"timer":60}]}}""")
        assertEquals(1, r.active.size)
        assertEquals("#A", r.playerTag)
        assertEquals(1_700_000_000_000L, r.exportedAtMs)
        assertTrue(r.warnings.any { it.contains("wrapped") })
    }

    @Test
    fun emptyOrNonObjectInputGivesReadableErrors() {
        for (bad in listOf("", "   ", "not json", "[1,2,3]", "42", "{}", """{"a":1}""", """{"buildings":[]}""")) {
            try {
                parse(bad)
                fail("expected ImportException for: $bad")
            } catch (e: ImportException) {
                assertTrue(e.message!!.isNotBlank())
            }
        }
    }

    @Test
    fun exportWithNoRunningTimersWarns() {
        val r = parse("""{"timestamp":1700000000,"buildings":[{"data":1,"lvl":3}]}""")
        assertTrue(r.active.isEmpty())
        assertTrue(r.warnings.any { it.contains("No running upgrades") })
    }

    @Test
    fun ordinalsStayStableForRepeatedIds() {
        val r = parse("""{"buildings":[{"data":9,"timer":10},{"data":9,"timer":20},{"data":9,"timer":30}]}""")
        assertEquals(listOf("buildings:9#0", "buildings:9#1", "buildings:9#2"), r.active.map { it.sourceKey })
    }

    @Test
    fun syncWorksWithMillisecondTimestampsToo() {
        val r = parse("""{"timestamp":1700000000000,"buildings":[{"data":1,"timer":3600}]}""")
        val plan = ImportSync.reconcile(emptyList(), r.active, r.exportedAtMs!!)
        assertEquals(1_700_000_000_000L + 3_600_000L, plan.inserts.single().endsAtMs)
    }
}

class WeeklySummaryTest {
    private val day = 86_400_000L
    private val now = 100 * day
    private val builders = WorkerPool(1, 1, "Builders", 2, 0)
    private val lab = WorkerPool(2, 1, "Laboratory", 1, 1)

    private fun done(id: Long, pool: Long, startedDaysAgo: Double, endedDaysAgo: Double, cost: Long = 0, res: Resource? = null, cancelled: Boolean = false) =
        CompletedUpgrade(
            id, 1, pool, "Item $id", null, null,
            now - (startedDaysAgo * day).toLong(), now - (endedDaysAgo * day).toLong(), cost, res, cancelled,
        )

    @Test
    fun emptyWeekSaysSo() {
        val s = Analytics.weeklySummary(listOf(builders), emptyList(), emptyList(), now)
        assertTrue(s.isEmpty)
        assertEquals(0, s.finished)
        assertTrue(s.highlights.first().contains("No upgrades finished"))
    }

    @Test
    fun countsFinishedCancelledAndPreviousWeek() {
        val completed = listOf(
            done(1, 1, 3.0, 2.0),
            done(2, 1, 6.0, 5.0),
            done(3, 1, 4.0, 3.5, cancelled = true),
            done(4, 1, 9.0, 8.0), // previous week
            done(5, 1, 20.0, 19.0), // two weeks+ ago: not counted at all
        )
        val s = Analytics.weeklySummary(listOf(builders), completed, emptyList(), now)
        assertEquals(2, s.finished)
        assertEquals(1, s.cancelled)
        assertEquals(1, s.previousFinished)
        assertTrue(s.highlights.first().contains("2 upgrades finished (1 more than the week before)"))
    }

    @Test
    fun spendSkipsCancelledAndOldUpgrades() {
        val completed = listOf(
            done(1, 1, 3.0, 2.0, cost = 1_000_000, res = Resource.GOLD),
            done(2, 1, 3.0, 2.0, cost = 500, res = Resource.ELIXIR, cancelled = true),
            done(3, 1, 10.0, 9.0, cost = 777, res = Resource.GOLD),
        )
        val s = Analytics.weeklySummary(listOf(builders), completed, emptyList(), now)
        assertEquals(mapOf(Resource.GOLD to 1_000_000L), s.spend)
        assertTrue(s.highlights.any { it.contains("Spent 1M Gold") })
    }

    @Test
    fun busiestAndIdlestPoolsAndIdleTime() {
        // Builders (2 slots) busy 7 of 14 slot-days = 50%. Lab (1 slot) busy 7 of 7 = 100%.
        val completed = listOf(
            done(1, 1, 7.0, 0.0),
            done(2, 2, 7.0, 0.0),
        )
        val s = Analytics.weeklySummary(listOf(builders, lab), completed, emptyList(), now)
        assertEquals("Laboratory", s.busiest!!.pool.name)
        assertEquals("Builders", s.idlest!!.pool.name)
        assertEquals(7 * day, s.idleMs)
        assertTrue(s.highlights.any { it.contains("Laboratory were busiest (100%)") })
    }

    @Test
    fun longestFinishedIsReported() {
        val completed = listOf(done(1, 1, 3.0, 2.0), done(2, 1, 6.0, 2.5))
        val s = Analytics.weeklySummary(listOf(builders), completed, emptyList(), now)
        assertEquals(2L, s.longestFinished!!.id)
        assertTrue(s.highlights.any { it.startsWith("Longest finished: Item 2 (3d 12h)") })
    }

    @Test
    fun runningUpgradesCountTowardBusyTime() {
        val running = ActiveUpgrade(9, 1, 2, "Running", null, null, now - 2 * day, now + day, 0, null)
        val s = Analytics.weeklySummary(listOf(lab), emptyList(), listOf(running), now)
        assertEquals(2 * day, s.utilization.single().busyMs)
        assertEquals(0, s.finished)
    }

    @Test
    fun singularWording() {
        val s = Analytics.weeklySummary(listOf(builders), listOf(done(1, 1, 2.0, 1.0)), emptyList(), now)
        assertTrue(s.highlights.first().startsWith("1 upgrade finished"))
    }
}
