package com.vrcx.android.ui.screen.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.vrcx.android.ui.components.VrcxDetailTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(onBack: () -> Unit = {}) {
    Column(Modifier.fillMaxSize()) {
        VrcxDetailTopBar(title = "Gallery", onBack = onBack)
        Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("Gallery", style = MaterialTheme.typography.titleLarge)
            Text("VRC+ Gallery, Prints, Emojis, Stickers & Inventory", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
