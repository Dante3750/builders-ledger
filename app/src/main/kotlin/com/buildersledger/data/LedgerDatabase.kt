package com.buildersledger.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface LedgerDao {
    // villages
    @Query("SELECT * FROM villages ORDER BY id")
    fun villages(): Flow<List<VillageEntity>>

    @Query("SELECT * FROM villages WHERE id = :id")
    suspend fun village(id: Long): VillageEntity?

    @Query("SELECT * FROM villages ORDER BY id")
    suspend fun villagesOnce(): List<VillageEntity>

    @Insert
    suspend fun insertVillage(v: VillageEntity): Long

    @Update
    suspend fun updateVillage(v: VillageEntity)

    @Query("DELETE FROM villages WHERE id = :id")
    suspend fun deleteVillage(id: Long)

    // pools
    @Query("SELECT * FROM pools WHERE villageId = :villageId ORDER BY sortOrder, id")
    fun pools(villageId: Long): Flow<List<PoolEntity>>

    @Query("SELECT * FROM pools WHERE villageId = :villageId ORDER BY sortOrder, id")
    suspend fun poolsOnce(villageId: Long): List<PoolEntity>

    @Query("SELECT * FROM pools ORDER BY id")
    suspend fun allPools(): List<PoolEntity>

    @Insert
    suspend fun insertPool(p: PoolEntity): Long

    @Update
    suspend fun updatePool(p: PoolEntity)

    @Query("DELETE FROM pools WHERE id = :id")
    suspend fun deletePool(id: Long)

    // running upgrades
    @Query("SELECT * FROM actives WHERE villageId = :villageId ORDER BY endsAtMs")
    fun actives(villageId: Long): Flow<List<ActiveEntity>>

    @Query("SELECT * FROM actives WHERE villageId = :villageId ORDER BY endsAtMs")
    suspend fun activesOnce(villageId: Long): List<ActiveEntity>

    @Query("SELECT * FROM actives WHERE id = :id")
    suspend fun active(id: Long): ActiveEntity?

    @Query("SELECT * FROM actives WHERE endsAtMs > :nowMs")
    suspend fun activesEndingAfter(nowMs: Long): List<ActiveEntity>

    @Query("SELECT * FROM actives ORDER BY id")
    suspend fun allActives(): List<ActiveEntity>

    @Insert
    suspend fun insertActive(a: ActiveEntity): Long

    @Update
    suspend fun updateActive(a: ActiveEntity)

    @Query("DELETE FROM actives WHERE id = :id")
    suspend fun deleteActive(id: Long)

    // wishlist
    @Query("SELECT * FROM wishlist WHERE villageId = :villageId ORDER BY priority DESC, id")
    fun wishlist(villageId: Long): Flow<List<WishEntity>>

    @Query("SELECT * FROM wishlist ORDER BY id")
    suspend fun allWishlist(): List<WishEntity>

    @Insert
    suspend fun insertWish(w: WishEntity): Long

    @Update
    suspend fun updateWish(w: WishEntity)

    @Query("DELETE FROM wishlist WHERE id = :id")
    suspend fun deleteWish(id: Long)

    // history
    @Query("SELECT * FROM history WHERE villageId = :villageId ORDER BY endedAtMs DESC")
    fun history(villageId: Long): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history ORDER BY id")
    suspend fun allHistory(): List<HistoryEntity>

    @Insert
    suspend fun insertHistory(h: HistoryEntity): Long

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteHistory(id: Long)

    // resources
    @Query("SELECT * FROM resources WHERE villageId = :villageId")
    fun resources(villageId: Long): Flow<List<ResourceEntity>>

    @Query("SELECT * FROM resources WHERE villageId = :villageId")
    suspend fun resourcesOnce(villageId: Long): List<ResourceEntity>

    @Query("SELECT * FROM resources")
    suspend fun allResources(): List<ResourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertResource(r: ResourceEntity)

    // labels
    @Query("SELECT * FROM labels")
    fun labels(): Flow<List<LabelEntity>>

    @Query("SELECT * FROM labels")
    suspend fun labelsOnce(): List<LabelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLabel(l: LabelEntity)

    // restore support (explicit ids are kept so foreign keys line up)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreVillage(v: VillageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restorePool(p: PoolEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreActive(a: ActiveEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreWish(w: WishEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreHistory(h: HistoryEntity)

    @Query("DELETE FROM villages")
    suspend fun deleteAllVillages()

    @Query("DELETE FROM labels")
    suspend fun deleteAllLabels()
}

@Database(
    entities = [
        VillageEntity::class,
        PoolEntity::class,
        ActiveEntity::class,
        WishEntity::class,
        HistoryEntity::class,
        ResourceEntity::class,
        LabelEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun dao(): LedgerDao

    companion object {
        /** v1 -> v2: optional storage cap per resource and optional finish-by deadline per wishlist item. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE resources ADD COLUMN capacity INTEGER")
                db.execSQL("ALTER TABLE wishlist ADD COLUMN finishByMs INTEGER")
            }
        }

        fun create(context: Context): LedgerDatabase =
            Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, "ledger.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
