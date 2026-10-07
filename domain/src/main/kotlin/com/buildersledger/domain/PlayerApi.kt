package com.buildersledger.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

enum class ItemKind(val label: String) {
    TROOP("Troops"),
    SPELL("Spells"),
    HERO("Heroes"),
    EQUIPMENT("Hero equipment"),
    SIEGE("Siege machines"),
}

enum class ApiVillage(val label: String) {
    HOME("Home village"),
    BUILDER_BASE("Builder base"),
}

/** One unit, spell, hero, equipment piece or siege machine as reported by the player endpoint. */
data class ItemProgress(
    val name: String,
    val level: Int,
    /** 0 when the response did not say; such items are ignored by every percentage and ranking. */
    val maxLevel: Int,
    val village: ApiVillage,
    val kind: ItemKind,
    /** True for super-troop boost entries. They mirror a base troop, so they are not counted in progress. */
    val superBoost: Boolean = false,
    val superActive: Boolean = false,
) {
    val hasMax: Boolean get() = maxLevel > 0
    val counts: Boolean get() = hasMax && !superBoost
    val levelsLeft: Int get() = if (hasMax) (maxLevel - level).coerceAtLeast(0) else 0
    val isMaxed: Boolean get() = hasMax && level >= maxLevel
}

data class PlayerProgress(
    val name: String?,
    val tag: String?,
    val townHall: Int?,
    val builderHall: Int?,
    val expLevel: Int?,
    val trophies: Int?,
    val warStars: Int?,
    val clanName: String?,
    val items: List<ItemProgress>,
) {
    /** Items that take part in percentages and rankings, optionally narrowed to one village. */
    fun counted(village: ApiVillage? = null, kind: ItemKind? = null): List<ItemProgress> =
        items.filter { it.counts && (village == null || it.village == village) && (kind == null || it.kind == kind) }

    fun remaining(village: ApiVillage? = null, kind: ItemKind? = null): Remaining =
        Remaining.of(counted(village, kind))

    fun byKind(village: ApiVillage? = null): Map<ItemKind, Remaining> =
        ItemKind.entries.associateWith { remaining(village, it) }.filterValues { it.total > 0 }

    /** Not-yet-maxed items, fewest levels left first (ties: further along first, then name). */
    fun closestToMax(village: ApiVillage? = null, limit: Int = 10): List<ItemProgress> =
        counted(village).filter { !it.isMaxed }
            .sortedWith(
                compareBy<ItemProgress> { it.levelsLeft }
                    .thenByDescending { it.level.toDouble() / it.maxLevel }
                    .thenBy { it.name }
            )
            .take(limit.coerceAtLeast(0))
}

/** Aggregates over a set of items. Percentages are levels reached divided by levels available (0..1). */
data class Remaining(
    val total: Int,
    val maxed: Int,
    val levelsLeft: Int,
    val levelsReached: Int,
    val levelsAvailable: Int,
) {
    val fraction: Double get() = if (levelsAvailable <= 0) 0.0 else (levelsReached.toDouble() / levelsAvailable).coerceIn(0.0, 1.0)
    val percent: Int get() = Math.round(fraction * 100).toInt()

    companion object {
        fun of(items: List<ItemProgress>): Remaining {
            val usable = items.filter { it.hasMax }
            return Remaining(
                total = usable.size,
                maxed = usable.count { it.isMaxed },
                levelsLeft = usable.sumOf { it.levelsLeft },
                levelsReached = usable.sumOf { minOf(it.level, it.maxLevel).coerceAtLeast(0) },
                levelsAvailable = usable.sumOf { it.maxLevel },
            )
        }
    }
}

/** Everything that can go wrong, each with a message a player can act on. */
sealed class ApiError {
    abstract val message: String

    data object NoKey : ApiError() {
        override val message = "No API key set. Add your key in Settings, under \"Progress from official API\"."
    }

    data object BadTag : ApiError() {
        override val message = "That player tag does not look right. Tags start with # and use only 0 2 8 9 P Y L Q G R J C U V."
    }

