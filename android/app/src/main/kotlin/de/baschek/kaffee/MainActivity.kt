package de.baschek.kaffee

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import de.baschek.kaffee.ui.AppRoot
import de.baschek.kaffee.ui.theme.KaffeeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent { KaffeeTheme { AppRoot() } }
        // Sync whenever the app comes to the foreground.
        val container = (application as KaffeeApplication).container
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { container.scheduler.requestSync() }
        }
    }
}
