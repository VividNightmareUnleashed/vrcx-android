package com.vrcx.android.ui.screen.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.model.CurrentUser
import com.vrcx.android.data.api.model.UpdateCurrentUserRequest
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.UserRepository
import com.vrcx.android.data.util.runCatchingCancellable
import com.vrcx.android.ui.common.whileUiSubscribed
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
) : ViewModel() {
    val currentUser: StateFlow<CurrentUser?> =
        authRepository.authState
            .map { state -> (state as? AuthState.LoggedIn)?.user }
            .stateIn(viewModelScope, whileUiSubscribed, null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun saveStatus(status: String, statusDescription: String) {
        updateCurrentUser(
            payload =
                UpdateCurrentUserRequest(
                    status = status,
                    statusDescription = statusDescription,
                ),
            successMessage = "Status updated",
        )
    }

    fun saveBio(bio: String) {
        updateCurrentUser(
            payload = UpdateCurrentUserRequest(bio = bio),
            successMessage = "Bio updated",
        )
    }

    fun savePronouns(pronouns: String) {
        updateCurrentUser(
            payload = UpdateCurrentUserRequest(pronouns = pronouns),
            successMessage = "Pronouns updated",
        )
    }

    fun clearHomeLocation() {
        updateCurrentUser(
            payload = UpdateCurrentUserRequest(homeLocation = ""),
            successMessage = "Home location cleared",
        )
    }

    fun clearMessage() {
        _message.value = null
    }

    fun logout() {
        viewModelScope.launch { authRepository.logout() }
    }

    private fun updateCurrentUser(payload: UpdateCurrentUserRequest, successMessage: String) {
        viewModelScope.launch {
            val userId = currentUser.value?.id ?: return@launch
            _message.value =
                runCatchingCancellable {
                    userRepository.saveCurrentUser(userId, payload)
                    authRepository.fetchCurrentUser()
                    successMessage
                }.getOrElse { failure -> "Failed: ${failure.message}" }
        }
    }
}
