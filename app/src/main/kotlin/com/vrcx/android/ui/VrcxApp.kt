package com.vrcx.android.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.core.util.Consumer
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.vrcx.android.data.preferences.PreferenceDefaults
import com.vrcx.android.data.preferences.ThemeMode
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.preferences.WallpaperScaleMode
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.service.WebSocketForegroundService
import com.vrcx.android.ui.components.VrcxPanelSurface
import com.vrcx.android.ui.navigation.VrcxBottomBar
import com.vrcx.android.ui.navigation.VrcxNavGraph
import com.vrcx.android.ui.screen.login.LoginScreen
import com.vrcx.android.ui.screen.login.LoginViewModel
import com.vrcx.android.ui.theme.LocalWallpaperActive
import com.vrcx.android.ui.theme.VrcxTheme
import com.vrcx.android.ui.theme.vrcxColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import androidx.compose.ui.unit.dp
import javax.inject.Inject

@HiltViewModel
class VrcxAppViewModel @Inject constructor(
    private val preferences: VrcxPreferences,
) : ViewModel() {
    // Null means "DataStore hasn't answered yet". Guessing a value here paints a
    // whole shell in the wrong theme on every cold start for anyone whose choice
    // isn't the guess, then flips it.
    val themeMode: StateFlow<ThemeMode?> = preferences.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val dynamicColors: StateFlow<Boolean> = preferences.dynamicColors
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PreferenceDefaults.DYNAMIC_COLORS)
    val wallpaperUri: StateFlow<String?> = preferences.wallpaperUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val wallpaperScaleMode: StateFlow<WallpaperScaleMode> = preferences.wallpaperScaleMode
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            PreferenceDefaults.WALLPAPER_SCALE_MODE,
        )
    val backgroundServiceEnabled: StateFlow<Boolean> = preferences.backgroundServiceEnabled
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            PreferenceDefaults.BACKGROUND_SERVICE_ENABLED,
        )
}

@Composable
fun VrcxApp(appViewModel: VrcxAppViewModel = hiltViewModel()) {
    val themeMode by appViewModel.themeMode.collectAsStateWithLifecycle()
    val dynamicColors by appViewModel.dynamicColors.collectAsStateWithLifecycle()
    val darkTheme = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        // null is the not-yet-resolved case: follow the system until we know,
        // which is what the window background behind us is already doing.
        ThemeMode.SYSTEM, null -> isSystemInDarkTheme()
    }

    val wallpaperUri by appViewModel.wallpaperUri.collectAsStateWithLifecycle()
    val wallpaperScaleMode by appViewModel.wallpaperScaleMode.collectAsStateWithLifecycle()
    val isWallpaperActive = wallpaperUri != null
    val wallpaperContentScale = when (wallpaperScaleMode) {
        WallpaperScaleMode.CROP -> ContentScale.Crop
        WallpaperScaleMode.FIT -> ContentScale.Fit
        WallpaperScaleMode.FILL_WIDTH -> ContentScale.FillWidth
        WallpaperScaleMode.FILL_HEIGHT -> ContentScale.FillHeight
    }

    VrcxTheme(darkTheme = darkTheme, dynamicColor = dynamicColors) {
        CompositionLocalProvider(LocalWallpaperActive provides isWallpaperActive) {
        val context = LocalContext.current
        val vrcxColors = MaterialTheme.vrcxColors

        val loginViewModel: LoginViewModel = hiltViewModel()
        val authState by loginViewModel.authState.collectAsStateWithLifecycle()
        val loggedInUserId = (authState as? AuthState.LoggedIn)?.user?.id
        val isLoggedIn = loggedInUserId != null
        val backgroundServiceEnabled by appViewModel.backgroundServiceEnabled.collectAsStateWithLifecycle()

        LaunchedEffect(Unit) {
            loginViewModel.tryResumeSession()
        }

        LaunchedEffect(loggedInUserId, backgroundServiceEnabled) {
            declareSocketState(context, loggedInUserId != null, backgroundServiceEnabled)
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
            val navController = key(loggedInUserId) {
                androidx.navigation.compose.rememberNavController()
            }
            val activity = remember(context) { context.findComponentActivity() }
            // NavController consumes the launch intent when its graph is first
            // created, so by the time this runs the link has been followed.
            // Clearing it keeps the launch link a one-shot: the controller is
            // rebuilt on every account switch, and a second consumption would
            // reopen the same screen under a different session. Links that
            // arrive later come through onNewIntent, which the live controller
            // handles directly.
            DisposableEffect(navController, activity) {
                activity?.intent?.data = null
                val listener = Consumer<Intent> { newIntent ->
                    navController.handleDeepLink(newIntent)
                    newIntent.data = null
                }
                activity?.addOnNewIntentListener(listener)
                onDispose { activity?.removeOnNewIntentListener(listener) }
            }
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
                val navigationBarBottomPadding = WindowInsets.navigationBars
                    .asPaddingValues()
                    .calculateBottomPadding()

                Scaffold(
                    bottomBar = { VrcxBottomBar(navController) },
                    containerColor = Color.Transparent,
                    contentWindowInsets = WindowInsets(0),
                ) { innerPadding ->
                    val bottomPadding = maxOf(
                        innerPadding.calculateBottomPadding(),
                        navigationBarBottomPadding,
                    )

                    VrcxPanelSurface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                start = 8.dp,
                                top = 8.dp,
                                end = 8.dp,
                                bottom = bottomPadding + 8.dp,
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
            LoginScreen(viewModel = loginViewModel)
        }
        }
    }
}

/**
 * Say what the socket should be doing; leave the doing to the service.
 *
 * The service reconciles: re-declaring the mode it is already in is a no-op,
 * the other mode is a transition in place that keeps the socket, and session
 * teardown stops it. Nothing here stops it — the shell's view of the session
 * lags the service's (authState starts NotLoggedIn on every launch, and the
 * background-service preference arrives a moment after that), so a stop issued
 * from here lands on a socket that is perfectly healthy, drops whatever VRChat
 * pushes during the gap, and on an offline open kills the connection the boot
 * reconnect had already established.
 */
internal fun declareSocketState(
    context: Context,
    isLoggedIn: Boolean,
    backgroundServiceEnabled: Boolean,
) {
    if (!isLoggedIn) return
    if (backgroundServiceEnabled) {
        WebSocketForegroundService.start(context)
    } else {
        WebSocketForegroundService.startNonForeground(context)
    }
}

/** The hosting activity, through however many themed ContextWrappers Compose adds. */
private fun Context.findComponentActivity(): ComponentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    return null
}
