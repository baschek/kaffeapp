package de.baschek.kaffee.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.baschek.kaffee.core.domain.Obj
import de.baschek.kaffee.core.domain.formatPortions
import de.baschek.kaffee.core.domain.jsRound
import de.baschek.kaffee.core.domain.objs
import de.baschek.kaffee.data.Inventory
import de.baschek.kaffee.ui.theme.Appearance
import de.baschek.kaffee.ui.theme.Kaffee
import de.baschek.kaffee.ui.theme.LocalTileStyle
import de.baschek.kaffee.ui.theme.PaletteId
import de.baschek.kaffee.ui.theme.Palettes
import de.baschek.kaffee.ui.theme.ThemeMode
import de.baschek.kaffee.ui.theme.TileStyle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private fun categoryName(settings: Obj, id: Any?): String =
    settings.objs("categories").firstOrNull { it["id"] == id }?.get("name") as? String ?: "Eigene Kategorie"

fun lotGrams(inv: Inventory, lot: Obj): Double =
    jsRound(inv.state.allJars().filter { it["lotId"] == lot["id"] }.sumOf { it["grams"] as Double } + (lot["unallocatedGrams"] as Double))

fun portion(lot: Obj): Double = jsRound((lot["baseGrams"] as Double) + (lot["allowanceGrams"] as Double))

private fun methodWord(lot: Obj) = if (lot["brewMethod"] == "espresso") "Espresso" else "Filter"

// ---------------------------------------------------------------- Übersicht

@Composable
fun Dashboard(inv: Inventory, onAdd: () -> Unit, onAllocate: () -> Unit, onJar: (String) -> Unit, onView: (Tab) -> Unit) {
    val jars = inv.state.allJars()
    fun free(size: String) = jars.count { it["size"] == size && it["status"] == "free" }
    val cleaning = jars.count { it["status"] == "cleaning" }
    val frozen = jars.count { it["status"] == "frozen" }
    val activeJars = jars.filter { it["status"] == "active" }
    val packages = inv.state.allPackages()
    val unopened = packages.filter { it["openedAt"] == null && it["archived"] != true }
    val c = Kaffee.colors
    PageHeading("Bestand heute", "Übersicht")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton("Kaffee öffnen & aufteilen", Modifier.weight(1f), onClick = onAllocate)
        PrimaryButton("Neue Packung", Modifier.weight(1f), onClick = onAdd)
    }
    Spacer(Modifier.height(Kaffee.spacing.gap))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetricTile("Tiefgekühlt", frozen, "${activeJars.size} aktiv aufgetaut", "frozen", Modifier.weight(1f))
        MetricTile("Frei", free("S") + free("M") + free("L"), "${free("S")} S · ${free("M")} M · ${free("L")} L", "free", Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetricTile("Ungeöffnet", unopened.size, "${unopened.count { it["location"] == "frozen" }} komplett eingefroren", null, Modifier.weight(1f))
        MetricTile("Zu reinigen", cleaning, if (cleaning > 0) "warten auf Reinigung" else "Alle Gläser einsatzbereit", "cleaning", Modifier.weight(1f))
    }
    Spacer(Modifier.height(Kaffee.spacing.gap))
    Panel {
        PanelHead("Gefrierfach", "Aktive Gläser", "Matrix öffnen →") { onView(Tab.JARS) }
        if (activeJars.isEmpty()) Empty("Kein Glas ist aktiv", "Ein tiefgekühltes Glas öffnest du über das Glas-Detail.")
        activeJars.forEachIndexed { index, jar ->
            val lot = inv.state.lot(jar["lotId"] as? String)
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onJar(jar["id"] as String) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (lot != null) MethodBadge(lot["brewMethod"] as String)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("${jar["id"]}${if (lot != null) " · ${lot["name"]}" else ""}", style = Kaffee.type.bodyStrong, color = c.ink)
                    Text("${if (lot != null) "${methodWord(lot)} · " else ""}aufgetaut", style = Kaffee.type.small, color = c.inkMuted)
                }
                Text(grams(jar["grams"] as Double), style = Kaffee.type.label, color = c.ink)
            }
            if (index < activeJars.lastIndex) HorizontalDivider(color = c.line)
        }
    }
    if (inv.state.overrides.isNotEmpty()) {
        val n = inv.state.overrides.size
        Notice("$n dokumentierte Regelabweichung${if (n == 1) "" else "en"}", listOf("Im Verlauf bleiben Begründung und betroffene Charge dauerhaft sichtbar."))
    }
}

