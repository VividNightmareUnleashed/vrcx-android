package com.vrcx.android.ui.screen.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vrcx.android.data.repository.AuthState

@Composable
fun LoginScreen(
    viewModel: LoginViewModel = hiltViewModel(),
    onLoginSuccess: () -> Unit = {},
) {
    val authState by viewModel.authState.collectAsState()
    val username by viewModel.username.collectAsState()
    val password by viewModel.password.collectAsState()
    val twoFactorCode by viewModel.twoFactorCode.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(authState) {
        when (val state = authState) {
            is AuthState.LoggedIn -> onLoginSuccess()
            is AuthState.Error -> snackbarHostState.showSnackbar(state.message)
            else -> {}
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .imePadding()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "VRCX",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "VRChat Companion",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(32.dp))

            when (authState) {
                is AuthState.RequiresTwoFactor -> {
                    TwoFactorCard(
                        methods = (authState as AuthState.RequiresTwoFactor).methods,
                        code = twoFactorCode,
                        onCodeChange = viewModel::updateTwoFactorCode,
                        onSubmit = viewModel::submitTwoFactor,
                    )
                }
                else -> {
                    val passwordVisible by viewModel.passwordVisible.collectAsState()
                    val rememberMe by viewModel.rememberMe.collectAsState()
                    LoginCard(
                        username = username,
                        password = password,
                        isLoading = authState is AuthState.LoggingIn,
                        passwordVisible = passwordVisible,
                        rememberMe = rememberMe,
                        onUsernameChange = viewModel::updateUsername,
                        onPasswordChange = viewModel::updatePassword,
                        onTogglePasswordVisibility = viewModel::togglePasswordVisibility,
                        onToggleRememberMe = viewModel::toggleRememberMe,
                        onLogin = viewModel::login,
                    )
                }
            }
        }
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
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Sign In",
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                enabled = !isLoading,
            )
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text("Password") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = onTogglePasswordVisibility) {
                        Text(if (passwordVisible) "Hide" else "Show", style = MaterialTheme.typography.labelSmall)
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onLogin() }),
                enabled = !isLoading,
            )
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = rememberMe,
                    onCheckedChange = { onToggleRememberMe() },
                )
                Text("Remember me", style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = onLogin,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && username.isNotBlank() && password.isNotBlank(),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Sign In")
                }
            }
        }
    }
}

@Composable
private fun TwoFactorCard(
    methods: List<String>,
    code: String,
    onCodeChange: (String) -> Unit,
    onSubmit: (useEmail: Boolean) -> Unit,
) {
    var useEmail by remember { mutableStateOf(false) }
    val hasEmail = methods.contains("emailOtp")
    val hasTotp = methods.contains("totp") || methods.contains("otp")
    val label = if (useEmail) "6-digit email code" else "Authenticator or recovery code"
    val helperText = if (useEmail) {
        "Enter the code sent to your email"
    } else {
        "Enter your 6-digit authenticator code or 8-digit recovery code"
    }
    val isValidCode = isTwoFactorCodeValid(code, useEmail)

    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Two-Factor Authentication",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = helperText,
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = code,
                onValueChange = { onCodeChange(normalizeTwoFactorCode(it)) },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onSubmit(useEmail) }),
            )
            Button(
                onClick = { onSubmit(useEmail) },
                modifier = Modifier.fillMaxWidth(),
                enabled = isValidCode,
            ) {
                Text("Verify")
            }
            if (hasEmail && hasTotp) {
                TextButton(
                    onClick = { useEmail = !useEmail },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (useEmail) "Use authenticator app instead"
                        else "Use email code instead",
                    )
                }
            }
        }
    }
}

private fun normalizeTwoFactorCode(input: String): String {
    return buildString {
        input.forEach { char ->
            if (char.isDigit() || char == '-') {
                append(char)
            }
        }
    }.take(9)
}

private fun isTwoFactorCodeValid(code: String, useEmail: Boolean): Boolean {
    val digitsOnly = code.filter(Char::isDigit)
    return if (useEmail) {
        digitsOnly.length == 6
    } else {
        digitsOnly.length == 6 || digitsOnly.length == 8
    }
}
