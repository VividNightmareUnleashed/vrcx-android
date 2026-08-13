package com.vrcx.android.ui.common

/**
 * What a screen (or one tab of one) knows about a thing it loads.
 *
 * The four cases are exclusive, so "loading" and "already loaded" cannot both be
 * true and a consumer never has to reconstruct which of several flags wins.
 * A refresh over data already on screen is [Loaded] with [Loaded.isRefreshing]
 * set, and a refresh that fails keeps the data and names the failure in
 * [Loaded.staleError] rather than throwing the screen back to [Failed].
 *
 * Screens whose data lives in a repository flow rather than in the state itself
 * use `LoadState<Unit>`.
 */
sealed interface LoadState<out T> {
    /** Nothing has been asked for yet. */
    data object NotLoaded : LoadState<Nothing>

    /** The first load is in flight; there is nothing to show behind it. */
    data object Loading : LoadState<Nothing>

    /**
     * There is something to show. [staleError] is the last refresh's failure,
     * which the data outlived; [warning] is a partial success — some of the data
     * arrived and some did not — and is not a failure.
     */
    data class Loaded<out T>(
        val value: T,
        val isRefreshing: Boolean = false,
        val staleError: String? = null,
        val warning: String? = null,
    ) : LoadState<T>

    /** The first load failed, so there is nothing to show. */
    data class Failed(val message: String) : LoadState<Nothing>
}

/** True once there is something to show, refreshing or not. */
val LoadState<*>.isLoaded: Boolean
    get() = this is LoadState.Loaded

/** True while a load is in flight — the guard against starting a second one. */
val LoadState<*>.isBusy: Boolean
    get() = this is LoadState.Loading || (this is LoadState.Loaded && isRefreshing)

/** The loaded value, or null in every other case. */
val <T> LoadState<T>.valueOrNull: T?
    get() = (this as? LoadState.Loaded)?.value

/**
 * A load is starting: the first one shows a spinner, a later one keeps the data
 * on screen and marks it refreshing. Messages from the previous attempt clear.
 */
fun <T> LoadState<T>.startLoad(): LoadState<T> = when (this) {
    is LoadState.Loaded -> copy(isRefreshing = true, staleError = null, warning = null)
    else -> LoadState.Loading
}

/**
 * A load succeeded, replacing whatever came before — nothing from the previous
 * attempt survives it. [warning] records data that arrived incomplete.
 */
fun <T> LoadState<T>.completeLoad(value: T, warning: String? = null): LoadState<T> =
    LoadState.Loaded(value = value, warning = warning)

/**
 * A load failed. Before the first success that is [LoadState.Failed]; afterwards
 * the data stays and the message rides along as [LoadState.Loaded.staleError],
 * which is what a screen shows in a banner or a snackbar instead of an error page.
 */
fun <T> LoadState<T>.failLoad(message: String): LoadState<T> = when (this) {
    is LoadState.Loaded -> copy(isRefreshing = false, staleError = message)
    else -> LoadState.Failed(message)
}

/**
 * Drops the in-flight marker without deciding an outcome — for a `finally`, so a
 * cancelled load cannot leave the state busy forever and block every later retry.
 * A no-op on a state that has already settled.
 */
fun <T> LoadState<T>.settleLoad(): LoadState<T> = when (this) {
    is LoadState.Loaded -> if (isRefreshing) copy(isRefreshing = false) else this
    LoadState.Loading -> LoadState.NotLoaded
    else -> this
}
