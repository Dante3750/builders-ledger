package com.buildersledger.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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

    /** Anything above this many seconds (one year) is treated as garbage rather than a running upgrade. */
    const val MAX_TIMER_SECONDS = 366L * 86_400L

    /**
     * Turns a raw export timestamp into epoch milliseconds. Accepts seconds (normal), milliseconds
     * and microseconds; returns null for zero or negative values.
     */
    fun normalizeTimestamp(raw: Long): Long? = when {
        raw <= 0L -> null
        raw >= 100_000_000_000_000L -> raw / 1000L // microseconds
        raw >= 100_000_000_000L -> raw // milliseconds
        else -> raw * 1000L // seconds
    }

    /** Pastes often arrive with a BOM, a markdown fence or chatter around the JSON; keep just the object. */
    internal fun cleanText(text: String): String {
        var t = text.trim().removePrefix("\uFEFF").trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```").substringAfter('\n', t).trim()
            t = t.removeSuffix("```").trim()
        }
        if (!t.startsWith("{")) {
            val first = t.indexOf('{')
            val last = t.lastIndexOf('}')
            if (first >= 0 && last > first) t = t.substring(first, last + 1)
        }
        return t
    }

    fun parse(text: String): VillageImportResult {
        val root = try {
            Json.parseToJsonElement(cleanText(text))
        } catch (e: Exception) {
            throw ImportException("That doesn't look like valid JSON.")
        }
        val top = root as? JsonObject ?: throw ImportException("Expected a JSON object at the top level.")

        val warnings = mutableListOf<String>()
        // Normally the categories sit at the top level. Some tools wrap the export in one more object.
        var obj: JsonObject = top
        if (!hasCategories(top)) {
            val inner = top.entries.firstNotNullOfOrNull { (key, value) ->
                (value as? JsonObject)?.takeIf { hasCategories(it) }?.let { key to it }
            }
            if (inner != null) {
                obj = inner.second
                warnings += "The export was wrapped inside \"${inner.first}\"; read it from there."
            }
        }

        val tag = (obj["tag"] as? JsonPrimitive ?: top["tag"] as? JsonPrimitive)
            ?.contentOrNull?.trim()?.takeIf { it.isNotBlank() && it != "null" }
        val rawTimestamp = (obj["timestamp"] as? JsonPrimitive ?: top["timestamp"] as? JsonPrimitive)?.asLong()
        val exportedAtMs = rawTimestamp?.let { normalizeTimestamp(it) }

        var total = 0
        var skipped = 0
        var implausible = 0
        val categories = mutableListOf<String>()
        val active = mutableListOf<ImportedEntry>()
        val ordinals = HashMap<String, Int>()

        for ((category, value) in obj) {
            val array = value as? JsonArray ?: continue
            val rows = array.mapNotNull { it as? JsonObject }.filter { it["data"] != null && it["data"] !is JsonNull }
            if (rows.isEmpty()) continue
            categories += category
            total += rows.size
            if (category !in TRACKED) continue
            for (row in rows) {
                val id = row.long("data")
                if (id == null) {
                    skipped++
                    continue
                }
                val timer = row.long("timer") ?: 0L
                if (timer <= 0L) continue
                if (timer > MAX_TIMER_SECONDS) {
                    implausible++
                    continue
                }
                val level = (row.long("lvl") ?: 0L).coerceIn(0L, 10_000L).toInt()
                val count = (row.long("cnt") ?: 1L).coerceIn(1L, 10_000L).toInt()
                val key = "$category:$id"
                val ordinal = ordinals[key] ?: 0
                ordinals[key] = ordinal + 1
                active += ImportedEntry(category, id, level, count, timer, ordinal)
            }
        }

        if (categories.isEmpty()) {
            throw ImportException("No village data found. Use the game's data export and paste the whole text.")
        }

        if (rawTimestamp != null && exportedAtMs == null) {
            warnings += "The export timestamp was not a valid time, so timers are counted from the moment you import."
        } else if (exportedAtMs == null) {
            warnings += "No timestamp in the export, so timers are counted from the moment you import."
        }
        if (skipped > 0) warnings += "Skipped $skipped entries whose id was not a number."
        if (implausible > 0) warnings += "Ignored $implausible timers longer than a year."
        if (active.isEmpty()) {
            warnings += "No running upgrades were found in this export."
        }
        return VillageImportResult(tag, exportedAtMs, active, total, categories, warnings)
    }

    private fun hasCategories(obj: JsonObject): Boolean =
        obj.values.any { v ->
            v is JsonArray && v.any { it is JsonObject && it["data"] != null && it["data"] !is JsonNull }
        }

    /** Numbers may arrive as 12, 12.0, "12" or " 12 ". Anything else is null. */
    private fun JsonPrimitive.asLong(): Long? {
        if (this is JsonNull) return null
        longOrNull?.let { return it }
        doubleOrNull?.let { if (it.isFinite()) return it.toLong() }
        if (isString) {
            val t = content.trim()
            return t.toLongOrNull() ?: t.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()
        }
        return null
    }

    private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.asLong()
}
