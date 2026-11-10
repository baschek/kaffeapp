package de.baschek.kaffee

import android.app.Application
import androidx.room.Room
import de.baschek.kaffee.core.api.createHttpClient
import de.baschek.kaffee.data.AppearanceStore
import de.baschek.kaffee.data.DeviceKeys
import de.baschek.kaffee.data.KaffeeRepository
import de.baschek.kaffee.data.SecureStore
import de.baschek.kaffee.data.db.KaffeeDatabase
import de.baschek.kaffee.sync.SyncManager
import de.baschek.kaffee.sync.SyncScheduler
import de.baschek.kaffee.update.AppUpdater
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hand-wired dependencies (no DI framework needed at this size). */
class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val database = Room.databaseBuilder(app, KaffeeDatabase::class.java, "kaffee.db").build()
    val scheduler = SyncScheduler(app)
    val repository = KaffeeRepository(database.dao(), appScope) { scheduler.requestSync() }
    val secureStore = SecureStore(app)
    val appearance = AppearanceStore(app)
    private val httpClient = createHttpClient(OkHttp.create())
    val syncManager = SyncManager(app, repository, secureStore, httpClient, DeviceKeys())
    val updater = AppUpdater(app, httpClient)
}

class KaffeeApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.scheduler.schedulePeriodic()
        // Sync on app start; the worker does nothing while the app is not paired.
        container.scheduler.requestSync()
    }
}
