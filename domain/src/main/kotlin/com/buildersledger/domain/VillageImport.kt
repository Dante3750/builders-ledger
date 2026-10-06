package com.buildersledger.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

class ImportException(message: String) : Exception(message)

/** One running upgrade found in a village export. */
data class ImportedEntry(
    val category: String,
    val dataId: Long,
    /** The level shown in the export. The running upgrade targets level + 1. */
    val level: Int,
    val count: Int,
    val timerSeconds: Long,
    /** Distinguishes several simultaneous upgrades of the same kind (two cannons, say). */
    val ordinal: Int,
) {
    val sourceKey: String get() = "$category:$dataId#$ordinal"
}

data class VillageImportResult(
    val playerTag: String?,
    val exportedAtMs: Long?,
    val active: List<ImportedEntry>,
    val totalEntries: Int,
    val categories: List<String>,
    val warnings: List<String>,
)

/**
 * Reads the JSON that the game's own "data export" produces and extracts every running timer.
 *
 * The parser is deliberately tolerant: it only relies on top-level arrays of objects that carry a numeric
 * "data" id, plus optional "lvl", "cnt" and "timer" (seconds remaining at the export "timestamp").
 * Names are not in the export, so callers resolve ids through a user-maintained label table.
 */
object VillageImport {
    /** Categories that can hold a running upgrade. */
    val TRACKED: Set<String> = setOf(
        "buildings", "buildings2", "traps", "heroes", "units", "spells", "siege_machines", "pets", "equipment",
    )

    fun defaultPoolName(category: String): String = when (category) {
        "buildings", "traps" -> "Builders"
        "buildings2" -> "Builder Base"
        "heroes" -> "Heroes"
        "units", "spells", "siege_machines" -> "Laboratory"
        "pets" -> "Pet House"
        "equipment" -> "Blacksmith"
        else -> "Builders"
    }

    fun parse(text: String): VillageImportResult {
        val root = try {
            Json.parseToJsonElement(text.trim())
        } catch (e: Exception) {
            throw ImportException("That doesn't look like valid JSON.")
        }
        val obj = root as? JsonObject ?: throw ImportException("Expected a JSON object at the top level.")

        val tag = (obj["tag"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val rawTimestamp = (obj["timestamp"] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
        // Unix seconds normally; tolerate milliseconds.
        val exportedAtMs = rawTimestamp?.let { if (it > 100_000_000_000L) it else it * 1000L }

        var total = 0
        val categories = mutableListOf<String>()
        val active = mutableListOf<ImportedEntry>()
        val ordinals = HashMap<String, Int>()

        for ((category, value) in obj) {
            val array = value as? JsonArray ?: continue
            val rows = array.mapNotNull { it as? JsonObject }.filter { it["data"] != null }
            if (rows.isEmpty()) continue
            categories += category
            total += rows.size
            if (category !in TRACKED) continue
            for (row in rows) {
                val id = row.long("data") ?: continue
                val timer = row.long("timer") ?: 0L
                if (timer <= 0L) continue
                val level = row.long("lvl")?.toInt() ?: 0
                val count = row.long("cnt")?.toInt() ?: 1
                val key = "$category:$id"
                val ordinal = ordinals[key] ?: 0
                ordinals[key] = ordinal + 1
                active += ImportedEntry(category, id, level, count, timer, ordinal)
            }
        }

        if (categories.isEmpty()) {
            throw ImportException("No village data found. Use the game's data export and paste the whole text.")
        }

        val warnings = mutableListOf<String>()
        if (exportedAtMs == null) {
            warnings += "No timestamp in the export, so timers are counted from the moment you import."
        }
        if (active.isEmpty()) {
            warnings += "No running upgrades were found in this export."
        }
        return VillageImportResult(tag, exportedAtMs, active, total, categories, warnings)
    }

    private fun JsonObject.long(name: String): Long? {
        val p = this[name] as? JsonPrimitive ?: return null
        return p.longOrNull ?: p.doubleOrNull?.toLong()
    }
}
