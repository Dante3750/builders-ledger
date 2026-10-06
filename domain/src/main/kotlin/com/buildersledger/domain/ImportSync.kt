package com.buildersledger.domain

import kotlin.math.abs

data class SyncInsert(val entry: ImportedEntry, val endsAtMs: Long)

data class SyncUpdate(val existing: ActiveUpgrade, val newEndsAtMs: Long)

data class SyncFinish(val existing: ActiveUpgrade, val endedAtMs: Long, val cancelled: Boolean)

data class SyncPlan(
    val inserts: List<SyncInsert>,
    val updates: List<SyncUpdate>,
    val finishes: List<SyncFinish>,
    val unchanged: Int,
)

/**
 * Compares running upgrades the app already knows (that came from earlier imports) with a fresh export,
 * so importing again keeps the board in sync instead of duplicating it.
 *
 * Upgrades entered by hand (no sourceKey) are never touched.
 */
object ImportSync {
    fun reconcile(
        existing: List<ActiveUpgrade>,
        imported: List<ImportedEntry>,
        exportedAtMs: Long,
        slackMs: Long = 60_000L,
    ): SyncPlan {
        val byKey = LinkedHashMap<String, ActiveUpgrade>()
        for (e in existing) {
            val key = e.sourceKey ?: continue
            if (key !in byKey) byKey[key] = e
        }

        val inserts = mutableListOf<SyncInsert>()
        val updates = mutableListOf<SyncUpdate>()
        var unchanged = 0
        val importedKeys = HashSet<String>()

        for (entry in imported) {
            importedKeys += entry.sourceKey
            val endsAt = exportedAtMs + entry.timerSeconds * 1000L
            val current = byKey[entry.sourceKey]
            when {
                current == null -> inserts += SyncInsert(entry, endsAt)
                abs(current.endsAtMs - endsAt) > slackMs -> updates += SyncUpdate(current, endsAt)
                else -> unchanged++
            }
        }

        val finishes = mutableListOf<SyncFinish>()
        for ((key, current) in byKey) {
            if (key in importedKeys) continue
            // Gone from the export: it finished on its own, was rushed, or was cancelled.
            val cancelled = current.endsAtMs > exportedAtMs + slackMs
            val endedAt = minOf(current.endsAtMs, exportedAtMs)
            finishes += SyncFinish(current, endedAt, cancelled)
        }
        return SyncPlan(inserts, updates, finishes, unchanged)
    }
}
