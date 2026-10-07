package com.buildersledger

import android.app.Application
import android.content.Context
import com.buildersledger.data.LedgerDatabase
import com.buildersledger.data.LedgerRepository
import com.buildersledger.data.ProgressRepository
import com.buildersledger.data.SettingsStore
import com.buildersledger.notify.UpgradeAlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Hand-rolled dependency container: small enough that a DI framework would only add build risk. */
class AppContainer(context: Context) {
    val settings = SettingsStore(context)
    val scheduler = UpgradeAlarmScheduler(context).also { it.ensureChannels() }
    private val database = LedgerDatabase.create(context)
    val repository = LedgerRepository(database, scheduler, settings)
    val progress = ProgressRepository(context.applicationContext, settings, repository)
}

class LedgerApplication : Application() {
    /** Lives as long as the process; used for work that must outlive any screen (alarm re-registration). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Alarms can be lost (force stop, restore); re-registering on every start is cheap and keeps them honest.
        appScope.launch { container.repository.rescheduleAll() }
    }
}
