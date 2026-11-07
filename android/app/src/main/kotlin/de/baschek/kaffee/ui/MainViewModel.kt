package de.baschek.kaffee.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.baschek.kaffee.KaffeeApplication
import de.baschek.kaffee.core.domain.CsvExport
import de.baschek.kaffee.core.domain.DomainError
import de.baschek.kaffee.core.domain.Obj
import de.baschek.kaffee.core.domain.toJsonElement
import de.baschek.kaffee.core.update.UpdateInfo
import de.baschek.kaffee.sync.PairResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as KaffeeApplication).container
    val inventory = container.repository.inventory
    val syncStatus = container.syncManager.status
    val paired: StateFlow<Boolean> = container.syncManager.paired
    val pendingCount: StateFlow<Int> = container.repository.pendingCount.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val notices: StateFlow<List<String>> = container.repository.notices.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _message = MutableStateFlow<String?>(null)
    /** Short feedback for the status strip (errors of a booking, export results). */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _update = MutableStateFlow<UpdateInfo?>(null)
    val update: StateFlow<UpdateInfo?> = _update.asStateFlow()

    /** Books events; returns true when stored. A booking that does not fit the stock is refused with its reason. */
    fun append(events: List<Obj>, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = try {
                container.repository.append(events)
                _message.value = "Gespeichert – wird mit TESS synchronisiert"
                true
            } catch (e: DomainError) {
                _message.value = "Nicht gebucht: ${e.message}"
                false
            }
            onDone(ok)
        }
    }

    fun say(text: String) { _message.value = text }

    fun syncNow() {
        viewModelScope.launch { container.syncManager.runSync() }
    }

    fun clearNotices() { viewModelScope.launch { container.repository.clearNotices() } }

    fun pair(url: String, code: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val result = container.syncManager.pair(url, code)
            onResult(when (result) {
                PairResult.Success -> null
                PairResult.InvalidUrl -> "Bitte eine gültige https-Adresse eingeben."
                PairResult.Unavailable -> "Kopplung nicht möglich: Code falsch oder abgelaufen, oder ein anderes Gerät ist noch gekoppelt (./tessctl kaffee-pair --replace)."
                PairResult.Network -> "Der Pi ist nicht erreichbar."
                PairResult.Other -> "Kopplung fehlgeschlagen."
            })
            if (result == PairResult.Success) container.syncManager.runSync()
        }
    }

    fun unpair() = container.syncManager.unpair()

    /** Shares the full journal (JSON) or the three CSV files through the Android share sheet. */
    fun export(csv: Boolean) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val document = container.repository.exportDocument()
            val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss").withZone(ZoneOffset.UTC).format(Instant.now())
            val files = withContext(Dispatchers.IO) {
                val dir = File(app.cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                if (csv) CsvExport.files(document).map { (name, text) -> File(dir, "$name-$stamp.csv").apply { writeText(text) } }
                else listOf(File(dir, "kaffee-bestand-$stamp.json").apply { writeText(prettyJson.encodeToString(JsonElement.serializer(), document.toJsonElement())) })
            }
            val uris = ArrayList(files.map { FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", it) })
            val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                type = if (csv) "text/csv" else "application/json"
                if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            app.startActivity(Intent.createChooser(intent, "Export teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun checkUpdate() {
        viewModelScope.launch {
            _update.value = runCatching { container.updater.checkForUpdate() }.getOrNull()
            if (_update.value == null) _message.value = "Die App ist aktuell."
        }
    }

    fun dismissUpdate() { _update.value = null }

    fun installUpdate(info: UpdateInfo) {
        viewModelScope.launch {
            runCatching { container.updater.install(container.updater.download(info)) }
                .onFailure { _message.value = "Update fehlgeschlagen: ${it.message}" }
        }
    }

    private companion object {
        val prettyJson = Json { prettyPrint = true }
    }
}