// ---------------------------------------------------------------- Gläser

@Composable
fun JarMatrix(inv: Inventory, selected: String?, onSelect: (String) -> Unit) {
    PageHeading("S01 bis L12", "Gläser", "Ein Tipp zeigt Inhalt, Herkunft und die nächsten möglichen Aktionen.")
    Legend()
    for (size in listOf("S", "M", "L")) {
        Panel(Modifier.padding(bottom = Kaffee.spacing.gap)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(Kaffee.spacing.radiusMd)).background(Kaffee.colors.ink), contentAlignment = Alignment.Center) {
                    Text(size, style = Kaffee.type.title, color = Kaffee.colors.surface200)
                }
                Column(Modifier.padding(start = 10.dp)) {
                    Text(when (size) { "S" -> "Einzelportionen"; "M" -> "Mehrportionen"; else -> "Großgläser" }, style = Kaffee.type.bodyStrong, color = Kaffee.colors.ink)
                    Text(when (size) { "S" -> "53 ml · 48 Gläser"; "M" -> "212 ml · 36 Gläser"; else -> "555 ml · 12 Gläser" }, style = Kaffee.type.small, color = Kaffee.colors.inkMuted)
                }
            }
            TileGrid(inv, inv.state.allJars().filter { it["size"] == size }, selected, columns = 4, onSelect)
        }
    }
}

/** Glass tiles in rows of [columns]; the last row is padded with empty space so all tiles keep one width. */
@Composable
fun TileGrid(inv: Inventory, jars: List<Obj>, selected: String?, columns: Int, onSelect: (String) -> Unit) {
    jars.chunked(columns).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            row.forEach { jar -> JarTile(jar, fillRatio(inv, jar), Modifier.weight(1f), selected == jar["id"]) { onSelect(jar["id"] as String) } }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

// ---------------------------------------------------------------- Kaffees

private fun flexDate(date: Obj?): String? {
    val value = date?.get("value") as? String ?: return null
    if (date["precision"] == "unknown") return null
    val text = runCatching { LocalDate.parse(value).format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) }.getOrNull() ?: return null
    return if (date["precision"] == "approximate") "ca. $text" else text
}

