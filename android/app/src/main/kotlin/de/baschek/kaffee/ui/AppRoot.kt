package de.baschek.kaffee.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.baschek.kaffee.core.domain.Actions
import de.baschek.kaffee.core.domain.Obj
import de.baschek.kaffee.ui.theme.Kaffee
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Screens. The first four are in the bottom navigation, the settings open with the gear in the header. */
enum class Tab(val label: String) {
    DASHBOARD("Übersicht"), JARS("Gläser"), COFFEE("Kaffees"), HISTORY("Verlauf"), SETTINGS("Einstellungen")
}

private sealed interface Overlay {
    data object Package : Overlay
    data class Allocate(val packageId: String?) : Overlay
    data object Pairing : Overlay
    data class Jar(val id: String) : Overlay
    data class Correction(val event: Obj) : Overlay
}

/** Confirmation after a booking with "Rückgängig" (which books counter-entries for exactly these events). */
private data class Toast(val text: String, val events: List<Obj>)

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
    val appearance by vm.appearance.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.DASHBOARD) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var query by remember { mutableStateOf("") }
    var toast by remember { mutableStateOf<Toast?>(null) }
    val inv = inventory
    val c = Kaffee.colors

    LaunchedEffect(toast) { if (toast != null) { delay(7000); toast = null } }

    val syncText = when {
        sync.running -> "Synchronisiere mit TESS …"
        !paired -> "Nicht gekoppelt – Buchungen bleiben lokal"
        sync.disconnected -> "Verbindung getrennt – neu koppeln"
        else -> (sync.message ?: "Bereit") + (sync.lastSuccessAt?.let { " · zuletzt ${clock.format(Instant.ofEpochMilli(it))}" } ?: "")
    }
    fun openSearch() {
        val id = query.trim().uppercase()
        if (inv?.state?.jar(id) != null) { overlay = Overlay.Jar(id); query = "" } else vm.say("Glas „$id“ wurde nicht gefunden.")
    }

    Scaffold(
        containerColor = c.surface100,
        bottomBar = {
            NavigationBar(containerColor = c.sidebar, contentColor = c.onSidebar) {
                Tab.entries.take(4).forEach { item ->
                    NavigationBarItem(selected = tab == item, onClick = { tab = item },
                        icon = { NavIcon(item, if (tab == item) c.accent else c.sidebarDim) },
                        label = { Text(item.label, style = Kaffee.type.label.copy(fontSize = 11.sp)) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = c.accent, selectedTextColor = c.accent,
                            unselectedIconColor = c.sidebarDim, unselectedTextColor = c.sidebarDim, indicatorColor = Color.Transparent))
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                // Header: brand, sync dot, gear; below it the glass search (type a number, Enter opens the glass).
                Column(Modifier.fillMaxWidth().background(c.surface100).statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Kaffee-Bestand", style = Kaffee.type.title.copy(fontSize = 19.sp), color = c.ink, modifier = Modifier.weight(1f))
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (paired && !sync.disconnected) c.success else c.inkMuted))
                        Text(if (paired && !sync.disconnected) "Verbunden" else "Lokal", style = Kaffee.type.label, color = c.inkMuted)
                        val gearShape = RoundedCornerShape(Kaffee.spacing.radiusMd)
                        Box(Modifier.size(48.dp).clip(gearShape).background(c.surface200).border(1.5.dp, if (tab == Tab.SETTINGS) c.brand else c.line, gearShape)
                            .clickable { tab = Tab.SETTINGS }.semantics { contentDescription = "Einstellungen" }, contentAlignment = Alignment.Center) {
                            NavIcon(Tab.SETTINGS, c.ink, 20.dp)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Glas suchen, z. B. S15") },
                            shape = RoundedCornerShape(Kaffee.spacing.radiusSm),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.brand, unfocusedBorderColor = c.line, focusedContainerColor = c.surface200,
                                unfocusedContainerColor = c.surface200, focusedTextColor = c.ink, unfocusedTextColor = c.ink, cursorColor = c.brand,
                                focusedPlaceholderColor = c.inkMuted, unfocusedPlaceholderColor = c.inkMuted),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { openSearch() }))
                        SecondaryButton("Suchen", Modifier.width(96.dp).height(56.dp), onClick = ::openSearch)
                    }
                }
                // Status strip: sync state, last message, and the sync or pairing action.
                HorizontalDivider(color = c.line, modifier = Modifier.padding(horizontal = 16.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text("$syncText${if (pending > 0) " · $pending ausstehend" else ""}", style = Kaffee.type.small, color = c.inkMuted)
                        message?.let { Text(it, style = Kaffee.type.small, color = c.ink) }
                    }
                    TextLink(if (paired) "Jetzt synchronisieren" else "Mit TESS koppeln") { if (paired) vm.syncNow() else overlay = Overlay.Pairing }
                }
                HorizontalDivider(color = c.line, modifier = Modifier.padding(horizontal = 16.dp))
                if (inv == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Kaffee-Bestand wird vorbereitet …", color = c.inkMuted) }
                    return@Column
                }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Kaffee.spacing.page)) {
                    if (notices.isNotEmpty()) {
                        Notice("Vom Pi abgelehnt", notices) { TextLink("Gelesen", vm::clearNotices) }
                    }
                    when (tab) {
                        Tab.DASHBOARD -> Dashboard(inv, onAdd = { overlay = Overlay.Package }, onAllocate = { overlay = Overlay.Allocate(null) },
                            onJar = { overlay = Overlay.Jar(it) }, onView = { tab = it })
                        Tab.JARS -> JarMatrix(inv, (overlay as? Overlay.Jar)?.id) { overlay = Overlay.Jar(it) }
                        Tab.COFFEE -> CoffeeView(inv, onAllocate = { overlay = Overlay.Allocate(it) }, onJar = { overlay = Overlay.Jar(it) })
                        Tab.HISTORY -> HistoryView(inv) { overlay = Overlay.Correction(it) }
                        Tab.SETTINGS -> SettingsView(inv, appearance, vm::setAppearance, paired, syncText, pending, onPair = { overlay = Overlay.Pairing }, onUnpair = vm::unpair,
                            onSync = vm::syncNow, onExportJson = { vm.export(csv = false) }, onExportCsv = { vm.export(csv = true) },
                            onCategory = { name, profile -> vm.append(Actions.defineCategory(name, profile)) }, onUpdate = vm::checkUpdate)
                    }
                }
            }
            toast?.let { current ->
                val shape = RoundedCornerShape(Kaffee.spacing.radiusMd)
                Row(Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth().clip(shape).background(Color(0xFF1A2429)).padding(start = 14.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(current.text, style = Kaffee.type.bodyStrong, color = Color(0xFFEAF1F2), modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        toast = null
                        vm.append(current.events.reversed().flatMap { Actions.correction(it, "Rückgängig") })
                    }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Rückgängig", color = Color(0xFFE5B15A), style = Kaffee.type.bodyStrong) }
                }
            }
        }
    }

    val close = { overlay = null }
    if (inv != null) when (val current = overlay) {
        Overlay.Package -> AddPackageSheet(close) { events -> vm.append(events) { if (it) overlay = null } }
        is Overlay.Allocate -> AllocateSheet(inv, current.packageId, close) { events -> vm.append(events) { if (it) { overlay = null; tab = Tab.JARS } } }
        Overlay.Pairing -> PairingSheet(close) { url, code, done -> vm.pair(url, code, done) }
        is Overlay.Jar -> JarDetailSheet(inv, current.id, close) { events, label -> vm.append(events) { if (it) { overlay = null; toast = Toast(label, events) } } }
        is Overlay.Correction -> CorrectionDialog(current.event, close) { events -> vm.append(events) { if (it) overlay = null } }
        null -> Unit
    }
    update?.let { info ->
        AlertDialog(onDismissRequest = vm::dismissUpdate, containerColor = c.surface100,
            title = { Text("Update ${info.versionName}", style = Kaffee.type.title) },
            text = { Text(info.notes ?: "Eine neue Version ist verfügbar.") },
            confirmButton = { TextButton(onClick = { vm.installUpdate(info) }) { Text("Installieren", color = c.brand) } },
            dismissButton = { TextButton(onClick = vm::dismissUpdate) { Text("Später", color = c.brand) } })
    }
}
