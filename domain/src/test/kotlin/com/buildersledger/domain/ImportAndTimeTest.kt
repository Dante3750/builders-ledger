package com.buildersledger.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TimeFormatTest {
    @Test
    fun formatsDurations() {
        assertEquals("1d 2h", TimeFormat.duration(93_784))
        assertEquals("2h 05m", TimeFormat.duration(7_500))
        assertEquals("12m 30s", TimeFormat.duration(750))
        assertEquals("45s", TimeFormat.duration(45))
        assertEquals("0s", TimeFormat.duration(-5))
    }

    @Test
    fun parsesWhatPlayersTypeFromTheGameScreen() {
        assertEquals(2 * 86_400L + 3 * 3_600L, TimeFormat.parse("2d 3h"))
        assertEquals(14 * 3_600L + 30 * 60L, TimeFormat.parse("14h 30m"))
        assertEquals(3 * 86_400L + 4 * 3_600L + 5 * 60L + 6L, TimeFormat.parse("3d4h5m6s"))
        assertEquals(45 * 60L, TimeFormat.parse("45M"))
    }

    @Test
    fun rejectsJunk() {
        assertNull(TimeFormat.parse(""))
        assertNull(TimeFormat.parse("soon"))
        assertNull(TimeFormat.parse("3"))
        assertNull(TimeFormat.parse("2d banana"))
    }
}

class VillageImportTest {
    private val sample = """
        {
          "tag": "#ABC123",
          "timestamp": 1700000000,
          "buildings": [
            {"data": 1000001, "lvl": 5, "cnt": 1},
            {"data": 1000008, "lvl": 12, "cnt": 2, "timer": 7200},
            {"data": 1000008, "lvl": 12, "cnt": 2, "timer": 3600}
          ],
          "units": [ {"data": 4000001, "lvl": 9, "timer": 90000} ],
          "heroes": [ {"data": 28000000, "lvl": 40} ],
          "decos": [ {"data": 18000000, "x": 1, "y": 2} ],
          "ignored_number": 7
        }
    """.trimIndent()

    @Test
    fun extractsRunningTimersOnly() {
        val r = VillageImport.parse(sample)
        assertEquals("#ABC123", r.playerTag)
        assertEquals(1_700_000_000_000L, r.exportedAtMs)
        assertEquals(3, r.active.size)
        assertEquals(setOf("buildings", "units", "heroes", "decos"), r.categories.toSet())
    }

    @Test
    fun simultaneousUpgradesOfTheSameKindGetDistinctKeys() {
        val keys = VillageImport.parse(sample).active.map { it.sourceKey }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue("buildings:1000008#0" in keys && "buildings:1000008#1" in keys)
    }

    @Test
    fun mapsCategoriesToPools() {
        assertEquals("Builders", VillageImport.defaultPoolName("buildings"))
        assertEquals("Laboratory", VillageImport.defaultPoolName("units"))
        assertEquals("Pet House", VillageImport.defaultPoolName("pets"))
    }

    @Test
    fun toleratesMillisecondTimestampsAndMissingOnes() {
        val ms = VillageImport.parse("""{"timestamp": 1700000000000, "buildings":[{"data":1,"timer":10}]}""")
        assertEquals(1_700_000_000_000L, ms.exportedAtMs)
        val none = VillageImport.parse("""{"buildings":[{"data":1,"timer":10}]}""")
        assertNull(none.exportedAtMs)
        assertTrue(none.warnings.isNotEmpty())
    }

    @Test
    fun rejectsInputThatIsNotAnExport() {
        for (bad in listOf("not json", "[1,2]", "{}", """{"tag":"#X"}""")) {
            try {
                VillageImport.parse(bad)
                fail("expected ImportException for: $bad")
            } catch (e: ImportException) {
                // expected
            }
        }
    }
}

class ImportSyncTest {
    private val exportedAt = 10_000_000L

    private fun entry(id: Long, timer: Long, ordinal: Int = 0) =
        ImportedEntry("buildings", id, 5, 1, timer, ordinal)

    private fun existing(id: Long, key: String, endsAt: Long) =
        ActiveUpgrade(id, 1, 1, "X", 5, 6, 0, endsAt, 0, null, sourceKey = key)

