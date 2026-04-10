package com.vrcx.android.ui

import android.app.Activity
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.service.WebSocketForegroundService
import com.vrcx.android.ui.components.DisclaimerDialog
import com.vrcx.android.ui.components.VrcxPanelSurface
import com.vrcx.android.ui.navigation.VrcxBottomBar
import com.vrcx.android.ui.navigation.VrcxNavGraph
import com.vrcx.android.ui.screen.login.LoginScreen
import com.vrcx.android.ui.screen.login.LoginViewModel
import com.vrcx.android.ui.theme.LocalWallpaperActive
import com.vrcx.android.ui.theme.VrcxTheme
import com.vrcx.android.ui.theme.vrcxColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import javax.inject.Inject

@HiltViewModel
class VrcxAppViewModel @Inject constructor(
    private val preferences: VrcxPreferences,
) : ViewModel() {
    val themeMode: StateFlow<String> = preferences.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "dark")
    val dynamicColors: StateFlow<Boolean> = preferences.dynamicColors
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val disclaimerAccepted: StateFlow<Boolean?> = preferences.disclaimerAccepted
        .map<Boolean, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val wallpaperUri: StateFlow<String?> = preferences.wallpaperUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val wallpaperScaleMode: StateFlow<String> = preferences.wallpaperScaleMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "crop")
    val backgroundServiceEnabled: StateFlow<Boolean> = preferences.backgroundServiceEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

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
    val wallpaperScaleMode by appViewModel.wallpaperScaleMode.collectAsState()
    val isWallpaperActive = wallpaperUri != null
    val wallpaperContentScale = when (wallpaperScaleMode) {
        "fit" -> ContentScale.Fit
        "fill_width" -> ContentScale.FillWidth
        "fill_height" -> ContentScale.FillHeight
        else -> ContentScale.Crop
    }

    VrcxTheme(darkTheme = darkTheme, dynamicColor = dynamicColors) {
        CompositionLocalProvider(LocalWallpaperActive provides isWallpaperActive) {
        val disclaimerAccepted by appViewModel.disclaimerAccepted.collectAsState()
        val context = LocalContext.current
        val vrcxColors = MaterialTheme.vrcxColors

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
        val backgroundServiceEnabled by appViewModel.backgroundServiceEnabled.collectAsState()

        LaunchedEffect(Unit) {
            loginViewModel.tryResumeSession()
        }

        LaunchedEffect(isLoggedIn, backgroundServiceEnabled) {
            if (isLoggedIn) {
                WebSocketForegroundService.stop(context)
                delay(1000)
                if (backgroundServiceEnabled) {
                    WebSocketForegroundService.start(context)
                } else {
                    WebSocketForegroundService.startNonForeground(context)
                }
            }
        }

        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner, isLoggedIn, backgroundServiceEnabled) {
            if (!isLoggedIn || backgroundServiceEnabled) return@DisposableEffect onDispose {}
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> WebSocketForegroundService.stop(context)
                    Lifecycle.Event.ON_START -> WebSocketForegroundService.startNonForeground(context)
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        if (isLoggedIn) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                vrcxColors.shellGradientStart,
                                vrcxColors.shellGradientEnd,
                            ),
                        ),
                    ),
            ) {
                if (wallpaperUri != null) {
                    val parsedUri = remember(wallpaperUri) {
                        try { Uri.parse(wallpaperUri) } catch (_: Exception) { null }
                    }
                    if (parsedUri != null) {
                        AsyncImage(
                            model = parsedUri,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = wallpaperContentScale,
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.42f)),
                    )
                }
                Scaffold(
                    bottomBar = { VrcxBottomBar(navController) },
                    containerColor = Color.Transparent,
                    contentWindowInsets = WindowInsets(0),
                ) { innerPadding ->
                    VrcxPanelSurface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                start = 8.dp,
                                top = 8.dp,
                                end = 8.dp,
                                bottom = innerPadding.calculateBottomPadding() + 8.dp,
                            ),
                    ) {
                        VrcxNavGraph(
                            navController = navController,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
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
