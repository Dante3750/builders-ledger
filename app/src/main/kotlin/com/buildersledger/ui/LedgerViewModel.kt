package com.buildersledger.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.buildersledger.LedgerApplication
import com.buildersledger.data.ImportSummary
import com.buildersledger.data.CachedProgress
import com.buildersledger.data.LedgerRepository
import com.buildersledger.data.ProgressRepository
import com.buildersledger.data.SettingsStore
import com.buildersledger.domain.ActiveUpgrade
import com.buildersledger.domain.ApiError
import com.buildersledger.domain.ApiException
import com.buildersledger.domain.PlayerApi
import com.buildersledger.domain.CompletedUpgrade
import com.buildersledger.domain.Resource
import com.buildersledger.domain.ResourceState
import com.buildersledger.domain.Village
import com.buildersledger.domain.VillageImportResult
import com.buildersledger.domain.WishlistItem
import com.buildersledger.domain.WorkerPool
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LedgerViewModel(
    private val repo: LedgerRepository,
    private val settings: SettingsStore,
    private val progressRepo: ProgressRepository,
) : ViewModel() {

    init {
        viewModelScope.launch { progressRepo.loadCache() }
    }

    /** Null while the database is still loading, so the UI can tell "loading" from "no villages yet". */
    val villages: StateFlow<List<Village>?> = repo.villages()
        .map<List<Village>, List<Village>?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val currentVillage: StateFlow<Village?> = combine(villages, settings.currentVillageId) { list, id ->
        list?.firstOrNull { it.id == id } ?: list?.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> perVillage(empty: T, source: (Long) -> Flow<T>): StateFlow<T> =
        currentVillage
            .map { it?.id }
            .distinctUntilChanged()
            .flatMapLatest { id -> if (id == null) flowOf(empty) else source(id) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, empty)

    val pools: StateFlow<List<WorkerPool>> = perVillage<List<WorkerPool>>(emptyList()) { repo.pools(it) }
    val active: StateFlow<List<ActiveUpgrade>> = perVillage<List<ActiveUpgrade>>(emptyList()) { repo.active(it) }
    val wishlist: StateFlow<List<WishlistItem>> = perVillage<List<WishlistItem>>(emptyList()) { repo.wishlist(it) }
    val history: StateFlow<List<CompletedUpgrade>> = perVillage<List<CompletedUpgrade>>(emptyList()) { repo.history(it) }
    val resources: StateFlow<Map<Resource, ResourceState>> =
        perVillage<Map<Resource, ResourceState>>(emptyMap()) { repo.resources(it) }

    val labels: StateFlow<Map<Long, String>> = repo.labels().stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val leadMinutes: StateFlow<Int> = settings.leadMinutes.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    /** Null until DataStore has answered, so the onboarding card never flashes for returning players. */
    val onboardingDone: StateFlow<Boolean?> = settings.onboardingDone
        .map<Boolean, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val patienceHours: StateFlow<Int> = settings.patienceHours.stateIn(viewModelScope, SharingStarted.Eagerly, 1)

    // ---- villages ----
    fun selectVillage(id: Long) {
        viewModelScope.launch { settings.setCurrentVillage(id) }
    }

    fun createVillage(name: String, townHall: Int, builders: Int) {
        viewModelScope.launch {
            val id = repo.createVillage(name, townHall, builders)
            settings.setCurrentVillage(id)
        }
    }

    fun updateVillage(village: Village) {
        viewModelScope.launch { repo.updateVillage(village) }
    }

    fun deleteVillage(id: Long) {
        viewModelScope.launch { repo.deleteVillage(id) }
    }

    // ---- pools ----
    fun savePool(pool: WorkerPool) {
        viewModelScope.launch { repo.savePool(pool) }
    }

    fun deletePool(id: Long) {
        viewModelScope.launch { repo.deletePool(id) }
    }

    // ---- running upgrades ----
    fun saveActive(upgrade: ActiveUpgrade, payFromBalance: Boolean) {
        viewModelScope.launch { repo.saveActive(upgrade, payFromBalance) }
    }

    fun finishActive(upgrade: ActiveUpgrade, cancelled: Boolean) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            repo.finishActive(upgrade, if (cancelled) now else minOf(upgrade.endsAtMs, now), cancelled)
        }
    }

    fun collectAllDone() {
        val village = currentVillage.value ?: return
        viewModelScope.launch { repo.collectAllDone(village.id, System.currentTimeMillis()) }
    }

    // ---- wishlist ----
    fun saveWish(item: WishlistItem) {
        viewModelScope.launch { repo.saveWish(item) }
    }

    fun deleteWish(id: Long) {
        viewModelScope.launch { repo.deleteWish(id) }
    }

    fun startFromWishlist(item: WishlistItem) {
        viewModelScope.launch { repo.startFromWishlist(item, System.currentTimeMillis()) }
    }

    // ---- history, resources, labels ----
    fun deleteHistory(id: Long) {
        viewModelScope.launch { repo.deleteHistory(id) }
    }

    fun saveResources(states: List<ResourceState>) {
        val village = currentVillage.value ?: return
        viewModelScope.launch { repo.saveResources(village.id, states) }
    }

    fun setLabel(dataId: Long, name: String) {
        viewModelScope.launch { repo.setLabel(dataId, name) }
    }

    fun dismissOnboarding() {
        viewModelScope.launch { settings.setOnboardingDone() }
    }

    // ---- settings ----
    fun setLeadMinutes(minutes: Int) {
        viewModelScope.launch {
            settings.setLeadMinutes(minutes)
            repo.rescheduleAll()
        }
    }

    fun setPatienceHours(hours: Int) {
        viewModelScope.launch { settings.setPatienceHours(hours) }
    }

    // ---- optional official-API progress ----
    val apiTag: StateFlow<String> = settings.apiTag.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val apiKey: StateFlow<String> = settings.apiKey.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val apiBaseUrl: StateFlow<String> = settings.apiBaseUrl.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val progress: StateFlow<CachedProgress?> = progressRepo.cached

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing
    private val _syncError = MutableStateFlow<ApiError?>(null)
    val syncError: StateFlow<ApiError?> = _syncError

    /** Returns an error message, or null when the settings were saved. */
    fun saveApiSettings(tag: String, key: String, baseUrl: String): String? {
        val cleanTag = tag.trim()
        val normalized = if (cleanTag.isEmpty()) "" else PlayerApi.normalizeTag(cleanTag) ?: return ApiError.BadTag.message
        val cleanBase = baseUrl.trim()
        if (cleanBase.isNotEmpty() && PlayerApi.normalizeBaseUrl(cleanBase) == null) return ApiError.BadBaseUrl.message
        viewModelScope.launch { settings.setApiSettings(normalized, key.trim(), cleanBase) }
        return null
    }

    fun syncProgress() {
        if (_syncing.value) return
        _syncing.value = true
        _syncError.value = null
        viewModelScope.launch {
            val result = progressRepo.sync(currentVillage.value?.id)
            _syncError.value = (result.exceptionOrNull() as? ApiException)?.error
            _syncing.value = false
        }
    }

    // ---- import / backup ----
    fun applyImport(result: VillageImportResult, names: Map<Long, String>, onDone: (Result<ImportSummary>) -> Unit) {
        val village = currentVillage.value ?: return
        viewModelScope.launch {
            val outcome = runCatching { repo.applyImport(village.id, result, names, System.currentTimeMillis()) }
            onDone(outcome)
        }
    }

    fun exportBackup(onReady: (String) -> Unit) {
        viewModelScope.launch { onReady(repo.exportBackup()) }
    }

    fun restoreBackup(text: String, onDone: (Result<Unit>) -> Unit) {
        viewModelScope.launch { onDone(runCatching { repo.restoreBackup(text) }) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LedgerApplication
                LedgerViewModel(app.container.repository, app.container.settings, app.container.progress)
            }
        }
    }
}
