package com.buildersledger.data

import android.content.Context
import com.buildersledger.domain.ApiError
import com.buildersledger.domain.ApiException
import com.buildersledger.domain.PlayerApi
import com.buildersledger.domain.PlayerProgress
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class CachedProgress(val progress: PlayerProgress, val syncedAtMs: Long)

/**
 * Optional sync from the official player endpoint. The last response is kept as a JSON file and re-parsed on start,
 * so everything keeps working offline. Nothing here is required by the rest of the app.
 */
class ProgressRepository(
    context: Context,
    private val settings: SettingsStore,
    private val ledger: LedgerRepository,
    private val client: PlayerApiClient = PlayerApiClient(),
) {
    private val file = File(context.filesDir, "player_progress.json")
    private val _cached = MutableStateFlow<CachedProgress?>(null)
    val cached: StateFlow<CachedProgress?> = _cached

    suspend fun loadCache() = withContext(Dispatchers.IO) {
        val parsed = runCatching {
            if (!file.exists()) null else PlayerApi.parse(file.readText())
        }.getOrNull() ?: return@withContext
        _cached.value = CachedProgress(parsed, settings.progressSyncedAt.first() ?: file.lastModified())
    }

    /** Fetches, caches, and updates the current village's town hall. Failures leave the cached data untouched. */
    suspend fun sync(currentVillageId: Long?): Result<CachedProgress> {
        return try {
            val key = settings.apiKey.first().trim()
            if (key.isEmpty()) throw ApiException(ApiError.NoKey)
            if (PlayerApi.normalizeTag(settings.apiTag.first()) == null) throw ApiException(ApiError.BadTag)
            val base = settings.apiBaseUrl.first().ifBlank { PlayerApi.DEFAULT_BASE_URL }
            val url = PlayerApi.buildUrl(base, settings.apiTag.first()) ?: throw ApiException(ApiError.BadBaseUrl)

            val body = client.get(url, key)
            val progress = PlayerApi.parse(body)
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) { file.writeText(body) }
            settings.setProgressSyncedAt(now)

            progress.townHall?.let { th ->
                val villages = ledger.villages().first()
                val village = villages.firstOrNull { it.id == currentVillageId } ?: villages.firstOrNull()
                val clamped = th.coerceIn(1, 30)
                if (village != null && village.townHall != clamped) ledger.updateVillage(village.copy(townHall = clamped))
            }
            val result = CachedProgress(progress, now)
            _cached.value = result
            Result.success(result)
        } catch (e: ApiException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(ApiException(ApiError.Offline))
        }
    }
}
