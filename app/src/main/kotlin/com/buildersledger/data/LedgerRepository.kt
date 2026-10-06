package com.buildersledger.data

import androidx.room.withTransaction
import com.buildersledger.domain.ActiveUpgrade
import com.buildersledger.domain.CompletedUpgrade
import com.buildersledger.domain.ImportSync
import com.buildersledger.domain.Resource
import com.buildersledger.domain.ResourceState
import com.buildersledger.domain.Village
import com.buildersledger.domain.VillageImport
import com.buildersledger.domain.VillageImportResult
import com.buildersledger.domain.WishlistItem
import com.buildersledger.domain.WorkerPool
import com.buildersledger.notify.UpgradeAlarmScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Imported upgrades whose in-game id has no name yet get this prefix until the player names them. */
const val PLACEHOLDER_PREFIX = "Unnamed "

fun isPlaceholderName(name: String): Boolean = name.startsWith(PLACEHOLDER_PREFIX)

/** "buildings:1000008#1" -> 1000008 */
fun dataIdOf(sourceKey: String?): Long? =
    sourceKey?.substringAfter(':', "")?.substringBefore('#')?.toLongOrNull()

private fun labelFor(labels: Map<Long, String>, sourceKey: String?): String? {
    val id = dataIdOf(sourceKey) ?: return null
    return labels[id]
}

data class ImportSummary(
    val added: Int,
    val updated: Int,
    val finished: Int,
    val unchanged: Int,
    val tagMismatch: Boolean,
)

