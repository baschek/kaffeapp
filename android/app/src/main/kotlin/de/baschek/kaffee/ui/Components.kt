package de.baschek.kaffee.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.baschek.kaffee.core.domain.Obj
import de.baschek.kaffee.core.domain.germanNumber
import de.baschek.kaffee.core.domain.portionCapacity
import de.baschek.kaffee.data.Inventory
import de.baschek.kaffee.ui.theme.Fraunces
import de.baschek.kaffee.ui.theme.Kaffee
import de.baschek.kaffee.ui.theme.TileStyle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val STATUS_LABEL = mapOf("free" to "Frei", "planned" to "Geplant", "frozen" to "Tiefgekühlt", "active" to "Aktiv", "cleaning" to "Zu reinigen")
val PROFILE_LABEL = mapOf("A" to "A · M und S", "B" to "B · L und M", "DIRECT_S" to "Direkt in S", "MANUAL" to "Manuell")

fun grams(value: Double): String = "${germanNumber(value)} g"

private val dateTime = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm").withZone(ZoneId.systemDefault())
private val dateOnly = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.systemDefault())
fun formatDateTime(iso: String?): String = iso?.let { runCatching { dateTime.format(Instant.parse(it)) }.getOrNull() } ?: "—"
fun formatDate(iso: String?): String = iso?.let { runCatching { dateOnly.format(Instant.parse(it)) }.getOrNull() } ?: "—"

// ---------------------------------------------------------------- headings and panels

@Composable
fun PageHeading(eyebrow: String, title: String, lead: String? = null) {
    Column(Modifier.padding(bottom = Kaffee.spacing.gap)) {
        Eyebrow(eyebrow)
        Text(title, style = Kaffee.type.display, color = Kaffee.colors.ink)
        if (lead != null) Text(lead, style = Kaffee.type.body, color = Kaffee.colors.inkMuted, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), style = Kaffee.type.eyebrow, color = Kaffee.colors.brand, modifier = modifier.padding(bottom = 4.dp))

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(Kaffee.spacing.radiusLg)
    Column(
        modifier.fillMaxWidth().clip(shape).background(Kaffee.colors.surface200).border(1.dp, Kaffee.colors.line, shape).padding(Kaffee.spacing.panelPadding),
        content = content,
    )
}

@Composable
fun PanelHead(eyebrow: String, title: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Eyebrow(eyebrow)
                Text(title, style = Kaffee.type.title, color = Kaffee.colors.ink)
            }
            if (action != null) TextLink(action, onAction)
        }
        HorizontalDivider(color = Kaffee.colors.line, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
fun TextLink(text: String, onClick: () -> Unit) =
    TextButton(onClick, modifier = Modifier.heightIn(min = Kaffee.spacing.tapMin)) {
        Text(text, color = Kaffee.colors.brand, style = Kaffee.type.label)
    }

// ---------------------------------------------------------------- buttons (at least 48 dp tall)

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, modifier.fillMaxWidth().heightIn(min = Kaffee.spacing.tapMin), enabled = enabled, shape = RoundedCornerShape(Kaffee.spacing.radiusMd),
        colors = ButtonDefaults.buttonColors(containerColor = Kaffee.colors.brand, contentColor = Kaffee.colors.onBrand,
            disabledContainerColor = Kaffee.colors.brand.copy(alpha = 0.4f), disabledContentColor = Kaffee.colors.onBrand.copy(alpha = 0.7f))) {
        Text(text, style = Kaffee.type.bodyStrong)
    }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick, modifier.fillMaxWidth().heightIn(min = Kaffee.spacing.tapMin), enabled = enabled, shape = RoundedCornerShape(Kaffee.spacing.radiusMd),
        border = BorderStroke(1.5.dp, Kaffee.colors.line),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Kaffee.colors.surface200, contentColor = Kaffee.colors.ink)) {
        Text(text, style = Kaffee.type.bodyStrong)
    }
}

