package de.baschek.kaffee

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import de.baschek.kaffee.ui.AppRoot
import de.baschek.kaffee.ui.theme.KaffeeTheme
import de.baschek.kaffee.ui.theme.ThemeMode
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as KaffeeApplication).container
        setContent {
            val appearance by container.appearance.state.collectAsState()
            val dark = appearance.mode == ThemeMode.DARK
            // Status bar icons follow the chosen mode (not the system mode); the bottom bar is dark in every palette.
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                    navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                )
            }
            KaffeeTheme(appearance) { AppRoot() }
        }
        // Sync whenever the app comes to the foreground.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { container.scheduler.requestSync() }
        }
    }
}
