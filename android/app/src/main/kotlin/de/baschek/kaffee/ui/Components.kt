package de.baschek.kaffee.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.baschek.kaffee.core.domain.Obj
import de.baschek.kaffee.core.domain.germanNumber
import de.baschek.kaffee.ui.theme.Kaffee
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

@Composable
fun PageHeading(eyebrow: String, title: String, lead: String? = null) {
    Column(Modifier.padding(bottom = Kaffee.spacing.gap)) {
        Eyebrow(eyebrow)
        Text(title, style = Kaffee.type.display, color = Kaffee.colors.text)
        if (lead != null) Text(lead, style = Kaffee.type.body, color = Kaffee.colors.muted, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
fun Eyebrow(text: String) = Text(text.uppercase(), style = Kaffee.type.eyebrow, color = Kaffee.colors.eyebrow, modifier = Modifier.padding(bottom = 6.dp))

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Kaffee.spacing.radius)).background(Kaffee.colors.cream)
            .border(1.dp, Kaffee.colors.line, RoundedCornerShape(Kaffee.spacing.radius)).padding(Kaffee.spacing.panelPadding),
        content = content,
    )
}

@Composable
fun PanelHead(eyebrow: String, title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = Kaffee.spacing.small), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Eyebrow(eyebrow)
            Text(title, style = Kaffee.type.title, color = Kaffee.colors.text)
        }
        if (action != null) TextButton(onClick = onAction) { Text(action, color = Kaffee.colors.link, fontWeight = FontWeight.Bold) }
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, modifier.fillMaxWidth(), enabled = enabled, shape = RoundedCornerShape(Kaffee.spacing.radiusSmall),
        colors = ButtonDefaults.buttonColors(containerColor = Kaffee.colors.brown, contentColor = Kaffee.colors.onDark)) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick, modifier.fillMaxWidth(), enabled = enabled, shape = RoundedCornerShape(Kaffee.spacing.radiusSmall),
        border = BorderStroke(1.dp, Kaffee.colors.line),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Kaffee.colors.card, contentColor = Kaffee.colors.text)) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun MetricTile(label: String, value: Int, detail: String, background: Color, foreground: Color, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(Kaffee.spacing.radius)).background(background).padding(16.dp).height(110.dp)) {
        Text(label, style = Kaffee.type.label, color = foreground)
        Spacer(Modifier.weight(1f))
        Text(value.toString(), style = Kaffee.type.metric, color = foreground)
        Text(detail, style = Kaffee.type.small, color = foreground.copy(alpha = 0.75f))
    }
}

@Composable
fun jarColors(status: String): Pair<Color, Color> = when (status) {
    "frozen" -> Kaffee.colors.jarFrozen to Kaffee.colors.jarFrozenBorder
    "active" -> Kaffee.colors.jarActive to Kaffee.colors.jarActiveBorder
    "cleaning" -> Kaffee.colors.jarCleaning to Kaffee.colors.jarCleaning
    else -> Kaffee.colors.jarFree to Kaffee.colors.line
}

@Composable
fun JarTile(jar: Obj, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    val (fill, border) = jarColors(jar["status"] as String)
    val g = (jar["grams"] as Double)
    Column(
        modifier.height(Kaffee.spacing.jarTile).clip(RoundedCornerShape(8.dp)).background(fill)
            .border(if (selected) 2.dp else 1.dp, if (selected) Kaffee.colors.link else border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(jar["id"] as String, style = Kaffee.type.label,
            color = if (jar["status"] == "cleaning") Kaffee.colors.jarCleaningText else Kaffee.colors.text)
        if (g > 0) Text(grams(g), style = Kaffee.type.tiny, color = Kaffee.colors.muted)
    }
}

@Composable
fun MethodBadge(brewMethod: String) {
    Box(Modifier.size(32.dp).clip(CircleShape).background(if (brewMethod == "espresso") Kaffee.colors.espresso else Kaffee.colors.filter),
        contentAlignment = Alignment.Center) {
        Text(if (brewMethod == "espresso") "E" else "F", color = Kaffee.colors.onDark, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
fun Notice(title: String, lines: List<String>, warning: Boolean = true, content: @Composable ColumnScope.() -> Unit = {}) {
    val bg = if (warning) Kaffee.colors.warningBackground else Kaffee.colors.successBackground
    val fg = if (warning) Kaffee.colors.warningText else Kaffee.colors.successText
    Column(Modifier.fillMaxWidth().padding(top = Kaffee.spacing.gap).clip(RoundedCornerShape(11.dp)).background(bg).padding(14.dp)) {
        Text(title, fontWeight = FontWeight.Bold, color = fg, style = Kaffee.type.body)
        lines.forEach { Text(it, color = fg, style = Kaffee.type.small) }
        content()
    }
}

@Composable
fun ErrorText(text: String) = Text(text, color = Kaffee.colors.error, style = Kaffee.type.small,
    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(8.dp)).background(Kaffee.colors.errorBackground).padding(10.dp))

@Composable
fun Empty(title: String, text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("○", style = Kaffee.type.title, color = Kaffee.colors.gold)
        Text(title, fontWeight = FontWeight.Bold, color = Kaffee.colors.text)
        Text(text, style = Kaffee.type.small, color = Kaffee.colors.muted)
        if (action != null) TextButton(onClick = onAction) { Text("$action →", color = Kaffee.colors.link) }
    }
}

@Composable
fun NumberField(label: String, value: Double, modifier: Modifier = Modifier, onValue: (Double) -> Unit) {
    var text by remember { mutableStateOf(germanNumber(value)) }
    // Follow outside changes (e.g. a new default) without fighting the user's typing ("18," stays "18,").
    LaunchedEffect(value) { if (text.replace(',', '.').toDoubleOrNull() != value) text = germanNumber(value) }
    OutlinedTextField(text, { new ->
        text = new
        new.replace(',', '.').toDoubleOrNull()?.let(onValue)
    }, modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

@Composable
fun TextInput(label: String, value: String, modifier: Modifier = Modifier, placeholder: String? = null, onValue: (String) -> Unit) {
    OutlinedTextField(value, onValue, modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        placeholder = if (placeholder != null) { { Text(placeholder) } } else null)
}

@Composable
fun <T> Select(label: String, options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(options.firstOrNull { it.first == selected }?.second ?: "", {}, Modifier.fillMaxWidth(), readOnly = true,
            label = { Text(label) }, singleLine = true, enabled = false,
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(disabledTextColor = Kaffee.colors.text,
                disabledLabelColor = Kaffee.colors.muted, disabledBorderColor = Kaffee.colors.line))
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(open, { open = false }) {
            options.forEach { (value, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); open = false }) }
        }
    }
}

@Composable
fun CheckRow(text: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChecked(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChecked)
        Text(text, style = Kaffee.type.small, color = Kaffee.colors.text)
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, color = Kaffee.colors.muted, style = Kaffee.type.small, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Bold, style = Kaffee.type.small, color = Kaffee.colors.text)
    }
}