/** The booking button: gold surface with a shadow, used once per screen ("Verbrauch buchen"). */
@Composable
fun AccentButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, modifier.fillMaxWidth().heightIn(min = Kaffee.spacing.tapMin), enabled = enabled, shape = RoundedCornerShape(Kaffee.spacing.radiusMd),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Kaffee.colors.accent, contentColor = Kaffee.colors.onAccent,
            disabledContainerColor = Kaffee.colors.accent.copy(alpha = 0.4f), disabledContentColor = Kaffee.colors.onAccent.copy(alpha = 0.7f))) {
        Text(text, style = Kaffee.type.bodyStrong)
    }
}

// ---------------------------------------------------------------- glass status

/** Skin of a glass status: background, foreground, and how its border is drawn (never color alone). */
data class StatusSkin(val bg: Color, val fg: Color, val word: String)

@Composable
fun statusSkin(status: String): StatusSkin {
    val c = Kaffee.colors
    return when (status) {
        "frozen" -> StatusSkin(c.frozenBg, c.frozenFg, "Tiefgekühlt")
        "active" -> StatusSkin(c.activeBg, c.activeFg, "Aktiv")
        "cleaning" -> StatusSkin(c.cleanBg, c.cleanFg, "Zu reinigen")
        else -> StatusSkin(c.freeBg, c.freeFg, if (status == "planned") "Geplant" else "Frei")
    }
}

enum class BorderKind { NONE, SOLID, DASHED, DOTTED }

/** Draws a rounded border that can be dashed (free) or dotted (to clean); Compose's border() only does solid lines. */
fun Modifier.statusBorder(kind: BorderKind, color: Color, width: Dp, radius: Dp): Modifier = if (kind == BorderKind.NONE) this else drawBehind {
    val w = width.toPx()
    val effect = when (kind) {
        BorderKind.DASHED -> PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
        BorderKind.DOTTED -> PathEffect.dashPathEffect(floatArrayOf(0.01f, w * 2.2f))
        else -> null
    }
    drawRoundRect(color, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), cornerRadius = CornerRadius(radius.toPx() - w / 2),
        style = Stroke(width = w, cap = if (kind == BorderKind.DOTTED) StrokeCap.Round else StrokeCap.Butt, pathEffect = effect))
}

/** Border of a tile for a status and glass style: free dashed, active thick, to clean dotted, frozen only by its fill. */
@Composable
private fun tileBorder(status: String, style: TileStyle): Triple<BorderKind, Color, Dp> {
    val c = Kaffee.colors
    val skin = statusSkin(status)
    return if (style == TileStyle.RUHIG) when (status) {
        "frozen" -> Triple(BorderKind.NONE, Color.Transparent, 0.dp)
        "active" -> Triple(BorderKind.SOLID, skin.fg, 2.dp)
        "cleaning" -> Triple(BorderKind.DOTTED, skin.fg, 1.5.dp)
        else -> Triple(BorderKind.DASHED, skin.fg, 1.5.dp)
    } else when (status) {
        "free", "planned" -> Triple(BorderKind.DASHED, c.inkMuted, 1.5.dp)
        else -> Triple(BorderKind.SOLID, c.line, 1.5.dp)
    }
}

/** Glass symbol: lid (tilted open when active), dashed outline when free, optional fill level (0 to 1). */
@Composable
fun JarIcon(status: String, color: Color, modifier: Modifier = Modifier.size(width = 20.dp, height = 24.dp), fill: Float = 0f) {
    Canvas(modifier) {
        scale(scaleX = size.width / 20f, scaleY = size.height / 24f, pivot = Offset.Zero) {
            val body = Path().apply {
                moveTo(4f, 5f); lineTo(16f, 5f); lineTo(16f, 19f)
                cubicTo(16f, 20.66f, 14.66f, 22f, 13f, 22f); lineTo(7f, 22f)
                cubicTo(5.34f, 22f, 4f, 20.66f, 4f, 19f); close()
            }
            val free = status == "free" || status == "planned"
            drawPath(body, color, style = Stroke(width = 1.8f, pathEffect = if (free) PathEffect.dashPathEffect(floatArrayOf(3f, 2.4f)) else null))
            if (fill > 0f) {
                val h = 15f * fill.coerceIn(0f, 1f)
                drawRoundRect(color.copy(alpha = 0.45f), topLeft = Offset(5f, 21f - h), size = Size(10f, h), cornerRadius = CornerRadius(1.5f))
            }
            if (status == "active") {
                rotate(-24f, pivot = Offset(7f, 2f)) { drawRoundRect(color, topLeft = Offset(7f, 0.5f), size = Size(10f, 3f), cornerRadius = CornerRadius(1f)) }
            } else drawRoundRect(color, topLeft = Offset(5f, 1.5f), size = Size(10f, 3f), cornerRadius = CornerRadius(1f))
        }
    }
}

