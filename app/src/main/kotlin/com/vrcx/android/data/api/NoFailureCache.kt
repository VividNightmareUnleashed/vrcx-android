package com.vrcx.android.data.api

/**
 * Marks a Retrofit endpoint whose failures [DedupInterceptor] must never cache.
 *
 * The failure cache only clears on sign-out, so an endpoint the app needs in
 * order to *reach* a signed-in state cannot be allowed into it: a single edge
 * 403 or 404 on `auth/user` would otherwise short-circuit every sign-in attempt
 * for the next fifteen minutes without a packet leaving the device. The desktop
 * client keeps its own sign-in check out of its failure cache the same way.
 *
 * Discovered via the `retrofit2.Invocation` request tag, the same way
 * [AuthPhase] is, so the marker never travels over the wire and no endpoint
 * paths are baked into shared infrastructure.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class NoFailureCache
