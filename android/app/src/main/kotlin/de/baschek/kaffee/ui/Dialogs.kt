package de.baschek.kaffee.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.baschek.kaffee.core.domain.Actions
import de.baschek.kaffee.core.domain.DomainError
import de.baschek.kaffee.core.domain.FillPlanItem
import de.baschek.kaffee.core.domain.Obj
import de.baschek.kaffee.core.domain.TransferDraft
import de.baschek.kaffee.core.domain.formatPortions
import de.baschek.kaffee.core.domain.germanNumber
import de.baschek.kaffee.core.domain.jsRound
import de.baschek.kaffee.core.domain.objs
import de.baschek.kaffee.core.domain.transferDraft
import de.baschek.kaffee.data.Inventory
import de.baschek.kaffee.ui.theme.Kaffee
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(title: String, subtitle: String?, onClose: () -> Unit, eyebrow: String? = null, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Kaffee.colors.surface100, contentColor = Kaffee.colors.ink, shape = RoundedCornerShape(topStart = Kaffee.spacing.radiusLg, topEnd = Kaffee.spacing.radiusLg)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column {
                if (eyebrow != null) Eyebrow(eyebrow)
                Text(title, style = Kaffee.type.sheetTitle, color = Kaffee.colors.ink)
            }
            if (subtitle != null) Text(subtitle, style = Kaffee.type.body, color = Kaffee.colors.inkMuted)
            HorizontalDivider(color = Kaffee.colors.line)
            content()
        }
    }
}

private val PRECISION = listOf("exact" to "genau", "approximate" to "ungefähr")

@Composable
private fun DateField(label: String, value: String, precision: String, onValue: (String) -> Unit, onPrecision: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextInput("$label (JJJJ-MM-TT)", value, Modifier.weight(1.4f)) { onValue(it.trim()) }
        Select("Genauigkeit", PRECISION, precision, Modifier.weight(1f), onPrecision)
    }
}

private fun validDate(value: String) = value.isBlank() || runCatching { LocalDate.parse(value) }.isSuccess

@Composable
fun AddPackageSheet(onClose: () -> Unit, onSave: (List<Obj>) -> Unit) {
    var name by remember { mutableStateOf("") }
    var grams by remember { mutableStateOf(250.0) }
    var location by remember { mutableStateOf("pantry") }
    var roast by remember { mutableStateOf("") }
    var roastPrecision by remember { mutableStateOf("exact") }
    var purchase by remember { mutableStateOf(LocalDate.now().toString()) }
    var purchasePrecision by remember { mutableStateOf("exact") }
    var error by remember { mutableStateOf<String?>(null) }
    Sheet("Neue Packung", "Ungeöffnet im Vorrat oder komplett tiefgekühlt", onClose) {
        TextInput("Kaffeename", name, placeholder = "z. B. Sterne Espresso") { name = it }
        NumberField("Nenngewicht in Gramm", grams) { grams = it }
        Select("Lagerort", listOf("pantry" to "Vorrat", "frozen" to "Komplett tiefgekühlt"), location) { location = it }
        DateField("Röstdatum", roast, roastPrecision, { roast = it }, { roastPrecision = it })
        DateField("Kaufdatum", purchase, purchasePrecision, { purchase = it }, { purchasePrecision = it })
        error?.let { ErrorText(it) }
        PrimaryButton("Packung speichern", enabled = name.isNotBlank() && grams > 0) {
            if (!validDate(roast) || !validDate(purchase)) { error = "Datum bitte als JJJJ-MM-TT eingeben."; return@PrimaryButton }
            onSave(Actions.newPackage(name, grams, Actions.FlexibleDate(roast.ifBlank { null }, roastPrecision),
                Actions.FlexibleDate(purchase.ifBlank { null }, purchasePrecision), location))
        }
        SecondaryButton("Abbrechen", onClick = onClose)
    }
}

