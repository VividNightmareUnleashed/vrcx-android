package com.vrcx.android.ui.screen.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.TwoFactorVerification
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxInputField
import com.vrcx.android.ui.theme.vrcxColors

@Composable
fun LoginScreen(viewModel: LoginViewModel = hiltViewModel()) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val username by viewModel.username.collectAsStateWithLifecycle()
    val password by viewModel.password.collectAsStateWithLifecycle()
    val twoFactorCode by viewModel.twoFactorCode.collectAsStateWithLifecycle()
    val canResendEmailCode by viewModel.canResendEmailCode.collectAsStateWithLifecycle()
    val passwordVisible by viewModel.passwordVisible.collectAsStateWithLifecycle()
    val rememberMe by viewModel.rememberMe.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(authState) {
        (authState as? AuthState.Error)?.let { snackbarHostState.showSnackbar(it.message) }
    }

    LoginScaffold(snackbarHostState) {
        LoginAuthContent(
            authState = authState,
            username = username,
            password = password,
            twoFactorCode = twoFactorCode,
            canResendEmailCode = canResendEmailCode,
            passwordVisible = passwordVisible,
            rememberMe = rememberMe,
            onUsernameChange = viewModel::updateUsername,
            onPasswordChange = viewModel::updatePassword,
            onTogglePasswordVisibility = viewModel::togglePasswordVisibility,
            onToggleRememberMe = viewModel::toggleRememberMe,
            onLogin = viewModel::login,
            onCodeChange = viewModel::updateTwoFactorCode,
            onSubmitTwoFactor = viewModel::submitTwoFactor,
            onResendEmail = viewModel::resendEmailCode,
            onOpenRegister = { uriHandler.openUri("https://vrchat.com/register") },
            onOpenForgotPassword = { uriHandler.openUri("https://vrchat.com/home/password/forgot") },
        )
    }
}

@Composable
private fun LoginScaffold(snackbarHostState: SnackbarHostState, content: @Composable () -> Unit) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.vrcxColors.shellGradientStart,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 32.dp)
                .imePadding()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "VRCX",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "VRChat Companion for Android",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.vrcxColors.panelMuted,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))
            content()
        }
    }
}

@Composable
private fun LoginAuthContent(
    authState: AuthState,
    username: String,
    password: String,
    twoFactorCode: String,
    canResendEmailCode: Boolean,
    passwordVisible: Boolean,
    rememberMe: Boolean,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onToggleRememberMe: () -> Unit,
    onLogin: () -> Unit,
    onCodeChange: (String) -> Unit,
    onSubmitTwoFactor: (useEmail: Boolean) -> Unit,
    onResendEmail: () -> Unit,
    onOpenRegister: () -> Unit,
    onOpenForgotPassword: () -> Unit,
) {
    when (authState) {
        is AuthState.RequiresTwoFactor -> TwoFactorCard(
            methods = authState.methods,
            code = twoFactorCode,
            isLoading = authState.verification is TwoFactorVerification.InProgress,
            errorMessage = (authState.verification as? TwoFactorVerification.Failed)?.message,
            canResendEmailCode = canResendEmailCode,
            onCodeChange = onCodeChange,
            onSubmit = onSubmitTwoFactor,
            onResendEmail = onResendEmail,
        )

        else -> LoginCard(
            username = username,
            password = password,
            isLoading = authState is AuthState.LoggingIn,
            passwordVisible = passwordVisible,
            rememberMe = rememberMe,
            onUsernameChange = onUsernameChange,
            onPasswordChange = onPasswordChange,
            onTogglePasswordVisibility = onTogglePasswordVisibility,
            onToggleRememberMe = onToggleRememberMe,
            onLogin = onLogin,
            onOpenRegister = onOpenRegister,
            onOpenForgotPassword = onOpenForgotPassword,
        )
    }
}

@Composable
private fun LoginCard(
    username: String,
    password: String,
    isLoading: Boolean,
    passwordVisible: Boolean = false,
    rememberMe: Boolean = false,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit = {},
    onToggleRememberMe: () -> Unit = {},
    onLogin: () -> Unit,
    onOpenRegister: () -> Unit,
    onOpenForgotPassword: () -> Unit,
) {
    VrcxCard {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Sign In",
                style = MaterialTheme.typography.titleLarge,
            )
            LoginCredentials(
                username = username,
                password = password,
                passwordVisible = passwordVisible,
                enabled = !isLoading,
                onUsernameChange = onUsernameChange,
                onPasswordChange = onPasswordChange,
                onTogglePasswordVisibility = onTogglePasswordVisibility,
                onLogin = onLogin,
            )
            RememberMeRow(rememberMe, onToggleRememberMe)

            Button(
                onClick = onLogin,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && username.isNotBlank() && password.isNotBlank(),
            ) {
                LoadingButtonContent(isLoading = isLoading, label = "Sign In")
            }
            LoginLinks(!isLoading, onOpenRegister, onOpenForgotPassword)
        }
    }
}

@Composable
private fun LoginCredentials(
    username: String,
    password: String,
    passwordVisible: Boolean,
    enabled: Boolean,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onLogin: () -> Unit,
) {
    Text(text = "Username", style = MaterialTheme.typography.labelLarge)
    VrcxInputField(
        value = username,
        onValueChange = onUsernameChange,
        placeholder = "Enter your VRChat username",
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        enabled = enabled,
    )
    Text(text = "Password", style = MaterialTheme.typography.labelLarge)
    VrcxInputField(
        value = password,
        onValueChange = onPasswordChange,
        placeholder = "Enter your password",
        visualTransformation =
            if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingContent = {
            TextButton(onClick = onTogglePasswordVisibility) {
                Text(if (passwordVisible) "Hide" else "Show")
            }
        },
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
        keyboardActions = KeyboardActions(onDone = { onLogin() }),
        enabled = enabled,
    )
}

@Composable
private fun RememberMeRow(checked: Boolean, onToggle: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(text = "Remember me", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LoginLinks(enabled: Boolean, onOpenRegister: () -> Unit, onOpenForgotPassword: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(onClick = onOpenRegister, enabled = enabled) {
            Text("Register")
        }
        TextButton(onClick = onOpenForgotPassword, enabled = enabled) {
            Text("Forgot Password")
        }
    }
}
