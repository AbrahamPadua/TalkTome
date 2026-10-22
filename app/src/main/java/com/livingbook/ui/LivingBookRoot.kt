package com.livingbook.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.livingbook.AppContainer

/** App entry composable: theme + navigation. */
@Composable
fun LivingBookRoot(container: AppContainer) {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) { Text("Living Book") }
        }
    }
}
