package com.vrcx.android.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.util.Consumer
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import coil3.compose.AsyncImage
import com.vrcx.android.data.preferences.PreferenceDefaults
import com.vrcx.android.data.preferences.ThemeMode
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.preferences.WallpaperScaleMode
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.service.WebSocketForegroundService
import com.vrcx.android.ui.common.whileUiSubscribed
import com.vrcx.android.ui.components.VrcxPanelSurface
import com.vrcx.android.ui.navigation.VrcxBottomBar
import com.vrcx.android.ui.navigation.VrcxNavGraph
import com.vrcx.android.ui.screen.login.LoginScreen
import com.vrcx.android.ui.screen.login.LoginViewModel
import com.vrcx.android.ui.theme.LocalWallpaperActive
import com.vrcx.android.ui.theme.VrcxTheme
import com.vrcx.android.ui.theme.vrcxColors
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class VrcxAppViewModel @Inject constructor(private val preferences: VrcxPreferences) : ViewModel() {
    private val sharingStarted = whileUiSubscribed

    // Null means "DataStore hasn't answered yet". Guessing a value here paints a
    // whole shell in the wrong theme on every cold start for anyone whose choice
    // isn't the guess, then flips it.
    val themeMode: StateFlow<ThemeMode?> = preferences.themeMode
        .stateIn(viewModelScope, sharingStarted, null)
    val dynamicColors: StateFlow<Boolean> = preferences.dynamicColors
        .stateIn(viewModelScope, sharingStarted, PreferenceDefaults.DYNAMIC_COLORS)
    val wallpaperUri: StateFlow<String?> = preferences.wallpaperUri
        .stateIn(viewModelScope, sharingStarted, null)
    val wallpaperScaleMode: StateFlow<WallpaperScaleMode> = preferences.wallpaperScaleMode
        .stateIn(
            viewModelScope,
            sharingStarted,
            PreferenceDefaults.WALLPAPER_SCALE_MODE,
        )
    val backgroundServiceEnabled: StateFlow<Boolean?> = preferences.backgroundServiceEnabled
        .stateIn(
            viewModelScope,
            sharingStarted,
            null,
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

    VrcxTheme(darkTheme = darkTheme, dynamicColor = dynamicColors) {
        VrcxSession(appViewModel)
    }
}

@Composable
private fun VrcxSession(appViewModel: VrcxAppViewModel) {
    val wallpaperUri by appViewModel.wallpaperUri.collectAsStateWithLifecycle()
    val wallpaperScaleMode by appViewModel.wallpaperScaleMode.collectAsStateWithLifecycle()
    val backgroundServiceEnabled by appViewModel.backgroundServiceEnabled.collectAsStateWithLifecycle()
    val loginViewModel: LoginViewModel = hiltViewModel()
    val authState by loginViewModel.authState.collectAsStateWithLifecycle()
    val credentialStorageError by loginViewModel.credentialStorageError.collectAsStateWithLifecycle()
    val storageSnackbarHostState = remember { SnackbarHostState() }
    val loggedInUserId = (authState as? AuthState.LoggedIn)?.user?.id

    LaunchedEffect(Unit) {
        loginViewModel.tryResumeSession()
    }
    LaunchedEffect(credentialStorageError) {
        credentialStorageError?.let { message ->
            storageSnackbarHostState.showSnackbar(message)
            loginViewModel.dismissCredentialStorageError()
        }
    }
    SocketServiceBinding(
        isLoggedIn = loggedInUserId != null,
        backgroundServiceEnabled = backgroundServiceEnabled,
    )

    val wallpaperContentScale = when (wallpaperScaleMode) {
        WallpaperScaleMode.CROP -> ContentScale.Crop
        WallpaperScaleMode.FIT -> ContentScale.Fit
        WallpaperScaleMode.FILL_WIDTH -> ContentScale.FillWidth
        WallpaperScaleMode.FILL_HEIGHT -> ContentScale.FillHeight
    }
    CompositionLocalProvider(LocalWallpaperActive provides (wallpaperUri != null)) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (loggedInUserId != null) {
                LoggedInContent(loggedInUserId, wallpaperUri, wallpaperContentScale)
            } else {
                LoginScreen(viewModel = loginViewModel)
            }
            SnackbarHost(
                hostState = storageSnackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        horizontal = 16.dp,
                        vertical = if (loggedInUserId != null) 80.dp else 16.dp,
                    ),
            )
        }
    }
}

@Composable
private fun SocketServiceBinding(isLoggedIn: Boolean, backgroundServiceEnabled: Boolean?) {
    val context = LocalContext.current
    LaunchedEffect(isLoggedIn, backgroundServiceEnabled) {
        declareSocketState(context, isLoggedIn, backgroundServiceEnabled)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, isLoggedIn, backgroundServiceEnabled) {
        if (!isLoggedIn || backgroundServiceEnabled != false) return@DisposableEffect onDispose {}
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
}

@Composable
private fun LoggedInContent(loggedInUserId: String, wallpaperUri: String?, wallpaperContentScale: ContentScale) {
    val navController = key(loggedInUserId) { rememberNavController() }
    val context = LocalContext.current
    val activity = remember(context) { context.findComponentActivity() }
    SessionDeepLinkBinding(navController, activity)
    WallpaperShell(navController, wallpaperUri, wallpaperContentScale)
}

@Composable
private fun SessionDeepLinkBinding(navController: NavHostController, activity: ComponentActivity?) {
    // The launch link has already been followed when the graph is created. Clearing it
    // keeps account-switch reconstruction from consuming it under a different session.
    DisposableEffect(navController, activity) {
        activity?.intent?.data = null
        val listener = Consumer<Intent> { newIntent ->
            navController.handleDeepLink(newIntent)
            newIntent.data = null
        }
        activity?.addOnNewIntentListener(listener)
        onDispose { activity?.removeOnNewIntentListener(listener) }
    }
}

@Composable
private fun WallpaperShell(
    navController: NavHostController,
    wallpaperUri: String?,
    wallpaperContentScale: ContentScale,
) {
    val vrcxColors = MaterialTheme.vrcxColors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(vrcxColors.shellGradientStart, vrcxColors.shellGradientEnd),
                ),
            ),
    ) {
        if (wallpaperUri != null) {
            val parsedUri = remember(wallpaperUri) {
                try {
                    Uri.parse(wallpaperUri)
                } catch (_: Exception) {
                    null
                }
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
        NavigationPanel(navController)
    }
}

@Composable
private fun NavigationPanel(navController: NavHostController) {
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
internal fun declareSocketState(context: Context, isLoggedIn: Boolean, backgroundServiceEnabled: Boolean?) {
    if (!isLoggedIn || backgroundServiceEnabled == null) return
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
