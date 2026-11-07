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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import de.baschek.kaffee.core.domain.Obj
import de.baschek.kaffee.core.domain.formatPortions
import de.baschek.kaffee.core.domain.jsRound
import de.baschek.kaffee.core.domain.objs
import de.baschek.kaffee.data.Inventory
import de.baschek.kaffee.ui.theme.Kaffee
import kotlin.math.roundToInt

private fun categoryName(settings: Obj, id: Any?): String =
    settings.objs("categories").firstOrNull { it["id"] == id }?.get("name") as? String ?: "Eigene Kategorie"

fun lotGrams(inv: Inventory, lot: Obj): Double =
    jsRound(inv.state.allJars().filter { it["lotId"] == lot["id"] }.sumOf { it["grams"] as Double } + (lot["unallocatedGrams"] as Double))

fun portion(lot: Obj): Double = jsRound((lot["baseGrams"] as Double) + (lot["allowanceGrams"] as Double))

@Composable
fun Dashboard(inv: Inventory, onAdd: () -> Unit, onAllocate: () -> Unit, onJar: (String) -> Unit, onView: (Tab) -> Unit) {
    val jars = inv.state.allJars()
    fun free(size: String) = jars.count { it["size"] == size && it["status"] == "free" }
    val cleaning = jars.count { it["status"] == "cleaning" }
    val frozen = jars.count { it["status"] == "frozen" }
    val active = jars.count { it["status"] == "active" }
    val packages = inv.state.allPackages()
    val c = Kaffee.colors
    PageHeading("Bestand heute", "Alles an seinem Platz.")
    SecondaryButton("Kaffee öffnen & aufteilen", onClick = onAllocate)
    Spacer(Modifier.height(8.dp))
    PrimaryButton("Neue Packung", onClick = onAdd)
    Spacer(Modifier.height(Kaffee.spacing.gap))
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        MetricTile("Tiefgekühlte Gläser", frozen, "$active aktiv aufgetaut", c.metricCoffee, c.onDark, Modifier.weight(1f))
        MetricTile("Freie Gläser", free("S") + free("M") + free("L"), "${free("S")} S · ${free("M")} M · ${free("L")} L", c.metricIce, c.text, Modifier.weight(1f))
    }
    Spacer(Modifier.height(9.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        MetricTile("Ungeöffnete Packungen", packages.count { it["openedAt"] == null && it["archived"] != true },
            "${packages.count { it["location"] == "frozen" && it["openedAt"] == null }} komplett eingefroren", c.metricGold, c.text, Modifier.weight(1f))
        MetricTile("Zu reinigen", cleaning, if (cleaning > 0) "Noch nicht wieder verfügbar" else "Alle Gläser einsatzbereit", c.metricSlate, c.text, Modifier.weight(1f))
    }
    Spacer(Modifier.height(Kaffee.spacing.gap))
    Panel {
        PanelHead("Kaffees", "Verfügbare Portionen", "Alle ansehen →") { onView(Tab.COFFEE) }
        val lots = inv.state.allLots().filter { it["archived"] != true }
        if (inv.state.lots.isEmpty()) Empty("Noch keine portionierte Charge", "Erfasse zuerst eine Packung und teile sie anschließend auf.", "Packung hinzufügen", onAdd)
        lots.take(6).forEach { lot ->
            Row(Modifier.fillMaxWidth().clickable { onView(Tab.COFFEE) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                MethodBadge(lot["brewMethod"] as String)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(lot["name"] as String, fontWeight = FontWeight.Bold, color = c.text)
                    Text(categoryName(inv.settings, lot["categoryId"]), style = Kaffee.type.small, color = c.muted)
                }
                Text(formatPortions(lotGrams(inv, lot), portion(lot)), style = Kaffee.type.small, fontWeight = FontWeight.Bold, color = c.text)
            }
            HorizontalDivider(color = c.line)
        }
    }
    Spacer(Modifier.height(Kaffee.spacing.gap))
    Panel {
        PanelHead("Gefrierfach", "Glasstatus", "Matrix öffnen →") { onView(Tab.JARS) }
        val used = jars.filter { it["status"] != "free" }.take(24)
        if (used.isEmpty()) Empty("Alle Gläser sind frei", "Sobald du eine Charge bestätigst, erscheint sie hier.")
        used.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.padding(top = 9.dp)) {
                row.forEach { jar -> JarTile(jar, Modifier.weight(1f)) { onJar(jar["id"] as String) } }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    if (inv.state.overrides.isNotEmpty()) {
        val n = inv.state.overrides.size
        Notice("$n dokumentierte Regelabweichung${if (n == 1) "" else "en"}", listOf("Im Verlauf bleiben Begründung und betroffene Charge dauerhaft sichtbar."))
    }
}

@Composable
fun JarMatrix(inv: Inventory, selected: String?, onSelect: (String) -> Unit) {
    PageHeading("S01 bis L12", "Alle 96 Gläser", "Ein Tipp zeigt Inhalt, Herkunft und die nächsten möglichen Aktionen.")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
        listOf("free", "frozen", "active", "cleaning").forEach { status ->
            val (fill, border) = jarColors(status)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(fill).border(1.dp, border, RoundedCornerShape(3.dp)))
                Text(" ${if (status == "cleaning") "Reinigung" else STATUS_LABEL[status]}", style = Kaffee.type.small, color = Kaffee.colors.text)
            }
        }
    }
    for (size in listOf("S", "M", "L")) {
        Panel(Modifier.padding(bottom = Kaffee.spacing.gap)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(Kaffee.colors.brown), contentAlignment = Alignment.Center) {
                    Text(size, style = Kaffee.type.title, color = Kaffee.colors.onDark)
                }
                Column(Modifier.padding(start = 13.dp)) {
                    Text(when (size) { "S" -> "Einzelportionen"; "M" -> "Mehrportionen"; else -> "Großgläser" }, style = Kaffee.type.title, color = Kaffee.colors.text)
                    Text(when (size) { "S" -> "53 ml · 48 Gläser"; "M" -> "212 ml · 36 Gläser"; else -> "555 ml · 12 Gläser" }, style = Kaffee.type.small, color = Kaffee.colors.muted)
                }
            }
            inv.state.allJars().filter { it["size"] == size }.chunked(6).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(bottom = 7.dp)) {
                    row.forEach { jar -> JarTile(jar, Modifier.weight(1f), selected == jar["id"]) { onSelect(jar["id"] as String) } }
                    repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
fun CoffeeView(inv: Inventory, onAllocate: () -> Unit, onJar: (String) -> Unit) {
    val lots = inv.state.allLots().filter { it["archived"] != true }
    var selected by remember { mutableStateOf<String?>(null) }
    val lot = selected?.let { inv.state.lot(it) } ?: lots.firstOrNull()
    PageHeading("Packungen & Chargen", "Der Weg jeder Bohne")
    PrimaryButton("Packung öffnen", onClick = onAllocate)
    Spacer(Modifier.height(Kaffee.spacing.gap))
    Panel {
        Text("Ungeöffnete Packungen", style = Kaffee.type.title, color = Kaffee.colors.text, modifier = Modifier.padding(bottom = 10.dp))
        val unopened = inv.state.allPackages().filter { it["openedAt"] == null && it["archived"] != true }
        if (unopened.isEmpty()) Text("Keine ungeöffneten Packungen.", style = Kaffee.type.small, color = Kaffee.colors.muted)
        unopened.forEach { pkg ->
            ListCard(pkg["name"] as String, "${grams(pkg["availableGrams"] as Double)} · ${if (pkg["location"] == "frozen") "komplett tiefgekühlt" else "Vorrat"}",
                linked = inv.beanLinks.containsKey(pkg["id"]))
        }
        Text("Teilchargen", style = Kaffee.type.title, color = Kaffee.colors.text, modifier = Modifier.padding(top = 14.dp, bottom = 10.dp))
        lots.forEach { item ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(10.dp)).background(Kaffee.colors.card)
                .border(if (lot?.get("id") == item["id"]) 2.dp else 1.dp, if (lot?.get("id") == item["id"]) Kaffee.colors.link else Kaffee.colors.line, RoundedCornerShape(10.dp))
                .clickable { selected = item["id"] as String }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                MethodBadge(item["brewMethod"] as String)
                Column(Modifier.padding(start = 11.dp)) {
                    Text(item["name"] as String, fontWeight = FontWeight.Bold, color = Kaffee.colors.text)
                    Text(categoryName(inv.settings, item["categoryId"]), style = Kaffee.type.small, color = Kaffee.colors.muted)
                }
            }
        }
    }
    Spacer(Modifier.height(Kaffee.spacing.gap))
    Panel {
        if (lot == null) Empty("Noch keine Teilcharge", "Öffne eine Packung und bestätige ihre Glasaufteilung.")
        else LotLineage(inv, lot, onJar)
    }
}

