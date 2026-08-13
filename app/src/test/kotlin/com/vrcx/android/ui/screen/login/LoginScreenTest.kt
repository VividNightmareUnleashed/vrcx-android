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
    fun `two factor validation rejects short IME submissions`() {
        assertFalse(isTwoFactorCodeValid("12345", useEmail = false))
        assertFalse(isTwoFactorCodeValid("12345", useEmail = true))
        assertTrue(isTwoFactorCodeValid("123456", useEmail = false))
        assertTrue(isTwoFactorCodeValid("1234-5678", useEmail = false))
    }

    @Test
    fun `two factor validation accepts the letters a recovery code carries`() {
        // VRChat recovery codes are 4+4 alphanumeric, so a validator counting
        // digits alone leaves the documented account-recovery path unusable.
        assertTrue(isTwoFactorCodeValid("ab12-cd34", useEmail = false))
        assertTrue(isTwoFactorCodeValid("abcdefgh", useEmail = false))
        assertFalse(isTwoFactorCodeValid("ab12cd3", useEmail = false))
        // Email codes are still six digits.
        assertFalse(isTwoFactorCodeValid("ab12cd", useEmail = true))
    }

    @Test
    fun `mixed two factor methods keep authenticator as the default`() {
        assertFalse(shouldUseEmailOtpByDefault(listOf("emailOtp", "totp")))
        assertFalse(shouldUseEmailOtpByDefault(listOf("emailOtp", "otp")))
    }
}
