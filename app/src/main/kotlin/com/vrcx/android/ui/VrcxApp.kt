package com.vrcx.android.ui

import android.app.Activity
import android.net.Uri
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.service.WebSocketForegroundService
import com.vrcx.android.ui.components.DisclaimerDialog
import com.vrcx.android.ui.navigation.VrcxBottomBar
import com.vrcx.android.ui.navigation.VrcxNavGraph
import com.vrcx.android.ui.screen.login.LoginScreen
import com.vrcx.android.ui.screen.login.LoginViewModel
import com.vrcx.android.ui.theme.LocalWallpaperActive
import com.vrcx.android.ui.theme.VrcxTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VrcxAppViewModel @Inject constructor(
    private val preferences: VrcxPreferences,
) : ViewModel() {
    val themeMode: StateFlow<String> = preferences.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "system")
    val dynamicColors: StateFlow<Boolean> = preferences.dynamicColors
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val disclaimerAccepted: StateFlow<Boolean?> = preferences.disclaimerAccepted
        .map<Boolean, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val wallpaperUri: StateFlow<String?> = preferences.wallpaperUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun acceptDisclaimer() {
        viewModelScope.launch { preferences.setDisclaimerAccepted(true) }
    }
}

@Composable
fun VrcxApp(appViewModel: VrcxAppViewModel = hiltViewModel()) {
    val themeMode by appViewModel.themeMode.collectAsState()
    val dynamicColors by appViewModel.dynamicColors.collectAsState()
    val darkTheme = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

    val wallpaperUri by appViewModel.wallpaperUri.collectAsState()
    val isWallpaperActive = wallpaperUri != null

    VrcxTheme(darkTheme = darkTheme, dynamicColor = dynamicColors) {
        CompositionLocalProvider(LocalWallpaperActive provides isWallpaperActive) {
        val disclaimerAccepted by appViewModel.disclaimerAccepted.collectAsState()
        val context = LocalContext.current

        if (disclaimerAccepted == null) {
            return@CompositionLocalProvider
        }

        if (disclaimerAccepted == false) {
            DisclaimerDialog(
                onAccept = { appViewModel.acceptDisclaimer() },
                onExit = { (context as? Activity)?.finishAffinity() },
            )
            return@CompositionLocalProvider
        }

        val navController = androidx.navigation.compose.rememberNavController()
        val loginViewModel: LoginViewModel = hiltViewModel()
        val authState by loginViewModel.authState.collectAsState()
        val isLoggedIn = authState is AuthState.LoggedIn

        LaunchedEffect(Unit) {
            loginViewModel.tryResumeSession()
        }

        LaunchedEffect(isLoggedIn) {
            if (isLoggedIn) {
                kotlinx.coroutines.delay(1000)
                WebSocketForegroundService.start(context)
            }
        }

        if (isLoggedIn) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (wallpaperUri != null) {
                    val parsedUri = remember(wallpaperUri) {
                        try { Uri.parse(wallpaperUri) } catch (_: Exception) { null }
                    }
                    if (parsedUri != null) {
                        AsyncImage(
                            model = parsedUri,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                Scaffold(
                    bottomBar = { VrcxBottomBar(navController) },
                    containerColor = if (isWallpaperActive) Color.Transparent else MaterialTheme.colorScheme.background,
                ) { innerPadding ->
                    VrcxNavGraph(
                        navController = navController,
                        modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                    )
                }
            }
        } else {
            LoginScreen(
                viewModel = loginViewModel,
                onLoginSuccess = { /* Auth state change triggers recomposition */ },
            )
        }
        }
    }
}