@Suppress("UNCHECKED_CAST")
@Composable
fun CoffeeView(inv: Inventory, onAllocate: (String?) -> Unit, onJar: (String) -> Unit) {
    val lots = inv.state.allLots().filter { it["archived"] != true }
    // Tapping a lot opens its origin tree and tapping it again closes it; at the start everything is closed.
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val c = Kaffee.colors
    PageHeading("Packungen & Chargen", "Kaffees")
    PrimaryButton("Packung öffnen") { onAllocate(null) }
    Spacer(Modifier.height(Kaffee.spacing.gap))
    Text("Teilchargen", style = Kaffee.type.title, color = c.ink, modifier = Modifier.padding(bottom = 8.dp))
    if (lots.isEmpty()) Empty("Noch keine Teilcharge", "Öffne eine Packung und bestätige ihre Glasaufteilung.")
    lots.forEach { lot ->
        val id = lot["id"] as String
        val expanded = open == id
        val jars = inv.state.allJars().filter { it["lotId"] == id }
        val shape = RoundedCornerShape(Kaffee.spacing.radiusLg)
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(shape).background(c.surface200)
            .border(1.5.dp, if (expanded) c.brand else c.line, shape).clickable { open = if (expanded) null else id }.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MethodBadge(lot["brewMethod"] as String)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(lot["name"] as String, style = Kaffee.type.bodyStrong, color = c.ink)
                    Text("${methodWord(lot)} · ${categoryName(inv.settings, lot["categoryId"])}", style = Kaffee.type.small, color = c.inkMuted)
                }
                Text(formatPortions(lotGrams(inv, lot), portion(lot)), style = Kaffee.type.label, color = c.ink)
            }
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                jars.chunked(16).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { row.forEach { MiniJar(it["status"] as String) } }
                }
            }
        }
        if (expanded) LotTree(inv, lot, onJar)
    }
    Text("Ungeöffnete Packungen", style = Kaffee.type.title, color = c.ink, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
    val unopened = inv.state.allPackages().filter { it["openedAt"] == null && it["archived"] != true }
    if (unopened.isEmpty()) Text("Keine ungeöffneten Packungen.", style = Kaffee.type.small, color = c.inkMuted)
    unopened.forEach { pkg ->
        val roast = flexDate(pkg["roastDate"] as? Obj)
        val shape = RoundedCornerShape(Kaffee.spacing.radiusMd)
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(shape).background(c.surface200).border(1.dp, c.line, shape).padding(start = 12.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(pkg["name"] as String, style = Kaffee.type.bodyStrong, color = c.ink)
                Text("${grams(pkg["availableGrams"] as Double)} · ${if (pkg["location"] == "frozen") "tiefgekühlt" else "Vorrat"}" +
                    (roast?.let { " · Röstdatum $it" } ?: "") + (if (inv.beanLinks.containsKey(pkg["id"])) " · Bohne verknüpft" else ""),
                    style = Kaffee.type.small, color = c.inkMuted)
            }
            SecondaryButton("Öffnen", Modifier.width(96.dp).heightIn(min = 44.dp)) { onAllocate(pkg["id"] as String) }
        }
    }
}

@Composable
private fun MiniJar(status: String) {
    val skin = statusSkin(status)
    val free = status == "free" || status == "planned"
    Box(Modifier.size(width = 12.dp, height = 15.dp).clip(RoundedCornerShape(3.dp)).background(skin.bg)
        .statusBorder(if (free) BorderKind.DASHED else BorderKind.SOLID, skin.fg, 1.5.dp, 3.dp))
}

@Composable
private fun TreeNode(label: String, title: String, subtitle: String) {
    val c = Kaffee.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Kaffee.spacing.radiusMd)).background(c.surface100)
        .border(1.dp, c.line, RoundedCornerShape(Kaffee.spacing.radiusMd)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(label.uppercase(), style = Kaffee.type.eyebrow.copy(letterSpacing = 1.sp), color = c.inkMuted)
        Text(title, style = Kaffee.type.bodyStrong, color = c.ink)
        Text(subtitle, style = Kaffee.type.small, color = c.inkMuted)
    }
}

@Composable
private fun TreeLine() = Box(Modifier.padding(start = 22.dp).size(width = 2.dp, height = 16.dp).background(Kaffee.colors.line))

