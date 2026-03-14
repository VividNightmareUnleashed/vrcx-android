package com.vrcx.android.data.repository

import com.vrcx.android.data.api.AvatarApi
import com.vrcx.android.data.api.GroupApi
import com.vrcx.android.data.api.UserApi
import com.vrcx.android.data.api.WorldApi
import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.data.api.model.GroupSearchResult
import com.vrcx.android.data.api.model.UserSearchResult
import com.vrcx.android.data.api.model.World
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchRepository @Inject constructor(
    private val userApi: UserApi,
    private val worldApi: WorldApi,
    private val avatarApi: AvatarApi,
    private val groupApi: GroupApi,
) {
    suspend fun searchUsers(query: String, n: Int = 10, offset: Int = 0): List<UserSearchResult> {
        return userApi.getUsers(n = n, offset = offset, search = query)
    }

    suspend fun searchWorlds(query: String, n: Int = 10, offset: Int = 0): List<World> {
        return worldApi.getWorlds(n = n, offset = offset, search = query)
    }

    suspend fun searchAvatars(query: String, n: Int = 10, offset: Int = 0): List<Avatar> {
        return avatarApi.getAvatars(n = n, offset = offset, search = query)
    }

    suspend fun searchGroups(query: String, n: Int = 10, offset: Int = 0): List<GroupSearchResult> {
        return groupApi.searchGroups(n = n, offset = offset, query = query)
    }
}