    @Test
    fun insertsNewUpdatesChangedAndFinishesMissing() {
        val known = listOf(
            existing(1, "buildings:1#0", exportedAt + 3_600_000L),   // unchanged
            existing(2, "buildings:2#0", exportedAt + 1_000_000L),   // time changed
            existing(3, "buildings:3#0", exportedAt - 500_000L),     // finished by itself
            existing(4, "buildings:4#0", exportedAt + 9_000_000L),   // vanished with time left
            ActiveUpgrade(5, 1, 1, "Manual", null, null, 0, exportedAt + 1, 0, null, sourceKey = null),
        )
        val imported = listOf(entry(1, 3_600), entry(2, 5_000), entry(9, 100))
        val plan = ImportSync.reconcile(known, imported, exportedAt)

        assertEquals(listOf(9L), plan.inserts.map { it.entry.dataId })
        assertEquals(exportedAt + 100_000L, plan.inserts.single().endsAtMs)
        assertEquals(listOf(2L), plan.updates.map { it.existing.id })
        assertEquals(exportedAt + 5_000_000L, plan.updates.single().newEndsAtMs)
        assertEquals(1, plan.unchanged)

        val finished = plan.finishes.associateBy { it.existing.id }
        assertEquals(setOf(3L, 4L), finished.keys)
        assertEquals(false, finished.getValue(3L).cancelled)
        assertEquals(exportedAt - 500_000L, finished.getValue(3L).endedAtMs)
        assertEquals(true, finished.getValue(4L).cancelled)
        assertEquals(exportedAt, finished.getValue(4L).endedAtMs)
    }

    @Test
    fun manualUpgradesAreNeverTouched() {
        val manual = ActiveUpgrade(5, 1, 1, "Manual", null, null, 0, exportedAt + 99, 0, null, sourceKey = null)
        val plan = ImportSync.reconcile(listOf(manual), emptyList(), exportedAt)
        assertTrue(plan.finishes.isEmpty() && plan.updates.isEmpty() && plan.inserts.isEmpty())
    }
}

class AnalyticsTest {
    private val h = 3_600_000L
    private val pool = WorkerPool(1, 1, "Builders", 2, 0)

    @Test
    fun utilizationCountsOverlapWithTheWindow() {
        val completed = listOf(
            CompletedUpgrade(1, 1, 1, "A", null, null, 0, 10 * h, 0, null, false),
            CompletedUpgrade(2, 1, 1, "B", null, null, 5 * h, 15 * h, 0, null, false),
        )
        // Window 0..20h, two slots => 40h available, 20h busy
        val u = Analytics.utilization(listOf(pool), completed, emptyList(), 0, 20 * h).single()
        assertEquals(20 * h, u.busyMs)
        assertEquals(40 * h, u.availableMs)
        assertEquals(0.5f, u.fraction, 0.0001f)
    }

    @Test
    fun runningUpgradesCountUpToTheWindowEnd() {
        val running = ActiveUpgrade(1, 1, 1, "A", null, null, 10 * h, 100 * h, 0, null)
        val u = Analytics.utilization(listOf(pool), emptyList(), listOf(running), 0, 20 * h).single()
        assertEquals(10 * h, u.busyMs)
    }

    @Test
    fun spendIgnoresCancelledAndOutOfWindow() {
        val completed = listOf(
            CompletedUpgrade(1, 1, 1, "A", null, null, 1 * h, 2 * h, 100, Resource.GOLD, false),
            CompletedUpgrade(2, 1, 1, "B", null, null, 1 * h, 2 * h, 999, Resource.GOLD, true),
            CompletedUpgrade(3, 1, 1, "C", null, null, 50 * h, 60 * h, 777, Resource.ELIXIR, false),
        )
        val running = ActiveUpgrade(4, 1, 1, "D", null, null, 3 * h, 9 * h, 40, Resource.GOLD)
        val spend = Analytics.spend(completed, listOf(running), 0, 20 * h)
        assertEquals(140L, spend[Resource.GOLD])
        assertNull(spend[Resource.ELIXIR])
    }
}
