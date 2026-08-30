package com.vrcx.android.ui.screen.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxInputField
import com.vrcx.android.ui.theme.vrcxColors

@Composable
internal fun TwoFactorCard(
    methods: List<String>,
    code: String,
    isLoading: Boolean,
    errorMessage: String?,
    canResendEmailCode: Boolean,
    onCodeChange: (String) -> Unit,
    onSubmit: (useEmail: Boolean) -> Unit,
    onResendEmail: () -> Unit,
) {
    val hasEmail = methods.contains("emailOtp")
    val hasAuthenticator = hasAuthenticatorMethod(methods)
    // This choice selects the verification endpoint, so rotation must preserve it during a challenge.
    var useEmail by rememberSaveable(methods) { mutableStateOf(shouldUseEmailOtpByDefault(methods)) }
    val isValidCode = isTwoFactorCodeValid(code, useEmail)

    VrcxCard {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TwoFactorPrompt(useEmail)
            TwoFactorCodeInput(
                code = code,
                useEmail = useEmail,
                isLoading = isLoading,
                isValidCode = isValidCode,
                onCodeChange = onCodeChange,
                onSubmit = onSubmit,
            )
            errorMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            TwoFactorVerifyButton(
                useEmail = useEmail,
                isLoading = isLoading,
                isValidCode = isValidCode,
                onSubmit = onSubmit,
            )
            TwoFactorMethodActions(
                showMethodToggle = hasEmail && hasAuthenticator,
                showResend = hasEmail && useEmail && canResendEmailCode,
                useEmail = useEmail,
                isLoading = isLoading,
                onToggleMethod = { useEmail = !useEmail },
                onResendEmail = onResendEmail,
            )
        }
    }
}

@Composable
private fun TwoFactorPrompt(useEmail: Boolean) {
    val helperText = if (useEmail) {
        "Enter the code sent to your email"
    } else {
        "Enter your 6-digit authenticator code or 8-character recovery code"
    }
    Text(
        text = "Two-Factor Authentication",
        style = MaterialTheme.typography.titleLarge,
    )
    Text(
        text = helperText,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.vrcxColors.panelMuted,
    )
}

@Composable
private fun TwoFactorCodeInput(
    code: String,
    useEmail: Boolean,
    isLoading: Boolean,
    isValidCode: Boolean,
    onCodeChange: (String) -> Unit,
    onSubmit: (useEmail: Boolean) -> Unit,
) {
    VrcxInputField(
        value = code,
        onValueChange = { onCodeChange(normalizeTwoFactorCode(it)) },
        placeholder = if (useEmail) "6-digit email code" else "Authenticator or recovery code",
        keyboardOptions = KeyboardOptions(
            // Recovery codes carry letters, so only the email branch can ask for the numeric keyboard.
            keyboardType = if (useEmail) KeyboardType.Number else KeyboardType.Text,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onDone = { if (isValidCode && !isLoading) onSubmit(useEmail) },
        ),
        enabled = !isLoading,
    )
}

@Composable
private fun TwoFactorVerifyButton(
    useEmail: Boolean,
    isLoading: Boolean,
    isValidCode: Boolean,
    onSubmit: (useEmail: Boolean) -> Unit,
) {
    Button(
        onClick = { onSubmit(useEmail) },
        modifier = Modifier.fillMaxWidth(),
        enabled = isValidCode && !isLoading,
    ) {
        LoadingButtonContent(isLoading = isLoading, label = "Verify")
    }
}

@Composable
private fun TwoFactorMethodActions(
    showMethodToggle: Boolean,
    showResend: Boolean,
    useEmail: Boolean,
    isLoading: Boolean,
    onToggleMethod: () -> Unit,
    onResendEmail: () -> Unit,
) {
    if (showMethodToggle) {
        TextButton(
            onClick = onToggleMethod,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (useEmail) {
                    "Use authenticator app instead"
                } else {
                    "Use email code instead"
                },
            )
        }
    }
    if (showResend) {
        TextButton(
            onClick = onResendEmail,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading,
        ) {
            Text("Resend Email Code")
        }
    }
}

@Composable
internal fun LoadingButtonContent(isLoading: Boolean, label: String) {
    if (isLoading) {
        CircularProgressIndicator(
            modifier = Modifier.height(20.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    } else {
        Text(label)
    }
}

private fun normalizeTwoFactorCode(input: String): String = buildString {
    input.forEach { char ->
        if (char.isLetterOrDigit() || char == '-') {
            append(char)
        }
    }
}.take(FORMATTED_RECOVERY_CODE_LENGTH)

internal fun isTwoFactorCodeValid(code: String, useEmail: Boolean): Boolean {
    val characters = code.filter(Char::isLetterOrDigit)
    return if (useEmail) {
        // Email codes are always 6 digits; recovery codes are the ones with letters.
        characters.length == SHORT_TWO_FACTOR_CODE_LENGTH && characters.all(Char::isDigit)
    } else {
        characters.length == SHORT_TWO_FACTOR_CODE_LENGTH || characters.length == RECOVERY_CODE_LENGTH
    }
}

internal fun shouldUseEmailOtpByDefault(methods: List<String>): Boolean {
    val hasEmail = methods.contains("emailOtp")
    return hasEmail && !hasAuthenticatorMethod(methods)
}

private fun hasAuthenticatorMethod(methods: List<String>): Boolean = methods.contains("totp") || methods.contains("otp")

private const val SHORT_TWO_FACTOR_CODE_LENGTH = 6
private const val RECOVERY_CODE_LENGTH = 8
private const val FORMATTED_RECOVERY_CODE_LENGTH = 9
