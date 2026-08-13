package com.vrcx.android.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountScopeTest {

    private class Member : AccountScoped {
        var clears = 0
        override fun clearRuntimeState() {
            clears++
        }
    }

    @Test
    fun `binding for the guard is what registers a repository for teardown`() {
        val scope = AccountScope()
        val member = Member()

        // A repository obtains the scope its guards read by binding, so there is
        // no way to guard writes without also being reset with the account.
        assertSame(scope, scope.bindTo(member))
        scope.invalidate()

        assertEquals(1, member.clears)
    }

    @Test
    fun `binding twice does not reset the same repository twice`() {
        val scope = AccountScope()
        val member = Member()
        scope.bindTo(member)
        scope.bindTo(member)

        scope.invalidate()

        assertEquals(1, member.clears)
    }

    @Test
    fun `work started under the previous account cannot publish into the new one`() {
        val scope = AccountScope()
        scope.bind("usr_old")
        val token = scope.current()

        scope.invalidate()
        scope.bind("usr_new")

        var published = false
        assertFalse(scope.publishIfCurrent(token) { published = true })
        assertFalse(published)
        assertFalse(scope.isCurrent(token))
    }

    @Test
    fun `signing back into the same account is still a different generation`() {
        val scope = AccountScope()
        scope.bind("usr_me")
        val token = scope.current()

        scope.invalidate()
        scope.bind("usr_me")

        // Same id, new session: the previous session's in-flight work is stale
        // even though the owner matches.
        assertFalse(scope.isCurrent(token))
    }

    @Test
    fun `a publish under the live account runs`() {
        val scope = AccountScope()
        scope.bind("usr_me")
        val token = scope.current()

        var published = false
        assertTrue(scope.publishIfCurrent(token) { published = true })
        assertTrue(published)
    }

    @Test
    fun `a multi-step sequence aborts with an account-change signal`() {
        val scope = AccountScope()
        scope.bind("usr_old")
        val token = scope.current()
        scope.invalidate()

        assertThrows(AccountChangedException::class.java) { scope.ensureCurrent(token) }
        assertThrows(AccountChangedException::class.java) {
            scope.publishOrAbort(token) { error("must not run") }
        }
    }

    @Test
    fun `ending the account leaves no owner behind`() {
        val scope = AccountScope()
        scope.bind("usr_me")

        scope.invalidate()

        assertEquals("", scope.ownerUserId)
    }
}