@Composable
fun AllocateSheet(inv: Inventory, initialPackageId: String?, onClose: () -> Unit, onSave: (List<Obj>) -> Unit) {
    val settings = inv.settings
    val available = inv.state.allPackages().filter { it["archived"] != true && (it["availableGrams"] as Double) > 0 }
    var packageId by remember { mutableStateOf(available.firstOrNull { it["id"] == initialPackageId }?.get("id") as? String ?: available.firstOrNull()?.get("id") as? String ?: "") }
    val pkg = inv.state.pkg(packageId)
    var actual by remember(packageId) { mutableStateOf((pkg?.get("actualGrams") ?: pkg?.get("nominalGrams")) as? Double ?: 0.0) }
    var amount by remember(packageId) { mutableStateOf(pkg?.get("availableGrams") as? Double ?: 0.0) }
    var brew by remember { mutableStateOf("espresso") }
    var bean by remember { mutableStateOf("whole") }
    var categoryId by remember { mutableStateOf("premium-whole") }
    val category = settings.objs("categories").firstOrNull { it["id"] == categoryId } ?: settings.objs("categories").first()
    var manualProfile by remember { mutableStateOf("A") }
    var base by remember { mutableStateOf(settings["espressoBaseGrams"] as Double) }
    var allowance by remember { mutableStateOf(settings["espressoAllowanceGrams"] as Double) }
    var directS by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<Actions.SplitDraft?>(null) }
    val fills = remember { mutableStateListOf<FillPlanItem>() }
    var confirmed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val current = draft
    Sheet(if (current != null) "Aufteilung bestätigen" else "Packung öffnen & aufteilen",
        if (current != null) "${current.plan.fullPortions} volle Portionen · ${germanNumber(current.plan.restGrams)} g rechnerischer Rest"
        else "Der Vorschlag belegt noch keine Gläser.", onClose) {
        if (available.isEmpty()) { Empty("Keine verfügbare Packung", "Lege zuerst eine Packung an."); return@Sheet }
        if (current == null) {
            Select("Packung", available.map { it["id"] as String to "${it["name"]} · ${germanNumber(it["availableGrams"] as Double)} g" }, packageId) { packageId = it }
            if (pkg != null && pkg["openedAt"] == null) NumberField("Tatsächlich verfügbar", actual) { actual = it; amount = it }
            NumberField("Teilmenge", amount) { amount = it }
            Select("Zubereitung", listOf("espresso" to "Espresso", "filter" to "Filter"), brew) {
                brew = it
                base = settings[if (it == "espresso") "espressoBaseGrams" else "filterBaseGrams"] as Double
                allowance = settings[if (it == "espresso") "espressoAllowanceGrams" else "filterAllowanceGrams"] as Double
            }
            Select("Mahlzustand", listOf("whole" to "Ganze Bohne", "ground" to "Gemahlen"), bean) { bean = it }
            Select("Kategorie", settings.objs("categories").map { it["id"] as String to it["name"] as String }, categoryId) { categoryId = it }
            if (category["ruleProfile"] == "MANUAL") Select("Aufteilungsprofil", listOf("A" to "A · M und S", "B" to "B · L und M"), manualProfile) { manualProfile = it }
            NumberField("Grundmenge je Portion", base) { base = it }
            NumberField("Mahlverlust je Portion", allowance) { allowance = it }
            if (category["ruleProfile"] == "A") CheckRow("Qualitätsalternative: vollständig direkt in S aufteilen", directS) { directS = it }
            error?.let { ErrorText(it) }
            PrimaryButton("Vorschlag berechnen") {
                error = null
                try {
                    val next = Actions.planSplit(inv.state, settings, packageId, actual, amount, brew, bean, categoryId, manualProfile, base, allowance, directS)
                    fills.clear(); fills.addAll(next.plan.fills.map { it.copy() }); confirmed = false; draft = next
                } catch (e: DomainError) { error = e.message }
            }
            SecondaryButton("Abbrechen", onClick = onClose)
        } else {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Kaffee.colors.cleanBg).padding(12.dp)) {
                listOf("Portionsziel" to grams(current.plan.portionGrams), "Gläser" to fills.size.toString(),
                    "Nicht eingelagert" to grams(current.plan.unallocatedGrams)).forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, style = Kaffee.type.tiny, color = Kaffee.colors.inkMuted)
                        Text(value, fontWeight = FontWeight.Bold, color = Kaffee.colors.ink)
                    }
                }
            }
            fills.forEachIndexed { index, fill ->
                val freeJars = inv.state.allJars().filter { it["size"] == fill.size && it["status"] == "free" }.map { it["id"] as String }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Select("Glas", freeJars.map { it to it }, fill.jarId, Modifier.weight(1f)) { fills[index] = fill.copy(jarId = it) }
                    NumberField("Ist (Soll ${germanNumber(fill.suggestedGrams)} g)", fill.actualGrams, Modifier.weight(1.3f)) { fills[index] = fill.copy(actualGrams = it) }
                }
            }
            if (current.plan.warnings.isNotEmpty()) {
                Notice("Vor dem Einfrieren prüfen", current.plan.warnings) {
                    CheckRow("Hinweise geprüft und bewusst bestätigt", confirmed) { confirmed = it }
                }
            }
            error?.let { ErrorText(it) }
            PrimaryButton("Einlagerung bestätigen") {
                try {
                    val final = current.copy(plan = current.plan.copy(fills = fills.map { it.copy() }))
                    onSave(Actions.commitSplit(inv.state, final, confirmed))
                } catch (e: DomainError) { error = e.message }
            }
            SecondaryButton("Zurück") { draft = null }
        }
    }
}

