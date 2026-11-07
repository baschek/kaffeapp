package de.baschek.kaffee.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.baschek.kaffee.core.domain.Actions
import de.baschek.kaffee.core.domain.Obj
import de.baschek.kaffee.ui.theme.Kaffee
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class Tab(val label: String, val icon: String) {
    DASHBOARD("Übersicht", "⌂"), JARS("Gläser", "▦"), COFFEE("Kaffees", "◉"), HISTORY("Verlauf", "↗"), DATA("Daten", "⋯")
}

private sealed interface Overlay {
    data object Package : Overlay
    data object Allocate : Overlay
    data object Pairing : Overlay
    data class Jar(val id: String) : Overlay
    data class Correction(val event: Obj) : Overlay
}

private val clock = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault())

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val inventory by vm.inventory.collectAsState()
    val sync by vm.syncStatus.collectAsState()
    val paired by vm.paired.collectAsState()
    val pending by vm.pendingCount.collectAsState()
    val notices by vm.notices.collectAsState()
    val message by vm.message.collectAsState()
    val update by vm.update.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.DASHBOARD) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var query by remember { mutableStateOf("") }
    val inv = inventory

    val syncText = when {
        sync.running -> "Synchronisiere mit TESS …"
        !paired -> "Nicht gekoppelt – Buchungen bleiben lokal"
        sync.disconnected -> "Verbindung getrennt – neu koppeln"
        else -> (sync.message ?: "Bereit") + (sync.lastSuccessAt?.let { " · zuletzt ${clock.format(Instant.ofEpochMilli(it))}" } ?: "")
    }

    Scaffold(
        containerColor = Kaffee.colors.background,
        bottomBar = {
            NavigationBar(containerColor = Kaffee.colors.brown) {
                Tab.entries.forEach { item ->
                    NavigationBarItem(selected = tab == item, onClick = { tab = item },
                        icon = { Text(item.icon) }, label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Kaffee.colors.navActive, selectedTextColor = Kaffee.colors.navActive,
                            unselectedIconColor = Kaffee.colors.navInactive, unselectedTextColor = Kaffee.colors.navInactive,
                            indicatorColor = Kaffee.colors.brownHover))
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Top bar: brand, jar search, sync.
            Column(Modifier.fillMaxWidth().background(Kaffee.colors.cream).statusBarsPadding().padding(horizontal = 15.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Kaffee-Bestand", style = Kaffee.type.title, color = Kaffee.colors.text, modifier = Modifier.weight(1f))
                    TextButton(onClick = { if (paired) vm.syncNow() else overlay = Overlay.Pairing }) {
                        Text(if (paired) "Jetzt synchronisieren" else "Mit TESS koppeln", color = Kaffee.colors.link, fontWeight = FontWeight.Bold)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Glas suchen, z. B. S15") })
                    TextButton(onClick = {
                        val id = query.trim().uppercase()
                        if (inv?.state?.jar(id) != null) { overlay = Overlay.Jar(id); query = "" } else vm.say("Glas „$id“ wurde nicht gefunden.")
                    }) { Text("Suchen", color = Kaffee.colors.link) }
                }
            }
            // Status strip.
            Column(Modifier.fillMaxWidth().background(Kaffee.colors.strip).padding(horizontal = 15.dp, vertical = 7.dp)) {
                Text("$syncText${if (pending > 0) " · $pending ausstehend" else ""}", style = Kaffee.type.small, color = Kaffee.colors.muted)
                message?.let { Text(it, style = Kaffee.type.small, color = Kaffee.colors.text) }
            }
            if (inv == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Kaffee-Bestand wird vorbereitet …", color = Kaffee.colors.muted) }
                return@Column
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Kaffee.spacing.page)) {
                if (notices.isNotEmpty()) {
                    Notice("Vom Pi abgelehnt", notices) { TextButton(onClick = vm::clearNotices) { Text("Gelesen", color = Kaffee.colors.link) } }
                }
                when (tab) {
                    Tab.DASHBOARD -> Dashboard(inv, onAdd = { overlay = Overlay.Package }, onAllocate = { overlay = Overlay.Allocate },
                        onJar = { overlay = Overlay.Jar(it) }, onView = { tab = it })
                    Tab.JARS -> JarMatrix(inv, (overlay as? Overlay.Jar)?.id) { overlay = Overlay.Jar(it) }
                    Tab.COFFEE -> CoffeeView(inv, onAllocate = { overlay = Overlay.Allocate }, onJar = { overlay = Overlay.Jar(it) })
                    Tab.HISTORY -> HistoryView(inv) { overlay = Overlay.Correction(it) }
                    Tab.DATA -> DataView(inv, paired, syncText, pending, onPair = { overlay = Overlay.Pairing }, onUnpair = vm::unpair,
                        onSync = vm::syncNow, onExportJson = { vm.export(csv = false) }, onExportCsv = { vm.export(csv = true) },
                        onCategory = { name, profile -> vm.append(Actions.defineCategory(name, profile)) }, onUpdate = vm::checkUpdate)
                }
            }
        }
    }

    val close = { overlay = null }
    if (inv != null) when (val current = overlay) {
        Overlay.Package -> AddPackageSheet(close) { events -> vm.append(events) { if (it) overlay = null } }
        Overlay.Allocate -> AllocateSheet(inv, close) { events -> vm.append(events) { if (it) { overlay = null; tab = Tab.JARS } } }
        Overlay.Pairing -> PairingSheet(close) { url, code, done -> vm.pair(url, code, done) }
        is Overlay.Jar -> JarDetailSheet(inv, current.id, close) { events -> vm.append(events) }
        is Overlay.Correction -> CorrectionDialog(current.event, close) { events -> vm.append(events) { if (it) overlay = null } }
        null -> Unit
    }
    update?.let { info ->
        AlertDialog(onDismissRequest = vm::dismissUpdate, containerColor = Kaffee.colors.cream,
            title = { Text("Update ${info.versionName}") },
            text = { Text(info.notes ?: "Eine neue Version ist verfügbar.") },
            confirmButton = { TextButton(onClick = { vm.installUpdate(info) }) { Text("Installieren") } },
            dismissButton = { TextButton(onClick = vm::dismissUpdate) { Text("Später") } })
    }
}