/** How full a glass is: contents divided by the capacity of its lot (portions per size times portion grams). */
fun fillRatio(inv: Inventory, jar: Obj): Float {
    val status = jar["status"] as String
    val g = jar["grams"] as Double
    if (g <= 0 || (status != "frozen" && status != "active")) return 0f
    val lot = inv.state.lot(jar["lotId"] as? String) ?: return 1f
    val capacity = portionCapacity(jar["size"] as String, lot["brewMethod"] as String) * portion(lot)
    return if (capacity > 0) (g / capacity).toFloat().coerceIn(0.08f, 1f) else 1f
}

/** Glass tile: symbol, number, grams. Tapping opens the glass detail (the only way to book or open a glass). */
@Composable
fun JarTile(jar: Obj, fill: Float, modifier: Modifier = Modifier, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    val c = Kaffee.colors
    val style = Kaffee.tileStyle
    val status = jar["status"] as String
    val skin = statusSkin(status)
    val g = jar["grams"] as Double
    val (kind, borderColor, borderWidth) = tileBorder(status, style)
    val shape = RoundedCornerShape(Kaffee.spacing.radiusSm)
    val ruhig = style == TileStyle.RUHIG
    val label = "Glas ${jar["id"]}, ${skin.word}${if (g > 0) ", ${grams(g)}" else ""}"
    val text = if (status == "cleaning") "leer" else if (g > 0) grams(g) else "frei"
    Column(
        modifier.heightIn(min = 70.dp).clip(shape).background(if (ruhig) skin.bg else c.surface200)
            .statusBorder(kind, borderColor, borderWidth, Kaffee.spacing.radiusSm)
            .then(if (selected) Modifier.border(2.dp, c.brand, shape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = 2.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        JarIcon(status, if (ruhig) skin.fg else skin.fg.takeUnless { status == "free" || status == "planned" } ?: c.inkMuted, fill = if (ruhig) 0f else fill)
        Text(jar["id"] as String, style = Kaffee.type.label, color = if (ruhig) skin.fg else c.ink)
        Text(text, style = Kaffee.type.tiny, color = if (ruhig) skin.fg else c.inkMuted)
    }
}

/** Status pill with symbol and word. */
@Composable
fun StatusBadge(status: String) {
    val skin = statusSkin(status)
    val kind = if (status == "free" || status == "planned") BorderKind.DASHED else BorderKind.SOLID
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(skin.bg).statusBorder(kind, skin.fg, 1.5.dp, 100.dp).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        JarIcon(status, skin.fg, Modifier.size(width = 14.dp, height = 17.dp))
        Text(skin.word, style = Kaffee.type.label, color = skin.fg)
    }
}

/** Legend: a small tile symbol plus the word for each status. */
@Composable
fun Legend() {
    val c = Kaffee.colors
    val style = Kaffee.tileStyle
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf("free" to 0f, "frozen" to 1f, "active" to 0.45f, "cleaning" to 0f).forEach { (status, fill) ->
            val skin = statusSkin(status)
            val (kind, borderColor, borderWidth) = tileBorder(status, style)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(width = 24.dp, height = 26.dp).clip(RoundedCornerShape(6.dp)).background(if (style == TileStyle.RUHIG) skin.bg else c.surface200)
                        .statusBorder(kind, borderColor, borderWidth, 6.dp),
                    contentAlignment = Alignment.Center,
                ) { JarIcon(status, if (style == TileStyle.RUHIG) skin.fg else if (status == "free") c.inkMuted else skin.fg, Modifier.size(width = 13.dp, height = 16.dp), if (style == TileStyle.FUELLSTAND) fill else 0f) }
                Text(" ${skin.word}", style = Kaffee.type.small, color = c.inkMuted)
            }
        }
    }
}