/** Typed gram amount in the display serif (used between the minus and plus buttons of the glass detail). */
@Composable
private fun AmountField(value: Double, modifier: Modifier = Modifier, onValue: (Double) -> Unit) {
    var text by remember { mutableStateOf(germanNumber(value)) }
    // Follow the stepper buttons without fighting the user's typing ("18," stays "18,").
    LaunchedEffect(value) { if (text.replace(',', '.').toDoubleOrNull() != value) text = germanNumber(value) }
    BasicTextField(text, { new -> text = new; new.replace(',', '.').toDoubleOrNull()?.let(onValue) }, modifier, singleLine = true,
        textStyle = Kaffee.type.display.copy(textAlign = TextAlign.Center, color = Kaffee.colors.ink),
        cursorBrush = SolidColor(Kaffee.colors.brand), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

private fun step(value: Double, delta: Double, max: Double): Double =
    (Math.round((value + delta) * 10) / 10.0).coerceIn(0.1, maxOf(0.1, max))

/**
 * Glass detail: the only place to book consumption or to open a glass. [onBook] gets the events and the confirmation
 * text; the caller stores them, closes the sheet and offers "Rückgängig".
 */
@Composable
fun JarDetailSheet(inv: Inventory, jarId: String, onClose: () -> Unit, onBook: (List<Obj>, String) -> Unit) {
    val jar = inv.state.jar(jarId) ?: return
    val lot = inv.state.lot(jar["lotId"] as? String)
    val status = jar["status"] as String
    val jarGrams = jar["grams"] as Double
    val size = jar["size"] as String
    val c = Kaffee.colors
    var amount by remember(jarId, status) { mutableStateOf(lot?.let { minOf(portion(it), jarGrams).coerceAtLeast(0.1) } ?: 0.0) }
    var transfer by remember(jarId) { mutableStateOf(false) }
    var error by remember(jarId) { mutableStateOf<String?>(null) }
    fun run(label: String, block: () -> List<Obj>) {
        try { onBook(block(), label) } catch (e: DomainError) { error = e.message }
    }

    Sheet(if (lot != null) "$jarId · ${lot["name"]}" else jarId, null, onClose, eyebrow = "$size-Glas · ${STATUS_LABEL[status]}") {
        Row { StatusBadge(status) }
        if (lot != null) {
            Text("${grams(jarGrams)} · ${if (lot["brewMethod"] == "espresso") "Espresso" else "Filter"} · ${formatPortions(jarGrams, portion(lot))}", style = Kaffee.type.body, color = c.inkMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FactBox("Kaffee", lot["name"] as String, Modifier.weight(1f))
                FactBox("Inhalt", grams(jarGrams), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FactBox("Zubereitung", if (lot["brewMethod"] == "espresso") "Espresso" else "Filter", Modifier.weight(1f))
                FactBox("Größe", when (size) { "S" -> "Klein"; "M" -> "Mittel"; else -> "Groß" }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FactBox("Auftauen", "${germanNumber(jar["thawCount"] as Double)}×", Modifier.weight(1f))
                FactBox("Wiedereinfrieren", "${germanNumber(jar["refreezeCount"] as Double)}×", Modifier.weight(1f))
            }
            FactBox("Eingefroren", formatDateTime(jar["filledAt"] as? String), Modifier.fillMaxWidth())
        }
        error?.let { ErrorText(it) }
        if (status == "frozen") {
            PrimaryButton("Glas öffnen") { run("$jarId geöffnet, jetzt aktiv") { Actions.openJar(jar) } }
            if (size == "S") SecondaryButton("Öffnen & vollständig verbrauchen") { run("$jarId geöffnet und verbraucht, zu reinigen") { Actions.consume(jar, jarGrams) } }
        }
        if (status == "active" && lot != null) {
            if (size == "S") PrimaryButton("Vollständig verbrauchen") { run("$jarId: Rest verbraucht, zu reinigen") { Actions.consume(jar, jarGrams) } }
            else {
                Text("Verbrauch in Gramm (eine Portion = ${grams(portion(lot))})", style = Kaffee.type.body, color = c.inkMuted)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("−", Modifier.width(56.dp).heightIn(min = 56.dp)) { amount = step(amount, -0.1, jarGrams) }
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        AmountField(amount, Modifier.width(110.dp)) { amount = it }
                        Text("g", style = Kaffee.type.title, color = c.inkMuted)
                    }
                    SecondaryButton("+", Modifier.width(56.dp).heightIn(min = 56.dp)) { amount = step(amount, 0.1, jarGrams) }
                }
                AccentButton("Verbrauch buchen", enabled = amount > 0 && amount <= jarGrams + 0.001) {
                    val booked = jsRound(amount)
                    val empty = jarGrams - booked <= 0.05
                    run("$jarId: ${grams(booked)} gebucht${if (empty) ". Glas leer, zu reinigen" else ""}") { Actions.consume(jar, booked) }
                }
                SecondaryButton("Rest vollständig verbrauchen") { run("$jarId: Rest verbraucht, zu reinigen") { Actions.consume(jar, jarGrams) } }
            }
        }
        if (status == "cleaning") PrimaryButton("Als gereinigt markieren") { run("$jarId gereinigt, wieder frei") { Actions.clean(jar) } }
        if (status == "free") Text("Dieses Glas ist frei. Es wird beim Aufteilen einer Packung befüllt.", style = Kaffee.type.body, color = c.inkMuted)
        if (lot != null && (size == "L" || (size == "M" && lot["ruleProfile"] == "A")) && status in setOf("frozen", "active")) {
            if (!transfer) SecondaryButton("Umfüllen") { transfer = true }
            else TransferBox(inv, jar, { events -> onBook(events, "$jarId umgefüllt") }) { error = it }
        }
        Text("Glasverlauf", style = Kaffee.type.title, color = c.ink, modifier = Modifier.padding(top = 10.dp))
        inv.document.objs("events").filter { jarId in (it["resourceIds"] as List<*>) }.sortedByDescending { it["effectiveAt"] as String }.forEach { event ->
            Row(Modifier.fillMaxWidth()) {
                Text(formatDate(event["effectiveAt"] as String), style = Kaffee.type.small, color = c.inkMuted, modifier = Modifier.weight(0.4f))
                Text(EVENT_LABEL[event["type"] as String] ?: event["type"] as String, style = Kaffee.type.small, color = c.ink, modifier = Modifier.weight(0.6f))
            }
        }
    }
}

@Composable
private fun TransferBox(inv: Inventory, jar: Obj, onAppend: (List<Obj>) -> Unit, onError: (String?) -> Unit) {
    val jarId = jar["id"] as String
    val c = Kaffee.colors
    var consumeFirst by remember(jarId) { mutableStateOf(true) }
    var draft by remember(jarId) { mutableStateOf<TransferDraft?>(null) }
    var reason by remember(jarId) { mutableStateOf("") }
    val shape = RoundedCornerShape(Kaffee.spacing.radiusMd)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.surface200).border(1.dp, c.line, shape).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (jar["size"] == "L") "In M-Gläser umfüllen" else "In S-Gläser umfüllen", style = Kaffee.type.bodyStrong, color = c.ink)
        if (jar["size"] == "M") CheckRow("Eine Portion sofort verbrauchen", consumeFirst) { consumeFirst = it; draft = null }
        val current = draft
        if (current == null) SecondaryButton("Umfüllvorschlag") { draft = transferDraft(inv.state, jarId, consumeFirst) }
        else {
            current.targets.forEach { Text("${it.jarId} · ${grams(it.grams)} · ${if (it.frozen) "tiefgekühlt" else "aktiv"}", style = Kaffee.type.small, color = c.ink) }
            current.warnings.forEach { Text(it, style = Kaffee.type.small, color = c.activeFg) }
            if (current.warnings.isNotEmpty()) TextInput("Pflichtbegründung", reason, placeholder = "Warum wird von der Regel abgewichen?") { reason = it }
            PrimaryButton("Umfüllung buchen") {
                try { onAppend(Actions.transfer(inv.state, jarId, current, reason)); draft = null } catch (e: DomainError) { onError(e.message) }
            }
        }
    }
}

