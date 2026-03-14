package com.vrcx.android.ui.screen.friendlog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.db.dao.FriendLogDao
import com.vrcx.android.data.db.entity.FriendLogHistoryEntity
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class FriendLogViewModel @Inject constructor(
    authRepository: AuthRepository,
    friendLogDao: FriendLogDao,
) : ViewModel() {
    val history: StateFlow<List<FriendLogHistoryEntity>> = authRepository.authState
        .map { (it as? AuthState.LoggedIn)?.user?.id ?: "" }
        .flatMapLatest { uid ->
            if (uid.isEmpty()) flowOf(emptyList())
            else friendLogDao.getHistory(uid, limit = 200)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@Composable
fun FriendLogScreen(viewModel: FriendLogViewModel = hiltViewModel()) {
    val history by viewModel.history.collectAsState()

    if (history.isEmpty()) {
        Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("No friend log history", style = MaterialTheme.typography.bodyLarge)
            Text("History will appear as friends are added/removed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(history, key = { it.id }) { entry ->
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (entry.type == "Friend") Icons.Default.PersonAdd else Icons.Default.PersonRemove,
                        contentDescription = null,
                        tint = if (entry.type == "Friend") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.padding(8.dp))
                    Column {
                        Text(entry.displayName, style = MaterialTheme.typography.bodyLarge)
                        Text("${entry.type} • ${entry.createdAt.take(10)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