    data object BadBaseUrl : ApiError() {
        override val message = "The API base URL is not valid. It must start with https:// (plain http is refused so your key never travels unencrypted)."
    }

    data class Forbidden(val detail: String?) : ApiError() {
        override val message: String
            get() = "The server refused the key (HTTP 403). Either the key is wrong, or it is not allowed from this network: " +
                "official keys only work from the IP addresses you registered, and a phone's address changes. " +
                "Use a proxy base URL and register the key for the proxy's IP." +
                (detail?.let { " Server said: $it" } ?: "")
    }

    data object NotFound : ApiError() {
        override val message = "No player with that tag was found (HTTP 404). Check the tag in the game's profile."
    }

    data object Throttled : ApiError() {
        override val message = "Too many requests (HTTP 429). Wait a minute and try again."
    }

    data object Maintenance : ApiError() {
        override val message = "The game's API is down for maintenance (HTTP 503). Try again later; your last synced data is still here."
    }

    data class Http(val code: Int, val detail: String?) : ApiError() {
        override val message: String
            get() = "The server answered with an unexpected status (HTTP $code)." + (detail?.let { " $it" } ?: "")
    }

    data object Offline : ApiError() {
        override val message = "Could not reach the server. Check your connection or the base URL; your last synced data is still here."
    }

    data object BadResponse : ApiError() {
        override val message = "The server answered, but not with player data. Check the base URL."
    }

    companion object {
        /** Maps an HTTP status and (optional) error body of the form {"reason":..., "message":...} to an error. */
        fun fromStatus(code: Int, body: String?): ApiError {
            val detail = PlayerApi.errorDetail(body)
            return when (code) {
                401, 403 -> Forbidden(detail)
                404 -> NotFound
                429 -> Throttled
                503 -> Maintenance
                else -> Http(code, detail)
            }
        }
    }
}

class ApiException(val error: ApiError) : Exception(error.message)

object PlayerApi {
    const val DEFAULT_BASE_URL = "https://api.clashofclans.com/v1"

    private const val TAG_CHARS = "0289PYLQGRJCUV"
    private const val MIN_TAG_BODY = 3
    private const val MAX_TAG_BODY = 15

    private val SIEGE_NAMES = setOf(
        "wall wrecker", "battle blimp", "stone slammer", "siege barracks",
        "log launcher", "flame flinger", "battle drill", "troop launcher",
    )

    /** Trims, uppercases, adds a leading #, turns letter O into zero. Returns null when the result is not a valid tag. */
    fun normalizeTag(raw: String?): String? {
        if (raw == null) return null
        val body = raw.trim().uppercase().trimStart('#').replace('O', '0')
        if (body.length !in MIN_TAG_BODY..MAX_TAG_BODY) return null
        if (body.any { it !in TAG_CHARS }) return null
        return "#$body"
    }

    /** Trims and strips trailing slashes; null unless it is an https URL with a host (the key must never travel in clear text). */
    fun normalizeBaseUrl(raw: String?): String? {
        val trimmed = raw?.trim()?.trimEnd('/') ?: return null
        val lower = trimmed.lowercase()
        val rest = when {
            lower.startsWith("https://") -> trimmed.substring(8)
            else -> return null
        }
        if (rest.isBlank() || rest.any { it.isWhitespace() } || rest.startsWith("/") || rest.startsWith("?")) return null
        return trimmed
    }

    /** `{base}/players/%23TAG`, or null when the base URL or the tag is invalid. */
    fun buildUrl(baseUrl: String?, tag: String?): String? {
        val base = normalizeBaseUrl(baseUrl) ?: return null
        val normalized = normalizeTag(tag) ?: return null
        return "$base/players/%23${normalized.removePrefix("#")}"
    }

    /** Reads {"reason", "message"} from an error body; null when there is nothing usable. */
    fun errorDetail(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val obj = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val reason = obj.str("reason")
        val message = obj.str("message")
        return listOfNotNull(reason, message).distinct().joinToString(": ").ifBlank { null }
    }

