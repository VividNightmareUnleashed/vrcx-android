package com.vrcx.android.ui.common

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String, val retry: (() -> Unit)? = null) : UiState<Nothing>
    data class Empty(val message: String = "", val action: (() -> Unit)? = null) : UiState<Nothing>
}

val <T> UiState<T>.isLoading: Boolean get() = this is UiState.Loading

val <T> UiState<T>.dataOrNull: T? get() = (this as? UiState.Success)?.data

val <T> UiState<T>.errorMessageOrNull: String? get() = (this as? UiState.Error)?.message
