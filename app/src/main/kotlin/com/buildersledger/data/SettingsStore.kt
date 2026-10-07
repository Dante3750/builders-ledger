package com.buildersledger.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {
    private val currentVillageKey = longPreferencesKey("current_village_id")
    private val leadMinutesKey = intPreferencesKey("lead_minutes")
    private val patienceHoursKey = intPreferencesKey("patience_hours")
    private val onboardingDoneKey = booleanPreferencesKey("onboarding_done")

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
}