    /**
     * Parses a player response. Every field is optional and unknown fields are ignored.
     * Throws [ApiException] ([ApiError.BadResponse]) only when the text is not a JSON object at all.
     */
    fun parse(json: String): PlayerProgress {
        val root = runCatching { Json.parseToJsonElement(json.trim()) }.getOrNull() as? JsonObject
            ?: throw ApiException(ApiError.BadResponse)

        val items = ArrayList<ItemProgress>()
        root.objects("troops").forEach { o ->
            val name = o.str("name") ?: return@forEach
            val kind = if (name.trim().lowercase() in SIEGE_NAMES) ItemKind.SIEGE else ItemKind.TROOP
            val superFlag = o.bool("superTroopIsActive")
            items += item(o, name, kind, superBoost = superFlag != null || name.startsWith("Super ", ignoreCase = true), superActive = superFlag == true)
        }
        root.objects("spells").forEach { o -> o.str("name")?.let { items += item(o, it, ItemKind.SPELL) } }

        val nested = ArrayList<ItemProgress>()
        root.objects("heroes").forEach { o ->
            val name = o.str("name") ?: return@forEach
            items += item(o, name, ItemKind.HERO)
            o.objects("equipment").forEach { e ->
                e.str("name")?.let { en -> nested += item(e, en, ItemKind.EQUIPMENT, villageFrom = o) }
            }
        }
        val top = root.objects("heroEquipment").mapNotNull { e -> e.str("name")?.let { item(e, it, ItemKind.EQUIPMENT) } }
        // The top-level list is authoritative; nested equipment only fills gaps.
        val seen = HashSet<Pair<String, ApiVillage>>()
        (top + nested).forEach { if (seen.add(it.name to it.village)) items += it }

        val clan = root["clan"] as? JsonObject
        return PlayerProgress(
            name = root.str("name"),
            tag = root.str("tag"),
            townHall = root.int("townHallLevel")?.takeIf { it > 0 },
            builderHall = root.int("builderHallLevel")?.takeIf { it > 0 },
            expLevel = root.int("expLevel"),
            trophies = root.int("trophies"),
            warStars = root.int("warStars"),
            clanName = clan?.str("name"),
            items = items,
        )
    }

    private fun item(
        o: JsonObject,
        name: String,
        kind: ItemKind,
        superBoost: Boolean = false,
        superActive: Boolean = false,
        villageFrom: JsonObject = o,
    ) = ItemProgress(
        name = name.trim(),
        level = (o.int("level") ?: 0).coerceAtLeast(0),
        maxLevel = (o.int("maxLevel") ?: 0).coerceAtLeast(0),
        village = villageOf(o.str("village") ?: villageFrom.str("village")),
        kind = kind,
        superBoost = superBoost,
        superActive = superActive,
    )

    private fun villageOf(raw: String?): ApiVillage {
        val s = raw?.lowercase()?.filter { it.isLetter() }
        return if (s == "builderbase") ApiVillage.BUILDER_BASE else ApiVillage.HOME
    }

    // ---- tolerant JSON readers: wrong types and nulls just read as "absent" ----

    /** Plain string, or a localized-name object like {"en":"Barbarian"} (the official schema calls it JsonLocalizedName). */
    private fun JsonObject.str(key: String): String? {
        val v = this[key]
        val text = when (v) {
            is JsonPrimitive -> v.takeIf { it !is JsonNull }?.contentOrNull
            is JsonObject -> ((v["en"] ?: v.values.firstOrNull()) as? JsonPrimitive)?.contentOrNull
            else -> null
        }
        return text?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun JsonObject.int(key: String): Int? {
        val p = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull } ?: return null
        val d = p.doubleOrNull ?: p.contentOrNull?.trim()?.toDoubleOrNull() ?: return null
        if (d.isNaN() || d.isInfinite()) return null
        return d.coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()).toInt()
    }

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.booleanOrNull ?: it.contentOrNull?.lowercase()?.toBooleanStrictOrNull() }

    private fun JsonObject.objects(key: String): List<JsonObject> =
        (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
}