/** Key figure: Fraunces number, label with glass symbol, extra line. Status is in the symbol, the word and the border (free dashed). */
@Composable
fun MetricTile(label: String, value: Int, detail: String, status: String?, modifier: Modifier = Modifier) {
    val c = Kaffee.colors
    val shape = RoundedCornerShape(Kaffee.spacing.radiusLg)
    val skin = if (status != null) statusSkin(status) else StatusSkin(c.surface200, c.ink, "")
    val kind = when (status) { "free" -> BorderKind.DASHED; null -> BorderKind.SOLID; else -> BorderKind.NONE }
    val borderColor = if (status == null) c.line else skin.fg
    val fg = if (status == "free" || status == null) c.ink else skin.fg
    Column(modifier.heightIn(min = 118.dp).clip(shape).background(skin.bg).statusBorder(kind, borderColor, 1.5.dp, 16.dp).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            JarIcon(status ?: "frozen", fg, Modifier.size(width = 14.dp, height = 17.dp))
            Text(label, style = Kaffee.type.label, color = fg)
        }
        Spacer(Modifier.weight(1f))
        Text(value.toString(), style = Kaffee.type.metric, color = fg)
        Text(detail, style = Kaffee.type.small, color = fg.copy(alpha = 0.9f), modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
fun MethodBadge(brewMethod: String) {
    val c = Kaffee.colors
    val espresso = brewMethod == "espresso"
    Box(Modifier.size(32.dp).clip(CircleShape).background(if (espresso) c.brand else c.frozenFg), contentAlignment = Alignment.Center) {
        Text(if (espresso) "E" else "F", color = if (espresso) c.onBrand else c.frozenBg, style = Kaffee.type.label)
    }
}

// ---------------------------------------------------------------- notices and inputs

@Composable
fun Notice(title: String, lines: List<String>, warning: Boolean = true, content: @Composable ColumnScope.() -> Unit = {}) {
    val c = Kaffee.colors
    val bg = if (warning) c.activeBg else c.successBg
    val fg = if (warning) c.activeFg else c.success
    Column(Modifier.fillMaxWidth().padding(top = Kaffee.spacing.gap).clip(RoundedCornerShape(Kaffee.spacing.radiusMd)).background(bg).padding(14.dp)) {
        Text(title, color = fg, style = Kaffee.type.bodyStrong)
        lines.forEach { Text(it, color = fg, style = Kaffee.type.small) }
        content()
    }
}

@Composable
fun ErrorText(text: String) = Text(text, color = Kaffee.colors.danger, style = Kaffee.type.small,
    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(Kaffee.spacing.radiusSm)).background(Kaffee.colors.dangerBg).padding(10.dp))

@Composable
fun Empty(title: String, text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("○", style = Kaffee.type.title, color = Kaffee.colors.line)
        Text(title, style = Kaffee.type.bodyStrong, color = Kaffee.colors.ink)
        Text(text, style = Kaffee.type.small, color = Kaffee.colors.inkMuted)
        if (action != null) TextLink("$action →", onAction)
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Kaffee.colors.brand, unfocusedBorderColor = Kaffee.colors.line,
    focusedContainerColor = Kaffee.colors.surface200, unfocusedContainerColor = Kaffee.colors.surface200,
    focusedTextColor = Kaffee.colors.ink, unfocusedTextColor = Kaffee.colors.ink,
    focusedLabelColor = Kaffee.colors.brand, unfocusedLabelColor = Kaffee.colors.inkMuted, cursorColor = Kaffee.colors.brand,
)

@Composable
fun NumberField(label: String, value: Double, modifier: Modifier = Modifier, onValue: (Double) -> Unit) {
    var text by remember { mutableStateOf(germanNumber(value)) }
    // Follow outside changes (e.g. a new default) without fighting the user's typing ("18," stays "18,").
    LaunchedEffect(value) { if (text.replace(',', '.').toDoubleOrNull() != value) text = germanNumber(value) }
    OutlinedTextField(text, { new ->
        text = new
        new.replace(',', '.').toDoubleOrNull()?.let(onValue)
    }, modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, shape = RoundedCornerShape(Kaffee.spacing.radiusSm), colors = fieldColors(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

@Composable
fun TextInput(label: String, value: String, modifier: Modifier = Modifier, placeholder: String? = null, onValue: (String) -> Unit) {
    OutlinedTextField(value, onValue, modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        shape = RoundedCornerShape(Kaffee.spacing.radiusSm), colors = fieldColors(),
        placeholder = if (placeholder != null) { { Text(placeholder) } } else null)
}

@Composable
fun <T> Select(label: String, options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(options.firstOrNull { it.first == selected }?.second ?: "", {}, Modifier.fillMaxWidth(), readOnly = true,
            label = { Text(label) }, singleLine = true, enabled = false, shape = RoundedCornerShape(Kaffee.spacing.radiusSm),
            colors = OutlinedTextFieldDefaults.colors(disabledTextColor = Kaffee.colors.ink, disabledLabelColor = Kaffee.colors.inkMuted,
                disabledBorderColor = Kaffee.colors.line, disabledContainerColor = Kaffee.colors.surface200))
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(open, { open = false }) {
            options.forEach { (value, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); open = false }) }
        }
    }
}

@Composable
fun CheckRow(text: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = Kaffee.spacing.tapMin).clickable { onChecked(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChecked, colors = CheckboxDefaults.colors(checkedColor = Kaffee.colors.brand, checkmarkColor = Kaffee.colors.onBrand, uncheckedColor = Kaffee.colors.inkMuted))
        Text(text, style = Kaffee.type.small, color = Kaffee.colors.ink)
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, color = Kaffee.colors.inkMuted, style = Kaffee.type.small, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = Kaffee.type.label, color = Kaffee.colors.ink)
    }
}

