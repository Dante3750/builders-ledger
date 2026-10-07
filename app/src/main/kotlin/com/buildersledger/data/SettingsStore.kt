package com.buildersledger.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {
    private val currentVillageKey = longPreferencesKey("current_village_id")
    private val leadMinutesKey = intPreferencesKey("lead_minutes")
    private val patienceHoursKey = intPreferencesKey("patience_hours")
    private val onboardingDoneKey = booleanPreferencesKey("onboarding_done")

    private val apiTagKey = stringPreferencesKey("api_player_tag")
    private val apiKeyKey = stringPreferencesKey("api_key")
    private val apiBaseUrlKey = stringPreferencesKey("api_base_url")
    private val progressSyncedAtKey = longPreferencesKey("progress_synced_at")

    private val data: Flow<Preferences> get() = context.settingsDataStore.data

    /** Null until the player picks or creates a village. */
    val currentVillageId: Flow<Long?> = data.map { it[currentVillageKey] }

    /** Extra reminder this many minutes before an upgrade finishes. 0 = only when it finishes. */
    val leadMinutes: Flow<Int> = data.map { it[leadMinutesKey] ?: 0 }

    /** How long the planner lets a worker wait for resources before it fills the gap with something cheaper. */
    val patienceHours: Flow<Int> = data.map { it[patienceHoursKey] ?: 1 }

    /** True once the player dismissed the first-run "Sync from export" card. Null while loading. */
    val onboardingDone: Flow<Boolean> = data.map { it[onboardingDoneKey] ?: false }

    suspend fun setOnboardingDone() {
        context.settingsDataStore.edit { it[onboardingDoneKey] = true }
    }

    suspend fun setCurrentVillage(id: Long) {
        context.settingsDataStore.edit { it[currentVillageKey] = id }
    }

    suspend fun setLeadMinutes(minutes: Int) {
        context.settingsDataStore.edit { it[leadMinutesKey] = minutes }
    }

    suspend fun setPatienceHours(hours: Int) {
        context.settingsDataStore.edit { it[patienceHoursKey] = hours }
    }

    // ---- optional official-API sync. The key is stored on the device in plain DataStore (app-private, not encrypted). ----

    val apiTag: Flow<String> = data.map { it[apiTagKey].orEmpty() }
    val apiKey: Flow<String> = data.map { it[apiKeyKey].orEmpty() }
    val apiBaseUrl: Flow<String> = data.map { it[apiBaseUrlKey].orEmpty() }
    val progressSyncedAt: Flow<Long?> = data.map { it[progressSyncedAtKey] }

    suspend fun setApiSettings(tag: String, key: String, baseUrl: String) {
        context.settingsDataStore.edit {
            it[apiTagKey] = tag
            it[apiKeyKey] = key
            it[apiBaseUrlKey] = baseUrl
        }
    }

    suspend fun setProgressSyncedAt(ms: Long) {
        context.settingsDataStore.edit { it[progressSyncedAtKey] = ms }
    }
}