class LedgerRepository(
    private val db: LedgerDatabase,
    private val scheduler: UpgradeAlarmScheduler,
    private val settings: SettingsStore,
) {
    private val dao = db.dao()

    // ---- villages ----------------------------------------------------------------------------------------------

    fun villages(): Flow<List<Village>> = dao.villages().map { list -> list.map { it.toDomain() } }

    suspend fun createVillage(name: String, townHall: Int, builders: Int): Long = db.withTransaction {
        val id = dao.insertVillage(
            VillageEntity(name = name.trim().ifBlank { "My village" }, townHall = townHall.coerceIn(1, 30), playerTag = null)
        )
        dao.insertPool(PoolEntity(villageId = id, name = "Builders", slots = builders.coerceIn(1, 10), sortOrder = 0))
        dao.insertPool(PoolEntity(villageId = id, name = "Laboratory", slots = 1, sortOrder = 1))
        id
    }

    suspend fun updateVillage(village: Village) = dao.updateVillage(village.toEntity())

    suspend fun deleteVillage(id: Long) {
        dao.activesOnce(id).forEach { scheduler.cancel(it.id) }
        dao.deleteVillage(id)
    }

    // ---- worker pools ------------------------------------------------------------------------------------------

    fun pools(villageId: Long): Flow<List<WorkerPool>> = dao.pools(villageId).map { list -> list.map { it.toDomain() } }

    suspend fun savePool(pool: WorkerPool): Long =
        if (pool.id == 0L) {
            dao.insertPool(pool.toEntity())
        } else {
            dao.updatePool(pool.toEntity())
            pool.id
        }

    /** Deleting a pool also removes the running upgrades and wishlist items that belong to it. */
    suspend fun deletePool(id: Long) {
        dao.allActives().filter { it.poolId == id }.forEach { scheduler.cancel(it.id) }
        dao.deletePool(id)
    }

    // ---- running upgrades --------------------------------------------------------------------------------------

    fun active(villageId: Long): Flow<List<ActiveUpgrade>> = dao.actives(villageId).map { list -> list.map { it.toDomain() } }

    /**
     * Inserts or updates a running upgrade and (re)schedules its alarm.
     * [payFromBalance] subtracts the cost from the stored resource balance, for new upgrades only.
     */
    suspend fun saveActive(a: ActiveUpgrade, payFromBalance: Boolean = false, nowMs: Long = System.currentTimeMillis()): Long {
        val id = db.withTransaction {
            val newId = if (a.id == 0L) {
                dao.insertActive(a.toEntity())
            } else {
                dao.updateActive(a.toEntity())
                a.id
            }
            learnLabel(a)
            if (payFromBalance && a.id == 0L) spend(a.villageId, a.costResource, a.costAmount, nowMs)
            newId
        }
        scheduleAlarm(a.copy(id = id))
        return id
    }

    /** Moves a running upgrade to history, either because it finished or because the player cancelled it. */
    suspend fun finishActive(a: ActiveUpgrade, endedAtMs: Long, cancelled: Boolean) {
        db.withTransaction {
            dao.insertHistory(
                HistoryEntity(
                    villageId = a.villageId, poolId = a.poolId, name = a.name,
                    fromLevel = a.fromLevel, toLevel = a.toLevel,
                    startedAtMs = a.startedAtMs, endedAtMs = endedAtMs,
                    costAmount = a.costAmount, costResource = a.costResource?.name, cancelled = cancelled,
                )
            )
            dao.deleteActive(a.id)
        }
        scheduler.cancel(a.id)
    }

    suspend fun collectAllDone(villageId: Long, nowMs: Long): Int {
        val done = dao.activesOnce(villageId).map { it.toDomain() }.filter { it.isDone(nowMs) }
        done.forEach { finishActive(it, it.endsAtMs, cancelled = false) }
        return done.size
    }

    // ---- wishlist ----------------------------------------------------------------------------------------------

    fun wishlist(villageId: Long): Flow<List<WishlistItem>> = dao.wishlist(villageId).map { list -> list.map { it.toDomain() } }

    suspend fun saveWish(item: WishlistItem): Long =
        if (item.id == 0L) {
            dao.insertWish(item.toEntity())
        } else {
            dao.updateWish(item.toEntity())
            item.id
        }

    suspend fun deleteWish(id: Long) = dao.deleteWish(id)

    /** Turns a planned upgrade into a running one, pays for it from the stored balance, and removes it from the wishlist. */
    suspend fun startFromWishlist(item: WishlistItem, nowMs: Long): Long {
        val a = ActiveUpgrade(
            id = 0, villageId = item.villageId, poolId = item.poolId, name = item.name,
            fromLevel = item.fromLevel, toLevel = item.toLevel,
            startedAtMs = nowMs, endsAtMs = nowMs + item.durationSeconds * 1000L,
            costAmount = item.costAmount, costResource = item.costResource,
        )
        val id = db.withTransaction {
            val newId = dao.insertActive(a.toEntity())
            dao.deleteWish(item.id)
            spend(item.villageId, item.costResource, item.costAmount, nowMs)
            newId
        }
        scheduleAlarm(a.copy(id = id))
        return id
    }

    // ---- history, resources, labels ----------------------------------------------------------------------------

    fun history(villageId: Long): Flow<List<CompletedUpgrade>> = dao.history(villageId).map { list -> list.map { it.toDomain() } }

    suspend fun deleteHistory(id: Long) = dao.deleteHistory(id)

    fun resources(villageId: Long): Flow<Map<Resource, ResourceState>> =
        dao.resources(villageId).map { list -> list.mapNotNull { it.toDomain() }.associateBy { it.resource } }

    suspend fun saveResources(villageId: Long, states: List<ResourceState>) {
        db.withTransaction {
            states.forEach {
                dao.upsertResource(ResourceEntity(villageId, it.resource.name, it.amount, it.incomePerHour, it.updatedAtMs))
            }
        }
    }

    fun labels(): Flow<Map<Long, String>> = dao.labels().map { list -> list.associate { it.dataId to it.name } }

    suspend fun setLabel(dataId: Long, name: String) {
        if (name.isNotBlank()) dao.upsertLabel(LabelEntity(dataId, name.trim()))
    }

    // ---- import --------------------------------------------------------------------------------------------------

    /**
     * Brings the running upgrades of [villageId] in line with a village export:
     * new timers are added, changed timers are updated, and timers that vanished are moved to history.
     * [names] are labels the player typed for ids the app did not know yet.
     */
    suspend fun applyImport(
        villageId: Long,
        result: VillageImportResult,
        names: Map<Long, String>,
        nowMs: Long,
    ): ImportSummary {
        val exportedAt = result.exportedAtMs ?: nowMs
        val scheduleIds = mutableListOf<Long>()
        val cancelIds = mutableListOf<Long>()
        var mismatch = false
        var added = 0
        var updated = 0
        var finished = 0
        var unchanged = 0

        db.withTransaction {
            names.forEach { (id, name) -> if (name.isNotBlank()) dao.upsertLabel(LabelEntity(id, name.trim())) }
            val labels = dao.labelsOnce().associate { it.dataId to it.name }
            val village = dao.village(villageId) ?: throw IllegalStateException("Village not found")
            val existing = dao.activesOnce(villageId).map { it.toDomain() }
            val plan = ImportSync.reconcile(existing, result.active, exportedAt)

            val pools = dao.poolsOnce(villageId).toMutableList()
            suspend fun poolFor(category: String): PoolEntity {
                val poolName = VillageImport.defaultPoolName(category)
                pools.firstOrNull { it.name.equals(poolName, ignoreCase = true) }?.let { return it }
                val inCategory = result.active.count { VillageImport.defaultPoolName(it.category) == poolName }
                val created = PoolEntity(villageId = villageId, name = poolName, slots = maxOf(1, inCategory), sortOrder = pools.size)
                val newId = dao.insertPool(created)
                val saved = created.copy(id = newId)
                pools += saved
                return saved
            }

            for (ins in plan.inserts) {
                val e = ins.entry
                val pool = poolFor(e.category)
                val name = labels[e.dataId] ?: "$PLACEHOLDER_PREFIX${e.category} #${e.dataId}"
                val from = e.level.takeIf { it > 0 }
                val newId = dao.insertActive(
                    ActiveEntity(
                        villageId = villageId, poolId = pool.id, name = name,
                        fromLevel = from, toLevel = from?.plus(1),
                        startedAtMs = minOf(exportedAt, ins.endsAtMs), endsAtMs = ins.endsAtMs,
                        costAmount = 0, costResource = null, sourceKey = e.sourceKey, note = "",
                    )
                )
                scheduleIds += newId
                added++
            }

            val touched = HashSet<Long>()
            for (u in plan.updates) {
                val label = labelFor(labels, u.existing.sourceKey)
                val name = if (isPlaceholderName(u.existing.name) && label != null) label else u.existing.name
                val changed = u.existing.copy(endsAtMs = u.newEndsAtMs, name = name)
                dao.updateActive(changed.toEntity())
                scheduleIds += changed.id
                touched += changed.id
                updated++
            }

            for (f in plan.finishes) {
                val a = f.existing
                dao.insertHistory(
                    HistoryEntity(
                        villageId = villageId, poolId = a.poolId, name = a.name, fromLevel = a.fromLevel, toLevel = a.toLevel,
                        startedAtMs = a.startedAtMs, endedAtMs = f.endedAtMs,
                        costAmount = a.costAmount, costResource = a.costResource?.name, cancelled = f.cancelled,
                    )
                )
                dao.deleteActive(a.id)
                cancelIds += a.id
                touched += a.id
                finished++
            }

            // Names learned just now also fix upgrades that were already on the board under a placeholder name.
            for (a in existing) {
                if (a.id in touched || a.sourceKey == null || !isPlaceholderName(a.name)) continue
                val label = labelFor(labels, a.sourceKey) ?: continue
                dao.updateActive(a.copy(name = label).toEntity())
                scheduleIds += a.id
            }
            unchanged = plan.unchanged

            val tag = result.playerTag
            if (tag != null) {
                val known = village.playerTag
                if (known == null) {
                    dao.updateVillage(village.copy(playerTag = tag))
                } else if (!known.equals(tag, ignoreCase = true)) {
                    mismatch = true
                }
            }
        }

        cancelIds.forEach { scheduler.cancel(it) }
        for (id in scheduleIds.distinct()) {
            dao.active(id)?.let { scheduleAlarm(it.toDomain()) }
        }
        return ImportSummary(added, updated, finished, unchanged, mismatch)
    }

    // ---- alarms ------------------------------------------------------------------------------------------------

    suspend fun rescheduleAll(nowMs: Long = System.currentTimeMillis()) {
        val lead = settings.leadMinutes.first()
        val villages = dao.villagesOnce().associateBy { it.id }
        for (a in dao.activesEndingAfter(nowMs)) {
            scheduler.schedule(a.id, a.name, villages[a.villageId]?.name ?: "Village", a.endsAtMs, lead)
        }
    }

    private suspend fun scheduleAlarm(a: ActiveUpgrade) {
        val villageName = dao.village(a.villageId)?.name ?: "Village"
        scheduler.schedule(a.id, a.name, villageName, a.endsAtMs, settings.leadMinutes.first())
    }

    // ---- backup ------------------------------------------------------------------------------------------------

    suspend fun exportBackup(nowMs: Long = System.currentTimeMillis()): String = BackupCodec.encode(
        BackupFile(
            exportedAtMs = nowMs,
            villages = dao.villagesOnce(),
            pools = dao.allPools(),
            actives = dao.allActives(),
            wishlist = dao.allWishlist(),
            history = dao.allHistory(),
            resources = dao.allResources(),
            labels = dao.labelsOnce(),
        )
    )

    /** Replaces everything with the contents of a backup. Throws [IllegalArgumentException] for unusable files. */
    suspend fun restoreBackup(text: String) {
        val backup = BackupCodec.decode(text)
        dao.allActives().forEach { scheduler.cancel(it.id) }
        db.withTransaction {
            dao.deleteAllVillages()
            dao.deleteAllLabels()
            backup.villages.forEach { dao.restoreVillage(it) }
            backup.pools.forEach { dao.restorePool(it) }
            backup.actives.forEach { dao.restoreActive(it) }
            backup.wishlist.forEach { dao.restoreWish(it) }
            backup.history.forEach { dao.restoreHistory(it) }
            backup.resources.forEach { dao.upsertResource(it) }
            backup.labels.forEach { dao.upsertLabel(it) }
        }
        rescheduleAll()
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private suspend fun learnLabel(a: ActiveUpgrade) {
        val dataId = dataIdOf(a.sourceKey) ?: return
        if (a.name.isBlank() || isPlaceholderName(a.name)) return
        dao.upsertLabel(LabelEntity(dataId, a.name.trim()))
    }

    private suspend fun spend(villageId: Long, resource: Resource?, amount: Long, nowMs: Long) {
        if (resource == null || amount <= 0L) return
        val current = dao.resourcesOnce(villageId).firstOrNull { it.resource == resource.name }
        val state = current?.toDomain() ?: ResourceState(resource, 0L, 0L, nowMs)
        val balance = state.amountAt(nowMs)
        dao.upsertResource(
            ResourceEntity(villageId, resource.name, (balance - amount).coerceAtLeast(0L), state.incomePerHour, nowMs)
        )
    }
}