/** Origin tree of a lot: package, partial batch, then its glasses grouped by size (tap a glass for its detail). */
@Composable
private fun LotTree(inv: Inventory, lot: Obj, onJar: (String) -> Unit) {
    val c = Kaffee.colors
    val pkg = inv.state.pkg(lot["packageId"] as String)
    val jars = inv.state.allJars().filter { it["lotId"] == lot["id"] }
    val shape = RoundedCornerShape(Kaffee.spacing.radiusLg)
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(shape).background(c.surface200).border(1.dp, c.line, shape).padding(14.dp)) {
        val dates = listOfNotNull(flexDate(pkg?.get("roastDate") as? Obj)?.let { "Röstdatum $it" }, flexDate(pkg?.get("purchaseDate") as? Obj)?.let { "gekauft $it" })
        TreeNode("Packung", pkg?.get("name") as? String ?: "—", (listOf("${grams((pkg?.get("nominalGrams") as? Double) ?: 0.0)} Nenngewicht") + dates).joinToString(" · "))
        TreeLine()
        TreeNode("Teilcharge", "${grams(lot["initialGrams"] as Double)} ${methodWord(lot)}",
            "${if (lot["beanState"] == "whole") "Ganze Bohne" else "Gemahlen"} · ${categoryName(inv.settings, lot["categoryId"])} · ${grams(portion(lot))} je Portion")
        TreeLine()
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Kaffee.spacing.radiusMd)).background(c.surface100)
            .border(1.dp, c.line, RoundedCornerShape(Kaffee.spacing.radiusMd)).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text("GLÄSER", style = Kaffee.type.eyebrow.copy(letterSpacing = 1.sp), color = c.inkMuted)
            if (jars.isEmpty()) Text("Noch keine Gläser.", style = Kaffee.type.small, color = c.inkMuted)
            listOf("S", "M", "L").forEach { size ->
                val list = jars.filter { it["size"] == size }
                if (list.isNotEmpty()) {
                    Text("$size · ${list.size} ${if (list.size == 1) "Glas" else "Gläser"}", style = Kaffee.type.label, color = c.ink, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                    TileGrid(inv, list, null, 4, onJar)
                }
            }
            if ((lot["unallocatedGrams"] as Double) > 0) {
                Spacer(Modifier.height(4.dp))
                TreeNode("Nicht eingelagert", "Restmenge", grams(lot["unallocatedGrams"] as Double))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FactBox("Auftauvorgänge", (jars.maxOfOrNull { it["thawCount"] as Double } ?: 0.0).roundToInt().toString(), Modifier.weight(1f))
            FactBox("Wiedereinfrieren", (jars.maxOfOrNull { it["refreezeCount"] as Double } ?: 0.0).roundToInt().toString(), Modifier.weight(1f))
            FactBox("Regelprofil", lot["ruleProfile"] as String, Modifier.weight(1f))
        }
    }
}

// ---------------------------------------------------------------- Verlauf

val EVENT_LABEL = mapOf(
    "PACKAGE_CREATED" to "Packung angelegt", "PACKAGE_FROZEN" to "Packung eingefroren", "PACKAGE_OPENED" to "Packung geöffnet",
    "LOT_CREATED" to "Teilcharge erstellt", "LOT_GROUND" to "Vorratsmahlung", "ALLOCATION_COMMITTED" to "Gläser befüllt",
    "JAR_OPENED" to "Glas geöffnet", "TRANSFER_COMMITTED" to "Kaffee umgefüllt", "CONSUMED" to "Kaffee verbraucht",
    "JAR_CLEANED" to "Glas gereinigt", "CATEGORY_CHANGED" to "Kategorie geändert", "CATEGORY_DEFINED" to "Kategorie angelegt",
    "SETTINGS_CHANGED" to "Einstellung geändert", "ARCHIVED" to "Archiviert", "RULE_OVERRIDE" to "Regelabweichung",
    "MEASUREMENT_ADJUSTMENT" to "Messkorrektur", "CORRECTION" to "Fehler-Gegenbuchung",
)

@Composable
fun HistoryView(inv: Inventory, onCorrect: (Obj) -> Unit) {
    val c = Kaffee.colors
    PageHeading("Unveränderliches Journal", "Verlauf", "Fehler verschwinden nicht: Eine Gegenbuchung hebt ihre Bestandswirkung nachvollziehbar auf.")
    val events = inv.document.objs("events").sortedByDescending { it["effectiveAt"] as String }
    Panel {
        if (events.isEmpty()) Empty("Noch keine Ereignisse", "Die erste erfasste Packung beginnt dein Journal.")
        events.forEach { event ->
            val id = event["id"] as String
            val corrected = id in inv.state.correctedEventIds
            Row(Modifier.fillMaxWidth().alpha(if (corrected) 0.55f else 1f).padding(vertical = 12.dp)) {
                Box(Modifier.padding(top = 5.dp).size(10.dp).clip(CircleShape).background(c.brand))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(EVENT_LABEL[event["type"] as String] ?: event["type"] as String, style = Kaffee.type.bodyStrong, color = c.ink,
                        textDecoration = if (corrected) TextDecoration.LineThrough else null)
                    Text(formatDateTime(event["effectiveAt"] as String), style = Kaffee.type.small, color = c.inkMuted)
                    val resources = (event["resourceIds"] as List<*>).joinToString(" · ").ifEmpty { "Bestand" }
                    Text(resources + ((event["reason"] as? String)?.let { " — $it" } ?: ""), style = Kaffee.type.small, color = c.inkMuted)
                    (event["warnings"] as? List<*>)?.forEach { Text(it.toString(), style = Kaffee.type.small, color = c.danger) }
                    if (id in inv.pendingIds) Text("wartet auf Synchronisierung", style = Kaffee.type.tiny, color = c.brand)
                    Text(id, style = Kaffee.type.tiny, color = c.inkMuted)
                }
                if (event["type"] != "CORRECTION" && !corrected) TextLink("Fehler buchen") { onCorrect(event) }
            }
            HorizontalDivider(color = c.line)
        }
    }
}

