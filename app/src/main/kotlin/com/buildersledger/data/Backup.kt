package com.buildersledger.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BackupFile(
    val app: String = BackupCodec.APP_ID,
    val version: Int = BackupCodec.VERSION,
    val exportedAtMs: Long,
    val villages: List<VillageEntity>,
    val pools: List<PoolEntity>,
    val actives: List<ActiveEntity>,
    val wishlist: List<WishEntity>,
    val history: List<HistoryEntity>,
    val resources: List<ResourceEntity>,
    val labels: List<LabelEntity>,
)

object BackupCodec {
    const val APP_ID = "builders-ledger"
    const val VERSION = 1

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(file: BackupFile): String = json.encodeToString(BackupFile.serializer(), file)

    /** Throws [IllegalArgumentException] with a readable message when the text is not a usable backup. */
    fun decode(text: String): BackupFile {
        val file = try {
            json.decodeFromString(BackupFile.serializer(), text)
        } catch (e: Exception) {
            throw IllegalArgumentException("This file is not a Builder's Ledger backup.")
        }
        require(file.app == APP_ID) { "This file is not a Builder's Ledger backup." }
        require(file.version <= VERSION) { "This backup was made by a newer version of the app." }
        return file
    }
}
