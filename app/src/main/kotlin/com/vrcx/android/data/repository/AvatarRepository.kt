package com.vrcx.android.data.repository

import com.vrcx.android.data.api.AvatarApi
import com.vrcx.android.data.api.model.Avatar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AvatarRepository @Inject constructor(
    private val avatarApi: AvatarApi,
) {
    private val _myAvatars = MutableStateFlow<List<Avatar>>(emptyList())
    val myAvatars: StateFlow<List<Avatar>> = _myAvatars.asStateFlow()

    suspend fun loadMyAvatars() {
        _myAvatars.value = avatarApi.getAvatars(user = "me", releaseStatus = "all", n = 100)
    }

    suspend fun selectAvatar(avatarId: String) {
        avatarApi.selectAvatar(avatarId)
    }

    suspend fun getAvatar(avatarId: String): Avatar = avatarApi.getAvatar(avatarId)
}