// ---------------------------------------------------------------- Einstellungen (Darstellung, Verbindung, Daten)

@Composable
fun SettingsView(
    inv: Inventory, appearance: Appearance, onAppearance: ((Appearance) -> Appearance) -> Unit,
    paired: Boolean, syncText: String, pending: Int, onPair: () -> Unit, onUnpair: () -> Unit, onSync: () -> Unit,
    onExportJson: () -> Unit, onExportCsv: () -> Unit, onCategory: (String, String) -> Unit, onUpdate: () -> Unit,
) {
    val c = Kaffee.colors
    PageHeading("Darstellung", "Einstellungen", "Farben, Glas-Stil und Helligkeit. Die Auswahl gilt sofort überall und bleibt nur auf diesem Gerät gespeichert.")
    Panel {
        PanelHead("Look", "Farben")
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PaletteId.entries.forEach { id ->
                val preview = Palettes.colors(id, appearance.mode == ThemeMode.DARK)
                OptionCard(appearance.palette == id, { onAppearance { it.copy(palette = id) } }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row {
                            listOf(preview.brand, preview.frozenBg, preview.activeBg, preview.cleanBg, preview.surface100).forEachIndexed { i, color ->
                                Box(Modifier.padding(start = if (i == 0) 0.dp else 15.dp).size(20.dp).clip(CircleShape).background(color).border(1.5.dp, c.surface200, CircleShape))
                            }
                        }
                        Spacer(Modifier.width(2.dp))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(id.label, style = Kaffee.type.bodyStrong, color = c.ink)
                            Text(id.hint, style = Kaffee.type.small, color = c.inkMuted)
                        }
                        RadioDot(appearance.palette == id)
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(Kaffee.spacing.gap))
    Panel {
        PanelHead("Gläser", "Glas-Stil")
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TileStyle.entries.forEach { style ->
                OptionCard(appearance.tileStyle == style, { onAppearance { it.copy(tileStyle = style) } }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(style.label, style = Kaffee.type.bodyStrong, color = c.ink)
                                Text(style.hint, style = Kaffee.type.small, color = c.inkMuted)
                            }
                            RadioDot(appearance.tileStyle == style)
                        }
                        CompositionLocalProvider(LocalTileStyle provides style) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(Triple("S01", "frozen", 15.0), Triple("S05", "active", 18.0), Triple("S10", "cleaning", 0.0), Triple("S02", "free", 0.0)).forEach { (id, status, g) ->
                                    val jar: Obj = mutableMapOf("id" to id, "status" to status, "grams" to g)
                                    JarTile(jar, fill = if (status == "frozen") 1f else if (status == "active") 0.55f else 0f, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(Kaffee.spacing.gap))
    Panel {
        PanelHead("Helligkeit", "Modus")
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                OptionCard(appearance.mode == mode, { onAppearance { it.copy(mode = mode) } }, Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text(mode.label, style = Kaffee.type.bodyStrong, color = c.ink) }
                }
            }
        }
    }
    Spacer(Modifier.height(Kaffee.spacing.gap * 2))
    Eyebrow("Server & Sicherung")
    Text("Verbindung und Daten", style = Kaffee.type.sheetTitle, color = c.ink)
    Spacer(Modifier.height(Kaffee.spacing.gap))
    DataSections(inv, paired, syncText, pending, onPair, onUnpair, onSync, onExportJson, onExportCsv, onCategory, onUpdate)
}

