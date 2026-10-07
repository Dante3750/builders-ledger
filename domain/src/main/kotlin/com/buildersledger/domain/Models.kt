package com.buildersledger.domain

/** Things an upgrade can cost. */
enum class Resource(val label: String) {
    GOLD("Gold"),
    ELIXIR("Elixir"),
    DARK_ELIXIR("Dark Elixir"),
    SHINY_ORE("Shiny Ore"),
    GLOWY_ORE("Glowy Ore"),
    STARRY_ORE("Starry Ore");

    companion object {
        fun fromName(name: String?): Resource? = Resource.entries.firstOrNull { it.name == name }
    }
}

data class Village(
    val id: Long,
    val name: String,
    val townHall: Int,
    val playerTag: String?,
)

/**
 * A group of identical workers, for example "Builders" with 5 slots or "Laboratory" with 1.
 * Pools are fully user-editable so the app never hard-codes game rules.
 */
data class WorkerPool(
    val id: Long,
    val villageId: Long,
    val name: String,
    val slots: Int,
    val sortOrder: Int,
)

/** An upgrade that is running right now. */
data class ActiveUpgrade(
    val id: Long,
    val villageId: Long,
    val poolId: Long,
    val name: String,
    val fromLevel: Int?,
    val toLevel: Int?,
    val startedAtMs: Long,
    val endsAtMs: Long,
    val costAmount: Long,
    val costResource: Resource?,
    /** "category:dataId#ordinal" when created by an import, null when entered by hand. */
    val sourceKey: String? = null,
    val note: String = "",
) {
    val durationMs: Long get() = (endsAtMs - startedAtMs).coerceAtLeast(0L)

    fun remainingMs(nowMs: Long): Long = (endsAtMs - nowMs).coerceAtLeast(0L)

    fun isDone(nowMs: Long): Boolean = nowMs >= endsAtMs

    fun progress(nowMs: Long): Float {
        if (nowMs >= endsAtMs) return 1f
        val total = durationMs
        if (total <= 0L) return 0f
        return ((nowMs - startedAtMs).toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }
}

/** An upgrade the player intends to do, used by the planner. */
data class WishlistItem(
    val id: Long,
    val villageId: Long,
    val poolId: Long,
    val name: String,
    val fromLevel: Int?,
    val toLevel: Int?,
    val durationSeconds: Long,
    val costAmount: Long,
    val costResource: Resource?,
    /** 1 (low) to 5 (high). */
    val priority: Int,
    /** Optional absolute deadline (epoch ms): the player wants this upgrade finished by then. */
    val finishByMs: Long? = null,
)

/** History row, written when an upgrade is collected or cancelled. */
data class CompletedUpgrade(
    val id: Long,
    val villageId: Long,
    val poolId: Long,
    val name: String,
    val fromLevel: Int?,
    val toLevel: Int?,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val costAmount: Long,
    val costResource: Resource?,
    val cancelled: Boolean,
)

/**
 * What the player has of one resource, as entered at [updatedAtMs], plus how fast it comes in.
 * The balance keeps growing with income after the entry time, so it stays useful between edits.
 *
 * [capacity] is the optional storage cap. When set, income stops accruing once the balance reaches it
 * (a balance that was already entered above the cap is simply kept, never reduced).
 */
data class ResourceState(
    val resource: Resource,
    val amount: Long,
    val incomePerHour: Long,
    val updatedAtMs: Long = 0L,
    val capacity: Long? = null,
) {
    private val cap: Long? get() = capacity?.takeIf { it > 0L }

    fun amountAt(nowMs: Long): Long {
        val elapsed = (nowMs - updatedAtMs).coerceAtLeast(0L)
        val grown = amount + incomePerHour.coerceAtLeast(0L) * elapsed / 3_600_000L
        val c = cap ?: return grown
        return minOf(grown, maxOf(c, amount))
    }

    /**
     * When the storage fills up (epoch ms), assuming nothing is spent. Null when there is no cap or no income.
     * Returns [nowMs] when the storage is already full.
     */
    fun capReachedAtMs(nowMs: Long): Long? {
        val c = cap ?: return null
        val income = incomePerHour
        if (income <= 0L) return null
        val have = amountAt(nowMs)
        if (have >= c) return nowMs
        val waitMs = ((c - have) * 3_600_000L + income - 1) / income
        return nowMs + waitMs
    }
}

/** "12 -> 13", "-> 13", "12" or null. */
fun levelLabel(from: Int?, to: Int?): String? = when {
    from != null && to != null -> "$from → $to"
    to != null -> "→ $to"
    from != null -> "$from"
    else -> null
}
