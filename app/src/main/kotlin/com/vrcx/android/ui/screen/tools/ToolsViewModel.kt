package com.vrcx.android.ui.screen.tools

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.data.repository.UserDetailRepository
import com.vrcx.android.data.util.runCatchingCancellable
import com.vrcx.android.di.IoDispatcher
import com.vrcx.android.ui.common.whileUiSubscribed
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class FriendExportFormat { CSV, JSON }

@HiltViewModel
class ToolsViewModel @Inject constructor(
    preferences: VrcxPreferences,
    private val authRepository: AuthRepository,
    private val friendRepository: FriendRepository,
    private val userDetailRepository: UserDetailRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val _targetId = MutableStateFlow("")
    val targetId: StateFlow<String> = _targetId.asStateFlow()

    val backgroundServiceEnabled =
        preferences.backgroundServiceEnabled.stateIn(viewModelScope, whileUiSubscribed, true)

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private val _exportMessage = MutableStateFlow<String?>(null)
    val exportMessage: StateFlow<String?> = _exportMessage.asStateFlow()

    fun updateTargetId(value: String) {
        _targetId.value = value
    }

    internal fun exportFriends(context: Context, uri: Uri, format: FriendExportFormat) {
        if (_isExporting.value) return
        viewModelScope.launch {
            _isExporting.value = true
            _exportMessage.value = null
            try {
                _exportMessage.value =
                    runCatchingCancellable {
                        val rows = buildExportRows()
                        val content =
                            when (format) {
                                FriendExportFormat.CSV -> FriendListExport.toCsv(rows)
                                FriendExportFormat.JSON -> FriendListExport.toJson(rows)
                            }
                        writeExport(context, uri, content)
                        "Exported ${rows.size} friends."
                    }.getOrElse { failure ->
                        "Export failed: ${failure.message ?: "Unknown error"}"
                    }
            } finally {
                _isExporting.value = false
            }
        }
    }

    private suspend fun buildExportRows(): List<FriendExportRow> {
        val initialUser =
            (authRepository.authState.value as? AuthState.LoggedIn)?.user
                ?: error("No logged-in user")
        val ownerUserId = initialUser.id
        val memoMap = userDetailRepository.loadMemos(ownerUserId)
        val currentUser =
            (authRepository.authState.value as? AuthState.LoggedIn)
                ?.user
                ?.takeIf { it.id == ownerUserId }
                ?: error("Account changed while preparing export")
        return FriendListExport.rows(
            currentUserFriends = currentUser.friends,
            friends = friendRepository.friends.value,
            memos = memoMap,
        )
    }

    private suspend fun writeExport(context: Context, uri: Uri, content: String) {
        withContext(ioDispatcher) {
            val stream =
                context.applicationContext.contentResolver.openOutputStream(uri, "wt")
                    ?: error("Unable to open the selected destination")
            OutputStreamWriter(stream, StandardCharsets.UTF_8).use { it.write(content) }
        }
    }
}