@Composable
private fun DataSections(
    inv: Inventory, paired: Boolean, syncText: String, pending: Int, onPair: () -> Unit, onUnpair: () -> Unit, onSync: () -> Unit,
    onExportJson: () -> Unit, onExportCsv: () -> Unit, onCategory: (String, String) -> Unit, onUpdate: () -> Unit,
) {
    val c = Kaffee.colors
    Panel {
        Text("Verbindung zu TESS", style = Kaffee.type.title, color = c.ink)
        DetailRow("Status", if (paired) "gekoppelt" else "nicht gekoppelt")
        DetailRow("Synchronisierung", syncText)
        DetailRow("Ausstehende Buchungen", pending.toString())
        Spacer(Modifier.height(8.dp))
        if (paired) {
            PrimaryButton("Jetzt synchronisieren", onClick = onSync)
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Neu koppeln", onClick = onPair)
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Kopplung entfernen", onClick = onUnpair)
        } else PrimaryButton("Mit TESS koppeln", onClick = onPair)
    }
    Spacer(Modifier.height(Kaffee.spacing.gap))
    Panel {
        Text("Export", style = Kaffee.type.title, color = c.ink)
        Text("Vollständiges Ereignisjournal: ${inv.document.objs("events").size} Buchungen", style = Kaffee.type.small, color = c.inkMuted)
        Spacer(Modifier.height(8.dp))
        PrimaryButton("Vollständiges JSON teilen", onClick = onExportJson)
        Spacer(Modifier.height(8.dp))
        SecondaryButton("Drei CSV-Dateien teilen", onClick = onExportCsv)
    }
    Spacer(Modifier.height(Kaffee.spacing.gap))
    Panel {
        var name by remember { mutableStateOf("") }
        var profile by remember { mutableStateOf("A") }
        Text("Eigene Kategorie", style = Kaffee.type.title, color = c.ink)
        Text("Eigene Bezeichnungen nutzen weiterhin ein klar festgelegtes Regelprofil.", style = Kaffee.type.small, color = c.inkMuted, modifier = Modifier.padding(bottom = 8.dp))
        TextInput("Name", name, placeholder = "z. B. Wettbewerb") { name = it }
        Spacer(Modifier.height(8.dp))
        Select("Regelprofil", PROFILE_LABEL.toList(), profile) { profile = it }
        Spacer(Modifier.height(8.dp))
        PrimaryButton("Kategorie anlegen", enabled = name.isNotBlank()) { onCategory(name, profile); name = "" }
        Spacer(Modifier.height(8.dp))
        inv.settings.objs("categories").forEach {
            Text("${it["name"]} · ${it["ruleProfile"]}", style = Kaffee.type.small, color = c.cleanFg,
                modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(50)).background(c.cleanBg).padding(horizontal = 10.dp, vertical = 5.dp))
        }
    }
    Spacer(Modifier.height(Kaffee.spacing.gap))
    Panel {
        Text("Datenprüfung", style = Kaffee.type.title, color = c.ink)
        DetailRow("Gläser", inv.state.jars.size.toString())
        DetailRow("Packungen", inv.state.packages.size.toString())
        DetailRow("Teilchargen", inv.state.lots.size.toString())
        DetailRow("Ableitungswarnungen", inv.state.warnings.size.toString())
        inv.state.warnings.forEach { ErrorText(it) }
    }
    Spacer(Modifier.height(Kaffee.spacing.gap))
    Panel {
        Text("App", style = Kaffee.type.title, color = c.ink)
        DetailRow("Version", de.baschek.kaffee.BuildConfig.VERSION_NAME)
        SecondaryButton("Nach Update suchen", onClick = onUpdate)
    }
}