@Composable
private fun ListCard(title: String, subtitle: String, linked: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(10.dp)).background(Kaffee.colors.card)
        .border(1.dp, Kaffee.colors.line, RoundedCornerShape(10.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("▰", color = Kaffee.colors.gold)
        Column(Modifier.padding(start = 11.dp).weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, color = Kaffee.colors.text)
            Text(subtitle, style = Kaffee.type.small, color = Kaffee.colors.muted)
        }
        if (linked) Text("Bohne verknüpft", style = Kaffee.type.tiny, color = Kaffee.colors.muted)
    }
}

@Composable
private fun LotLineage(inv: Inventory, lot: Obj, onJar: (String) -> Unit) {
    val pkg = inv.state.pkg(lot["packageId"] as String)
    val jars = inv.state.allJars().filter { it["lotId"] == lot["id"] }
    val total = lotGrams(inv, lot)
    Eyebrow("Herkunftsbaum")
    Text(lot["name"] as String, style = Kaffee.type.display, color = Kaffee.colors.text)
    Text("${if (lot["brewMethod"] == "espresso") "Espresso" else "Filter"} · ${if (lot["beanState"] == "whole") "ganze Bohne" else "gemahlen"} · ${formatPortions(total, portion(lot))}",
        style = Kaffee.type.body, color = Kaffee.colors.muted)
    Text(grams(total), color = Kaffee.colors.onDark, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 10.dp).clip(RoundedCornerShape(30.dp)).background(Kaffee.colors.brown).padding(horizontal = 14.dp, vertical = 8.dp))
    TreeNode("Ursprungspackung", pkg?.get("name") as? String ?: "—", "${grams((pkg?.get("nominalGrams") as? Double) ?: 0.0)} Nenngewicht")
    TreeNode("Teilcharge", categoryName(inv.settings, lot["categoryId"]), "${grams(portion(lot))} je Portion")
    jars.chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            row.forEach { jar ->
                val (fill, _) = jarColors(jar["status"] as String)
                Column(Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(fill).border(1.dp, Kaffee.colors.line, RoundedCornerShape(11.dp))
                    .clickable { onJar(jar["id"] as String) }.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(STATUS_LABEL[jar["status"] as String] ?: "", style = Kaffee.type.tiny, color = Kaffee.colors.muted)
                    Text(jar["id"] as String, fontWeight = FontWeight.Bold, color = Kaffee.colors.text)
                    Text(grams(jar["grams"] as Double), style = Kaffee.type.tiny, color = Kaffee.colors.muted)
                }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    if ((lot["unallocatedGrams"] as Double) > 0) TreeNode("Nicht eingelagert", grams(lot["unallocatedGrams"] as Double), "Teilfüllung außerhalb der Gläser")
    HorizontalDivider(color = Kaffee.colors.line, modifier = Modifier.padding(top = 16.dp))
    DetailRow("Auftauvorgänge", (jars.maxOfOrNull { it["thawCount"] as Double } ?: 0.0).roundToInt().toString())
    DetailRow("Wiedereinfrierungen", (jars.maxOfOrNull { it["refreezeCount"] as Double } ?: 0.0).roundToInt().toString())
    DetailRow("Regelprofil", lot["ruleProfile"] as String)
}

