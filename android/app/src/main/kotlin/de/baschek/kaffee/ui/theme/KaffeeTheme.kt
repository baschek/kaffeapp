package de.baschek.kaffee.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ALL visual tokens of the app in one place (taken from the web app's src/styles.css). A later design pass only
 * changes this file: colors, typography, spacing and corner radii. Screens never hard-code a color or size.
 */
@Immutable
data class KaffeeColors(
    val background: Color = Color(0xFFEEE9E1),
    val brown: Color = Color(0xFF211710),
    val brownHover: Color = Color(0xFF3A291F),
    val coffee: Color = Color(0xFF6C3D26),
    val gold: Color = Color(0xFFD49A37),
    val cream: Color = Color(0xFFFAF7F2),
    val card: Color = Color(0xFFFFFFFF),
    val ice: Color = Color(0xFF8BBBC0),
    val line: Color = Color(0xFFDED6CB),
    val text: Color = Color(0xFF241B16),
    val muted: Color = Color(0xFF766C65),
    val eyebrow: Color = Color(0xFF8D6547),
    val link: Color = Color(0xFF7C4F32),
    val strip: Color = Color(0xFFE5DDD2),
    val error: Color = Color(0xFFA33F2F),
    val errorBackground: Color = Color(0xFFF6E2DD),
    val warningBackground: Color = Color(0xFFF5DFB4),
    val warningText: Color = Color(0xFF66491D),
    val successBackground: Color = Color(0xFFDCEBDC),
    val successText: Color = Color(0xFF315A35),
    // metric tiles
    val metricCoffee: Color = Color(0xFF5C3424),
    val metricIce: Color = Color(0xFFC8E2E2),
    val metricGold: Color = Color(0xFFF1CC78),
    val metricSlate: Color = Color(0xFFDAD7D1),
    // jar status
    val jarFree: Color = Color(0xFFFFFFFF),
    val jarFrozen: Color = Color(0xFFCDE3E4),
    val jarFrozenBorder: Color = Color(0xFF9FC5C8),
    val jarActive: Color = Color(0xFFF3D590),
    val jarActiveBorder: Color = Color(0xFFDCB561),
    val jarCleaning: Color = Color(0xFFD2CECA),
    val jarCleaningText: Color = Color(0xFF6C635D),
    // brew method badges
    val espresso: Color = Color(0xFF6A3824),
    val filter: Color = Color(0xFF497B7E),
    val onDark: Color = Color(0xFFFFFFFF),
    val navInactive: Color = Color(0xFFC8BAB1),
    val navActive: Color = Color(0xFFF0C766),
)

@Immutable
data class KaffeeType(
    val display: TextStyle = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 36.sp, letterSpacing = (-1).sp),
    val title: TextStyle = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 26.sp),
    val metric: TextStyle = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 40.sp),
    val eyebrow: TextStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.6.sp),
    val body: TextStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    val label: TextStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 13.sp),
    val small: TextStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    val tiny: TextStyle = TextStyle(fontSize = 10.sp),
)

@Immutable
data class KaffeeSpacing(
    val page: Dp = 16.dp,
    val gap: Dp = 12.dp,
    val small: Dp = 8.dp,
    val tiny: Dp = 4.dp,
    val panelPadding: Dp = 18.dp,
    val radius: Dp = 16.dp,
    val radiusSmall: Dp = 9.dp,
    val jarTile: Dp = 48.dp,
)

val LocalColors = staticCompositionLocalOf { KaffeeColors() }
val LocalType = staticCompositionLocalOf { KaffeeType() }
val LocalSpacing = staticCompositionLocalOf { KaffeeSpacing() }

object Kaffee {
    val colors: KaffeeColors @Composable get() = LocalColors.current
    val type: KaffeeType @Composable get() = LocalType.current
    val spacing: KaffeeSpacing @Composable get() = LocalSpacing.current
}

@Composable
fun KaffeeTheme(content: @Composable () -> Unit) {
    val colors = KaffeeColors()
    val scheme = lightColorScheme(
        primary = colors.brown, onPrimary = colors.onDark, secondary = colors.gold, background = colors.background,
        surface = colors.cream, onSurface = colors.text, onBackground = colors.text, error = colors.error, outline = colors.line,
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalColors provides colors, LocalType provides KaffeeType(), LocalSpacing provides KaffeeSpacing()) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
