package com.vrcx.android.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.vrcx.android.service.WebSocketForegroundService
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.ui.navigation.VrcxBottomBar
import com.vrcx.android.ui.navigation.VrcxNavGraph
import com.vrcx.android.ui.navigation.VrcxRoutes
import com.vrcx.android.ui.screen.login.LoginScreen
import com.vrcx.android.ui.screen.login.LoginViewModel
import com.vrcx.android.ui.theme.VrcxTheme

@Composable
fun VrcxApp() {
    VrcxTheme {
        val navController = rememberNavController()
        val loginViewModel: LoginViewModel = hiltViewModel()
        val authState by loginViewModel.authState.collectAsState()
        val isLoggedIn = authState is AuthState.LoggedIn

        val context = LocalContext.current

        LaunchedEffect(Unit) {
            loginViewModel.tryResumeSession()
        }

        LaunchedEffect(isLoggedIn) {
            if (isLoggedIn) {
                // Small delay to ensure auth token is fetched before service starts
                kotlinx.coroutines.delay(1000)
                WebSocketForegroundService.start(context)
            }
        }

        if (isLoggedIn) {
            Scaffold(
                bottomBar = { VrcxBottomBar(navController) },
            ) { innerPadding ->
                VrcxNavGraph(
                    navController = navController,
                    modifier = Modifier.padding(innerPadding),
                )
            }
        } else {
            LoginScreen(
                viewModel = loginViewModel,
                onLoginSuccess = { /* Auth state change triggers recomposition */ },
            )
        }
    }
}