@Composable
private fun TreeNode(label: String, title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(11.dp)).background(Kaffee.colors.card)
        .border(1.dp, Kaffee.colors.line, RoundedCornerShape(11.dp)).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(), style = Kaffee.type.tiny, color = Kaffee.colors.muted)
        Text(title, fontWeight = FontWeight.Bold, color = Kaffee.colors.text)
        Text(subtitle, style = Kaffee.type.small, color = Kaffee.colors.muted)
    }
}

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
    PageHeading("Unveränderliches Journal", "Vollständiger Verlauf", "Fehler verschwinden nicht: Eine Gegenbuchung hebt ihre Bestandswirkung nachvollziehbar auf.")
    val events = inv.document.objs("events").sortedByDescending { it["effectiveAt"] as String }
    Panel {
        if (events.isEmpty()) Empty("Noch keine Ereignisse", "Die erste erfasste Packung beginnt dein Journal.")
        events.forEach { event ->
            val id = event["id"] as String
            val corrected = id in inv.state.correctedEventIds
            Row(Modifier.fillMaxWidth().alpha(if (corrected) 0.48f else 1f).padding(vertical = 12.dp)) {
                Box(Modifier.padding(top = 5.dp).size(10.dp).clip(CircleShape).background(Kaffee.colors.coffee))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(EVENT_LABEL[event["type"] as String] ?: event["type"] as String, fontWeight = FontWeight.Bold, color = Kaffee.colors.text,
                        textDecoration = if (corrected) TextDecoration.LineThrough else null)
                    Text(formatDateTime(event["effectiveAt"] as String), style = Kaffee.type.small, color = Kaffee.colors.muted)
                    val resources = (event["resourceIds"] as List<*>).joinToString(" · ").ifEmpty { "Bestand" }
                    Text(resources + ((event["reason"] as? String)?.let { " — $it" } ?: ""), style = Kaffee.type.small, color = Kaffee.colors.muted)
                    (event["warnings"] as? List<*>)?.forEach { Text(it.toString(), style = Kaffee.type.small, color = Kaffee.colors.error) }
                    if (id in inv.pendingIds) Text("wartet auf Synchronisierung", style = Kaffee.type.tiny, color = Kaffee.colors.eyebrow)
                    Text(id, style = Kaffee.type.tiny, color = Kaffee.colors.muted)
                }
                if (event["type"] != "CORRECTION" && !corrected) TextButton(onClick = { onCorrect(event) }) { Text("Fehler buchen", color = Kaffee.colors.link) }
            }
            HorizontalDivider(color = Kaffee.colors.line)
        }
    }
}

