package com.vrcx.android.ui.screen.login

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginScreenTest {

    @Test
    fun `email-only two factor defaults to email OTP submission`() {
        assertTrue(shouldUseEmailOtpByDefault(listOf("emailOtp")))
    }

    @Test
    fun `mixed two factor methods keep authenticator as the default`() {
        assertFalse(shouldUseEmailOtpByDefault(listOf("emailOtp", "totp")))
        assertFalse(shouldUseEmailOtpByDefault(listOf("emailOtp", "otp")))
    }
}
