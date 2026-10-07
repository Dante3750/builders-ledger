package com.buildersledger.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.buildersledger.domain.ActiveUpgrade
import com.buildersledger.domain.CompletedUpgrade
import com.buildersledger.domain.Resource
import com.buildersledger.domain.ResourceState
import com.buildersledger.domain.Village
import com.buildersledger.domain.WishlistItem
import com.buildersledger.domain.WorkerPool
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "villages")
data class VillageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val townHall: Int,
    val playerTag: String?,
)

@Serializable
@Entity(
    tableName = "pools",
    foreignKeys = [
        ForeignKey(
            entity = VillageEntity::class,
            parentColumns = ["id"],
            childColumns = ["villageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("villageId")],
)
data class PoolEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val villageId: Long,
    val name: String,
    val slots: Int,
    val sortOrder: Int,
)

@Serializable
@Entity(
    tableName = "actives",
    foreignKeys = [
        ForeignKey(
            entity = VillageEntity::class,
            parentColumns = ["id"],
            childColumns = ["villageId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PoolEntity::class,
            parentColumns = ["id"],
            childColumns = ["poolId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("villageId"), Index("poolId")],
)
data class ActiveEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val villageId: Long,
    val poolId: Long,
    val name: String,
    val fromLevel: Int?,
    val toLevel: Int?,
    val startedAtMs: Long,
    val endsAtMs: Long,
    val costAmount: Long,
    val costResource: String?,
    val sourceKey: String?,
    val note: String,
)

@Serializable
@Entity(
    tableName = "wishlist",
    foreignKeys = [
        ForeignKey(
            entity = VillageEntity::class,
            parentColumns = ["id"],
            childColumns = ["villageId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PoolEntity::class,
            parentColumns = ["id"],
            childColumns = ["poolId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("villageId"), Index("poolId")],
)
data class WishEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val villageId: Long,
    val poolId: Long,
    val name: String,
    val fromLevel: Int?,
    val toLevel: Int?,
    val durationSeconds: Long,
    val costAmount: Long,
    val costResource: String?,
    val priority: Int,
    val finishByMs: Long? = null,
)

@Serializable
@Entity(
    tableName = "history",
    foreignKeys = [
        ForeignKey(
            entity = VillageEntity::class,
            parentColumns = ["id"],
            childColumns = ["villageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    // No foreign key to pools on purpose: history must survive a pool being deleted.
    indices = [Index("villageId")],
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val villageId: Long,
    val poolId: Long,
    val name: String,
    val fromLevel: Int?,
    val toLevel: Int?,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val costAmount: Long,
    val costResource: String?,
    val cancelled: Boolean,
)

@Serializable
@Entity(
    tableName = "resources",
    primaryKeys = ["villageId", "resource"],
    foreignKeys = [
        ForeignKey(
            entity = VillageEntity::class,
            parentColumns = ["id"],
            childColumns = ["villageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ResourceEntity(
    val villageId: Long,
    val resource: String,
    val amount: Long,
    val incomePerHour: Long,
    val updatedAtMs: Long,
    /** Optional storage cap; null = not set. */
    val capacity: Long? = null,
)

/** Maps an in-game data id (from the village export) to a name the player gave it. */
@Serializable
@Entity(tableName = "labels")
data class LabelEntity(
    @PrimaryKey val dataId: Long,
    val name: String,
)

// ---- mappers -------------------------------------------------------------------------------------------------

fun VillageEntity.toDomain() = Village(id, name, townHall, playerTag)
fun Village.toEntity() = VillageEntity(id, name, townHall, playerTag)

fun PoolEntity.toDomain() = WorkerPool(id, villageId, name, slots, sortOrder)
fun WorkerPool.toEntity() = PoolEntity(id, villageId, name, slots, sortOrder)

fun ActiveEntity.toDomain() = ActiveUpgrade(
    id = id, villageId = villageId, poolId = poolId, name = name,
    fromLevel = fromLevel, toLevel = toLevel, startedAtMs = startedAtMs, endsAtMs = endsAtMs,
    costAmount = costAmount, costResource = Resource.fromName(costResource), sourceKey = sourceKey, note = note,
)

fun ActiveUpgrade.toEntity() = ActiveEntity(
    id = id, villageId = villageId, poolId = poolId, name = name,
    fromLevel = fromLevel, toLevel = toLevel, startedAtMs = startedAtMs, endsAtMs = endsAtMs,
    costAmount = costAmount, costResource = costResource?.name, sourceKey = sourceKey, note = note,
)

fun WishEntity.toDomain() = WishlistItem(
    id = id, villageId = villageId, poolId = poolId, name = name, fromLevel = fromLevel, toLevel = toLevel,
    durationSeconds = durationSeconds, costAmount = costAmount, costResource = Resource.fromName(costResource),
    priority = priority, finishByMs = finishByMs,
)

fun WishlistItem.toEntity() = WishEntity(
    id = id, villageId = villageId, poolId = poolId, name = name, fromLevel = fromLevel, toLevel = toLevel,
    durationSeconds = durationSeconds, costAmount = costAmount, costResource = costResource?.name, priority = priority,
    finishByMs = finishByMs,
)

fun HistoryEntity.toDomain() = CompletedUpgrade(
    id = id, villageId = villageId, poolId = poolId, name = name, fromLevel = fromLevel, toLevel = toLevel,
    startedAtMs = startedAtMs, endedAtMs = endedAtMs, costAmount = costAmount,
    costResource = Resource.fromName(costResource), cancelled = cancelled,
)

fun CompletedUpgrade.toEntity() = HistoryEntity(
    id = id, villageId = villageId, poolId = poolId, name = name, fromLevel = fromLevel, toLevel = toLevel,
    startedAtMs = startedAtMs, endedAtMs = endedAtMs, costAmount = costAmount,
    costResource = costResource?.name, cancelled = cancelled,
)

fun ResourceEntity.toDomain(): ResourceState? {
    val r = Resource.fromName(resource) ?: return null
    return ResourceState(r, amount, incomePerHour, updatedAtMs, capacity)
}

fun ResourceState.toEntity(villageId: Long) =
    ResourceEntity(villageId, resource.name, amount, incomePerHour, updatedAtMs, capacity)