/** One fact of the glass detail: small label above a bold value, in a bordered field. */
@Composable
fun FactBox(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(Kaffee.spacing.radiusMd)).background(Kaffee.colors.surface200)
        .border(1.dp, Kaffee.colors.line, RoundedCornerShape(Kaffee.spacing.radiusMd)).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(label, style = Kaffee.type.small, color = Kaffee.colors.inkMuted)
        Text(value, style = Kaffee.type.bodyStrong, color = Kaffee.colors.ink)
    }
}

/** Radio-style option card used in the settings (palette, glass style, mode). */
@Composable
fun OptionCard(selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(Kaffee.spacing.radiusMd)
    val c = Kaffee.colors
    Box(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape).background(c.surface100)
            .border(if (selected) 2.dp else 1.5.dp, if (selected) c.brand else c.line, shape)
            .clickable(onClick = onSelect).semantics(mergeDescendants = true) { }.padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) { content() }
}

@Composable
fun RadioDot(selected: Boolean) {
    val c = Kaffee.colors
    Box(Modifier.size(18.dp).border(2.dp, if (selected) c.brand else c.inkMuted, CircleShape), contentAlignment = Alignment.Center) {
        if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(c.brand))
    }
}

/** Heading text with the display serif at an arbitrary size, e.g. the big gram number of the stepper. */
@Composable
fun SerifNumber(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier = modifier, color = Kaffee.colors.ink, style = Kaffee.type.sheetTitle.copy(fontFamily = Fraunces, fontWeight = FontWeight.Bold))

