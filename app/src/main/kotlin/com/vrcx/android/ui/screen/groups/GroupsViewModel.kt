package com.vrcx.android.ui.screen.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.model.Group
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.GroupRepository
import com.vrcx.android.data.util.runCatchingCancellable
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class GroupsViewModel @Inject constructor(
    private val groupRepository: GroupRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {
    val groups: StateFlow<List<Group>> = groupRepository.userGroups

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        loadGroups()
    }

    fun loadGroups() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val failure =
                    runCatchingCancellable {
                        check(authRepository.authState.value is AuthState.LoggedIn) {
                            "You must be logged in to load groups"
                        }
                        groupRepository.loadMyGroups()
                    }.exceptionOrNull()
                _error.value = failure?.message ?: failure?.let { "Failed to load groups" }
            } finally {
                _isLoading.value = false
            }
        }
    }
}
