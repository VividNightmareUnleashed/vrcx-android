package com.vrcx.android.data.api

/**
 * Marks a Retrofit endpoint as part of the pre-session login / 2FA flow.
 *
 * A 401 on such a call is an authentication-phase failure (bad credentials, a
 * wrong or expired 2FA code) — not evidence that an established cookie session
 * expired. [ErrorInterceptor] therefore does **not** emit
 * [AuthEvent.Unauthorized] for these calls, so the login flow can surface the
 * verification error locally without tearing down a session.
 *
 * The interceptor discovers this via the `retrofit2.Invocation` request tag, so
 * the marker never travels over the wire. Declaring it on the interface method
 * keeps the contract explicit at the call definition: a new auth-phase endpoint
 * opts in by adding this annotation instead of by being appended to a URL-path
 * sniff list inside shared infrastructure.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class AuthPhase
