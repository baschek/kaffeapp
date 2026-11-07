package de.baschek.kaffee.sync

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.baschek.kaffee.BuildConfig
import de.baschek.kaffee.KaffeeApplication
import de.baschek.kaffee.core.api.BaseUrl
import de.baschek.kaffee.core.api.InvalidBaseUrlException
import de.baschek.kaffee.core.api.KtorKaffeeApi
import de.baschek.kaffee.core.api.RequestSigner
import de.baschek.kaffee.core.api.SyncApiException
import de.baschek.kaffee.core.sync.SyncEngine
import de.baschek.kaffee.data.DeviceKeys
import de.baschek.kaffee.data.KaffeeRepository
import de.baschek.kaffee.data.SecureStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import java.util.UUID
import java.util.concurrent.TimeUnit

/** What the UI shows in the status strip. */
data class SyncStatus(
    val running: Boolean = false,
    val lastSuccessAt: Long? = null,
    val message: String? = null,
    /** The Pi no longer knows this device (revoked, replaced, expired): pair again. */
    val disconnected: Boolean = false,
)

enum class SyncOutcome { SUCCESS, NOT_PAIRED, DISCONNECTED, RETRY, ALREADY_RUNNING }

sealed interface PairResult {
    data object Success : PairResult
    data object InvalidUrl : PairResult
    data object Unavailable : PairResult
    data object Network : PairResult
    data object Other : PairResult
}

/** Runs syncs (one at a time), keeps the UI state and handles pairing (docs/sync-protocol.md, credential A). */
class SyncManager(
    context: Context,
    private val repository: KaffeeRepository,
    private val secure: SecureStore,
    private val httpClient: HttpClient,
    private val deviceKeys: DeviceKeys,
) {
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val api = KtorKaffeeApi(httpClient, { secure.baseUrl() }, { secure.token() },
        { secure.keyAlias()?.let(deviceKeys::load)?.let { RequestSigner(it) } })
    private val engine = SyncEngine(api, repository.syncStore)
    private val mutex = Mutex()

    private val _status = MutableStateFlow(SyncStatus(lastSuccessAt = prefs.getLong(LAST_SUCCESS, 0).takeIf { it > 0 },
        disconnected = prefs.getBoolean(DISCONNECTED, false)))
    val status: StateFlow<SyncStatus> = _status.asStateFlow()
    val paired: StateFlow<Boolean> get() = secure.paired

    suspend fun runSync(): SyncOutcome {
        if (!secure.paired.value) return SyncOutcome.NOT_PAIRED
        if (!mutex.tryLock()) return SyncOutcome.ALREADY_RUNNING
        _status.value = _status.value.copy(running = true)
        try {
            val result = engine.sync()
            val now = System.currentTimeMillis()
            prefs.edit().putLong(LAST_SUCCESS, now).putBoolean(DISCONNECTED, false).apply()
            val message = if (result.rejected.isNotEmpty()) "${result.rejected.size} Buchung(en) vom Pi abgelehnt" else "Mit TESS synchronisiert"
            _status.value = SyncStatus(lastSuccessAt = now, message = message)
            return SyncOutcome.SUCCESS
        } catch (e: CancellationException) {
            _status.value = _status.value.copy(running = false)
            throw e
        } catch (e: SyncApiException) {
            return when (e) {
                is SyncApiException.NotFound, is SyncApiException.Unauthorized -> {
                    prefs.edit().putBoolean(DISCONNECTED, true).apply()
                    _status.value = _status.value.copy(running = false, disconnected = true, message = "Verbindung getrennt – neu koppeln")
                    SyncOutcome.DISCONNECTED
                }
                is SyncApiException.Network -> fail("Offline – Änderungen bleiben gespeichert")
                is SyncApiException.RateLimited -> fail("Zu viele Anfragen, später erneut")
                else -> fail("Synchronisierung fehlgeschlagen (${e.message})")
            }
        } catch (e: Exception) {
            return fail("Synchronisierung fehlgeschlagen (${e::class.simpleName})")
        } finally {
            mutex.unlock()
        }
    }

    private fun fail(message: String): SyncOutcome {
        _status.value = _status.value.copy(running = false, message = message)
        return SyncOutcome.RETRY
    }

    /** Pairs this phone with the Pi: new Keystore key, one-time code from `./tessctl kaffee-pair`. */
    suspend fun pair(serverUrl: String, code: String): PairResult {
        val base = try {
            BaseUrl.normalize(serverUrl, allowInsecureLocal = BuildConfig.DEBUG)
        } catch (e: InvalidBaseUrlException) {
            return PairResult.InvalidUrl
        }
        val alias = "kaffee-device-${UUID.randomUUID()}"
        val key = deviceKeys.generate(alias)
        return try {
            val response = KtorKaffeeApi.pair(httpClient, base, code, Build.MODEL ?: "Android", key.publicKeySpkiBase64Url())
            secure.keyAlias()?.let(deviceKeys::delete)
            secure.save(base, response.token, alias)
            repository.resetCursor()
            prefs.edit().putBoolean(DISCONNECTED, false).apply()
            _status.value = SyncStatus(message = "Gekoppelt")
            PairResult.Success
        } catch (e: CancellationException) {
            deviceKeys.delete(alias); throw e
        } catch (e: SyncApiException) {
            deviceKeys.delete(alias)
            when (e) {
                is SyncApiException.PairingUnavailable -> PairResult.Unavailable
                is SyncApiException.Network -> PairResult.Network
                else -> PairResult.Other
            }
        }
    }

    fun unpair() {
        secure.keyAlias()?.let(deviceKeys::delete)
        secure.clear()
        _status.value = SyncStatus()
    }

    private companion object {
        const val LAST_SUCCESS = "last_success_at"
        const val DISCONNECTED = "disconnected"
    }
}

class SyncScheduler(private val context: Context) {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    private val workManager get() = WorkManager.getInstance(context)

    /** Periodic background sync, every 15 minutes (the WorkManager minimum), only with network. */
    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build()
        workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** One-off sync: app start, after every booking, manual button. */
    fun requestSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, request)
    }

    private companion object {
        const val PERIODIC = "kaffee-sync-periodic"
        const val ONCE = "kaffee-sync-once"
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as KaffeeApplication).container
        return when (container.syncManager.runSync()) {
            SyncOutcome.RETRY -> if (runAttemptCount >= 5) Result.success() else Result.retry()
            else -> Result.success()
        }
    }
}