@Composable
fun CorrectionDialog(event: Obj, onClose: () -> Unit, onSave: (List<Obj>) -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onClose, containerColor = Kaffee.colors.surface100,
        title = { Text("Fehler buchen", style = Kaffee.type.title) },
        text = {
            Column {
                Text("${EVENT_LABEL[event["type"] as String] ?: event["type"]} vom ${formatDateTime(event["effectiveAt"] as String)} wird durch eine Gegenbuchung aufgehoben.",
                    style = Kaffee.type.small, color = Kaffee.colors.inkMuted)
                Spacer(Modifier.height(8.dp))
                TextInput("Warum ist diese Buchung fehlerhaft?", reason) { reason = it }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(Actions.correction(event, reason)) }, enabled = reason.isNotBlank()) { Text("Gegenbuchung speichern") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Abbrechen") } })
}

@Composable
fun PairingSheet(onClose: () -> Unit, onPair: (String, String, (String?) -> Unit) -> Unit) {
    var url by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Sheet("Mit TESS koppeln", "Auf dem Pi: ./tessctl kaffee-pair – der Code gilt 10 Minuten.", onClose) {
        TextInput("Server-Adresse (TESS_PUBLIC_URL)", url, placeholder = "https://…") { url = it }
        TextInput("Kopplungscode", code) { code = it }
        Text("Der Geräteschlüssel entsteht im Android Keystore und verlässt das Handy nie.", style = Kaffee.type.small, color = Kaffee.colors.inkMuted)
        error?.let { ErrorText(it) }
        PrimaryButton(if (busy) "Bitte warten …" else "Koppeln", enabled = !busy && url.isNotBlank() && code.isNotBlank()) {
            busy = true; error = null
            onPair(url, code) { failure -> busy = false; if (failure == null) onClose() else error = failure }
        }
        SecondaryButton("Abbrechen", onClick = onClose)
    }
}
