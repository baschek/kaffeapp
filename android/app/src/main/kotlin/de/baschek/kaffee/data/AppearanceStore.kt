package de.baschek.kaffee.data

import android.content.Context
import de.baschek.kaffee.ui.theme.Appearance
import de.baschek.kaffee.ui.theme.PaletteId
import de.baschek.kaffee.ui.theme.ThemeMode
import de.baschek.kaffee.ui.theme.TileStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Palette, glass style and mode, stored only on this device (plain SharedPreferences: nothing secret, never synced,
 * never part of the journal). Before the first choice the mode follows the system setting; Sand and Ruhig are the defaults.
 */
class AppearanceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())

    val state: StateFlow<Appearance> = _state.asStateFlow()

    fun update(change: (Appearance) -> Appearance) {
        val next = change(_state.value)
        _state.value = next
        prefs.edit().putString(KEY_PALETTE, next.palette.name).putString(KEY_TILES, next.tileStyle.name).putString(KEY_MODE, next.mode.name).apply()
    }

    private fun read() = Appearance(
        palette = prefs.getString(KEY_PALETTE, null).toEnum(PaletteId.SAND),
        tileStyle = prefs.getString(KEY_TILES, null).toEnum(TileStyle.RUHIG),
        mode = prefs.getString(KEY_MODE, null).toEnum(ThemeMode.LIGHT),
    )

    private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
        enumValues<E>().firstOrNull { it.name == this } ?: default

    private companion object {
        const val KEY_PALETTE = "palette"
        const val KEY_TILES = "tileStyle"
        const val KEY_MODE = "mode"
    }
}