@Composable
fun DataView(
    inv: Inventory, paired: Boolean, syncText: String, pending: Int, onPair: () -> Unit, onUnpair: () -> Unit, onSync: () -> Unit,
    onExportJson: () -> Unit, onExportCsv: () -> Unit, onCategory: (String, String) -> Unit, onUpdate: () -> Unit,
) {
    PageHeading("Sicherung & Regeln", "Deine Daten bleiben greifbar.", "TESS auf dem Pi ist die zentrale Quelle; JSON und CSV kannst du jederzeit zusätzlich exportieren.")
    Panel {
        Text("Verbindung zu TESS", style = Kaffee.type.title, color = Kaffee.colors.text)
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
        Text("Export", style = Kaffee.type.title, color = Kaffee.colors.text)
        Text("Vollständiges Ereignisjournal: ${inv.document.objs("events").size} Buchungen", style = Kaffee.type.small, color = Kaffee.colors.muted)
        Spacer(Modifier.height(8.dp))
        PrimaryButton("Vollständiges JSON teilen", onClick = onExportJson)
        Spacer(Modifier.height(8.dp))
        SecondaryButton("Drei CSV-Dateien teilen", onClick = onExportCsv)
    }
    Spacer(Modifier.height(Kaffee.spacing.gap))
    Panel {
        var name by remember { mutableStateOf("") }
        var profile by remember { mutableStateOf("A") }
        Text("Eigene Kategorie", style = Kaffee.type.title, color = Kaffee.colors.text)
        Text("Eigene Bezeichnungen nutzen weiterhin ein klar festgelegtes Regelprofil.", style = Kaffee.type.small, color = Kaffee.colors.muted)
        TextInput("Name", name, placeholder = "z. B. Wettbewerb") { name = it }
        Select("Regelprofil", PROFILE_LABEL.toList(), profile) { profile = it }
        Spacer(Modifier.height(8.dp))
        PrimaryButton("Kategorie anlegen", enabled = name.isNotBlank()) { onCategory(name, profile); name = "" }
        Spacer(Modifier.height(8.dp))
        inv.settings.objs("categories").forEach {
            Text("${it["name"]} · ${it["ruleProfile"]}", style = Kaffee.type.small, color = Kaffee.colors.text,
                modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(20.dp)).background(Kaffee.colors.strip).padding(horizontal = 9.dp, vertical = 5.dp))
        }
    }
    Spacer(Modifier.height(Kaffee.spacing.gap))
    Panel {
        Text("Datenprüfung", style = Kaffee.type.title, color = Kaffee.colors.text)
        DetailRow("Gläser", inv.state.jars.size.toString())
        DetailRow("Packungen", inv.state.packages.size.toString())
        DetailRow("Teilchargen", inv.state.lots.size.toString())
        DetailRow("Ableitungswarnungen", inv.state.warnings.size.toString())
        inv.state.warnings.forEach { ErrorText(it) }
    }
    Spacer(Modifier.height(Kaffee.spacing.gap))
    Panel {
        Text("App", style = Kaffee.type.title, color = Kaffee.colors.text)
        DetailRow("Version", de.baschek.kaffee.BuildConfig.VERSION_NAME)
        SecondaryButton("Nach Update suchen", onClick = onUpdate)
    }
    Spacer(Modifier.width(1.dp))
}
