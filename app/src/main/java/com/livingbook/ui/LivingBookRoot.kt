package com.livingbook.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.livingbook.AppContainer
import com.livingbook.ui.theme.LivingBookTheme
import kotlinx.serialization.Serializable

@Serializable data object LibraryRoute

/** App entry composable: theme + navigation. */
@Composable
fun LivingBookRoot(container: AppContainer) {
    val loaded by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    // DataStore answers within a frame; waiting avoids flashing the wrong theme or text size.
    val settings = loaded ?: return
    LivingBookTheme(settings.theme) {
        Surface(Modifier.fillMaxSize()) {
            val nav = rememberNavController()
            NavHost(nav, startDestination = LibraryRoute) {
                composable<LibraryRoute> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Living Book") }
                }
            }
        }
    }
}
