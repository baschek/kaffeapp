package de.baschek.kaffee.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.baschek.kaffee.R

/**
 * ALL visual tokens of the app in one place (design system "Kaffee-Bestand": project/tokens.json, palettes.md).
 * Screens never hard-code a color, font or size. Palette, glass style and mode are chosen in the settings screen and
 * stored locally (data/AppearanceStore.kt); nothing of it is synced or journaled.
 */
enum class PaletteId(val label: String, val hint: String) {
    SAND("Sand", "Warm, wie die bisherige App"),
    FROST("Frost", "Kühles Blaugrün"),
    SCHIEFER("Schiefer", "Ruhiges Blaugrau"),
    MONO("Mono", "Fast farblos"),
}

enum class TileStyle(val label: String, val hint: String) {
    RUHIG("Ruhig", "Flache Töne, Status über Rand und Deckel"),
    FUELLSTAND("Füllstand", "Das Glas ist so voll wie sein Inhalt"),
}

enum class ThemeMode(val label: String) { LIGHT("Hell"), DARK("Dunkel") }

@Immutable
data class Appearance(
    val palette: PaletteId = PaletteId.SAND,
    val tileStyle: TileStyle = TileStyle.RUHIG,
    val mode: ThemeMode = ThemeMode.LIGHT,
)

@Immutable
data class KaffeeColors(
    val surface100: Color, val surface200: Color, val ink: Color, val inkMuted: Color, val line: Color,
    val brand: Color, val onBrand: Color, val accent: Color, val onAccent: Color,
    val sidebar: Color, val onSidebar: Color,
    val freeBg: Color, val freeFg: Color, val frozenBg: Color, val frozenFg: Color,
    val activeBg: Color, val activeFg: Color, val cleanBg: Color, val cleanFg: Color,
    val danger: Color, val dangerBg: Color, val success: Color, val successBg: Color,
    val dark: Boolean,
) {
    val sidebarDim: Color get() = onSidebar.copy(alpha = 0.72f).compositeOver(sidebar)
}

object Palettes {
    private val SAND_LIGHT = longArrayOf(0xF4F1EC, 0xFFFEFB, 0x26211B, 0x665E54, 0xDDD6CB, 0x6B5A48, 0xFFFFFF, 0xC9973A, 0x2A241D, 0xDCE6E8, 0x2D5459, 0xF0DDB0, 0x6B4D12, 0xE3DFD9, 0x4B443C)
    private val SAND_DARK = longArrayOf(0x16130F, 0x201B16, 0xEEE7DD, 0xB3A99C, 0x352E26, 0xD3BC9F, 0x1D1710, 0xE0B059, 0x100E0B, 0x1E3A3F, 0xA9D3D8, 0x4A3710, 0xF0D590, 0x2F2A24, 0xD2C9BD)
    private val FROST_LIGHT = longArrayOf(0xEEF1EE, 0xFFFFFF, 0x14212A, 0x55646B, 0xD0DADC, 0x2F6F73, 0xFFFFFF, 0xD49A37, 0x1B2A30, 0xCBE5E9, 0x12525A, 0xF6DFA5, 0x6B4A0E, 0xDCE3E5, 0x3F4C52)
    private val FROST_DARK = longArrayOf(0x0F1518, 0x172025, 0xE8EFF0, 0xA3B2B6, 0x27353B, 0x8BBBC0, 0x0E1A1C, 0xE5B15A, 0x0C1013, 0x1B3C42, 0xA9D6DA, 0x4A3510, 0xF2CF86, 0x2A3439, 0xC7D2D5)
    private val SCHIEFER_LIGHT = longArrayOf(0xF1F2F4, 0xFFFFFF, 0x161E27, 0x5A6470, 0xD4D8DE, 0x3B4A5A, 0xFFFFFF, 0xD49A37, 0x1A222B, 0xDDE5EC, 0x2C4258, 0xEBE1CC, 0x5F4A1E, 0xE4E5E7, 0x464B52)
    private val SCHIEFER_DARK = longArrayOf(0x0F1318, 0x171D24, 0xE6EAEF, 0xA2ABB6, 0x262E38, 0x9DB4CC, 0x0E151C, 0xE5B15A, 0x0B0E12, 0x22334A, 0xB6CCE4, 0x453A1C, 0xEBD28D, 0x2A2F36, 0xC8CED6)
    private val MONO_LIGHT = longArrayOf(0xF4F4F3, 0xFFFFFF, 0x1A1F20, 0x5E6567, 0xD8DADA, 0x1F2D2E, 0xFFFFFF, 0xD49A37, 0x1A2021, 0xE6EBEB, 0x27474A, 0xF1E8CD, 0x5C4614, 0xE8E8E7, 0x4A4F50)
    private val MONO_DARK = longArrayOf(0x101212, 0x191C1D, 0xECEFEF, 0xA7AEB0, 0x282D2E, 0xCFE0E1, 0x101A1B, 0xE5B15A, 0x0B0D0D, 0x223234, 0xB5D1D3, 0x43391C, 0xEAD493, 0x2B2E2F, 0xCBD0D1)

    private fun c(hex: Long) = Color(0xFF000000L or hex)

    private fun table(id: PaletteId, dark: Boolean): LongArray = when (id) {
        PaletteId.SAND -> if (dark) SAND_DARK else SAND_LIGHT
        PaletteId.FROST -> if (dark) FROST_DARK else FROST_LIGHT
        PaletteId.SCHIEFER -> if (dark) SCHIEFER_DARK else SCHIEFER_LIGHT
        PaletteId.MONO -> if (dark) MONO_DARK else MONO_LIGHT
    }

    /** Order of the table: surface-100, surface-200, ink, ink-muted, line, brand, on-brand, accent, sidebar, frozen bg/fg, active bg/fg, clean bg/fg. */
    fun colors(id: PaletteId, dark: Boolean): KaffeeColors {
        val t = table(id, dark)
        val surface200 = c(t[1])
        val danger = if (dark) Color(0xFFF0907F) else Color(0xFFA33F2F)
        val success = if (dark) Color(0xFF8FD09A) else Color(0xFF2F6B3A)
        return KaffeeColors(
            surface100 = c(t[0]), surface200 = surface200, ink = c(t[2]), inkMuted = c(t[3]), line = c(t[4]),
            brand = c(t[5]), onBrand = c(t[6]), accent = c(t[7]), onAccent = Color(0xFF211710),
            sidebar = c(t[8]), onSidebar = Color(0xFFF3ECE2),
            freeBg = surface200, freeFg = c(t[3]), frozenBg = c(t[9]), frozenFg = c(t[10]),
            activeBg = c(t[11]), activeFg = c(t[12]), cleanBg = c(t[13]), cleanFg = c(t[14]),
            danger = danger, dangerBg = danger.copy(alpha = 0.12f).compositeOver(surface200),
            success = success, successBg = success.copy(alpha = 0.14f).compositeOver(surface200),
            dark = dark,
        )
    }
}

val Fraunces = FontFamily(Font(R.font.fraunces_bold, FontWeight.Bold))
val DmSans = FontFamily(
    Font(R.font.dm_sans_regular, FontWeight.Normal),
    Font(R.font.dm_sans_medium, FontWeight.Medium),
    Font(R.font.dm_sans_bold, FontWeight.Bold),
)

/** Type scale from tokens.json: Fraunces 700 for titles and numbers, DM Sans for text, small caps-style eyebrow. */
@Immutable
data class KaffeeType(
    val display: TextStyle = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 32.sp, letterSpacing = (-1.2).sp),
    val sheetTitle: TextStyle = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 28.sp, letterSpacing = (-0.8).sp),
    val title: TextStyle = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 24.sp, letterSpacing = (-0.4).sp),
    val metric: TextStyle = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.Bold, fontSize = 38.sp, lineHeight = 38.sp, letterSpacing = (-0.6).sp),
    val eyebrow: TextStyle = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Bold, fontSize = 10.5.sp, lineHeight = 14.sp, letterSpacing = 1.5.sp),
    val body: TextStyle = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Normal, fontSize = 13.5.sp, lineHeight = 20.sp),
    val bodyStrong: TextStyle = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp),
    val label: TextStyle = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 16.sp),
    val small: TextStyle = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Medium, fontSize = 11.5.sp, lineHeight = 16.sp),
    val tiny: TextStyle = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 12.sp),
)

@Immutable
data class KaffeeSpacing(
    val page: Dp = 16.dp,
    val gap: Dp = 12.dp,
    val small: Dp = 8.dp,
    val tiny: Dp = 4.dp,
    val panelPadding: Dp = 16.dp,
    val radiusSm: Dp = 10.dp,
    val radiusMd: Dp = 12.dp,
    val radiusLg: Dp = 16.dp,
    val tapMin: Dp = 48.dp,
)

val LocalColors = staticCompositionLocalOf { Palettes.colors(PaletteId.SAND, false) }
val LocalType = staticCompositionLocalOf { KaffeeType() }
val LocalSpacing = staticCompositionLocalOf { KaffeeSpacing() }
val LocalTileStyle = staticCompositionLocalOf { TileStyle.RUHIG }

object Kaffee {
    val colors: KaffeeColors @Composable get() = LocalColors.current
    val type: KaffeeType @Composable get() = LocalType.current
    val spacing: KaffeeSpacing @Composable get() = LocalSpacing.current
    val tileStyle: TileStyle @Composable get() = LocalTileStyle.current
}

private fun Typography.withFont(f: FontFamily) = copy(
    displayLarge = displayLarge.copy(fontFamily = f), displayMedium = displayMedium.copy(fontFamily = f), displaySmall = displaySmall.copy(fontFamily = f),
    headlineLarge = headlineLarge.copy(fontFamily = f), headlineMedium = headlineMedium.copy(fontFamily = f), headlineSmall = headlineSmall.copy(fontFamily = f),
    titleLarge = titleLarge.copy(fontFamily = f), titleMedium = titleMedium.copy(fontFamily = f), titleSmall = titleSmall.copy(fontFamily = f),
    bodyLarge = bodyLarge.copy(fontFamily = f), bodyMedium = bodyMedium.copy(fontFamily = f), bodySmall = bodySmall.copy(fontFamily = f),
    labelLarge = labelLarge.copy(fontFamily = f), labelMedium = labelMedium.copy(fontFamily = f), labelSmall = labelSmall.copy(fontFamily = f),
)

private fun scheme(c: KaffeeColors): ColorScheme =
    if (c.dark) darkColorScheme(
        primary = c.brand, onPrimary = c.onBrand, secondary = c.accent, onSecondary = c.onAccent, background = c.surface100, onBackground = c.ink,
        surface = c.surface200, onSurface = c.ink, surfaceVariant = c.surface100, onSurfaceVariant = c.inkMuted, outline = c.line, outlineVariant = c.line,
        error = c.danger, surfaceContainerLowest = c.surface100, surfaceContainerLow = c.surface200, surfaceContainer = c.surface200,
        surfaceContainerHigh = c.surface200, surfaceContainerHighest = c.surface200,
    ) else lightColorScheme(
        primary = c.brand, onPrimary = c.onBrand, secondary = c.accent, onSecondary = c.onAccent, background = c.surface100, onBackground = c.ink,
        surface = c.surface200, onSurface = c.ink, surfaceVariant = c.surface100, onSurfaceVariant = c.inkMuted, outline = c.line, outlineVariant = c.line,
        error = c.danger, surfaceContainerLowest = c.surface100, surfaceContainerLow = c.surface200, surfaceContainer = c.surface200,
        surfaceContainerHigh = c.surface200, surfaceContainerHighest = c.surface200,
    )

@Composable
fun KaffeeTheme(appearance: Appearance = Appearance(), content: @Composable () -> Unit) {
    val colors = Palettes.colors(appearance.palette, appearance.mode == ThemeMode.DARK)
    val type = KaffeeType()
    CompositionLocalProvider(
        LocalColors provides colors, LocalType provides type, LocalSpacing provides KaffeeSpacing(), LocalTileStyle provides appearance.tileStyle,
    ) {
        MaterialTheme(colorScheme = scheme(colors), typography = Typography().withFont(DmSans)) {
            ProvideTextStyle(type.body.copy(color = colors.ink), content)
        }
    }
}
