package io.github.vrcxandroid.bridge.webapi

import io.github.vrcxandroid.bridge.SQLiteModule
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The jar's blob in the main database, exactly where upstream keeps it: table `cookies`, row `key = 'default'`
 * (WebApi.cs:150-238). All access goes through the SQLite lane.
 */
internal class SqliteCookieBlobStore(private val database: () -> SQLiteModule) : CookieBlobStore {
    override suspend fun load(): String? = database().runNative { session ->
        session.executeNonQuery(CREATE_TABLE, null)
        session.queryScalar(SELECT, KEY_ARGS) as? String
    }

    override suspend fun save(blob: String) {
        database().runNative { session ->
            session.executeNonQuery(CREATE_TABLE, null)
            session.executeNonQuery(
                UPSERT,
                buildJsonObject {
                    put("@key", KEY)
                    put("@value", blob)
                },
            )
        }
    }

    private companion object {
        const val KEY = "default"
        const val CREATE_TABLE = "CREATE TABLE IF NOT EXISTS `cookies` (`key` TEXT PRIMARY KEY, `value` TEXT)"
        const val SELECT = "SELECT `value` FROM `cookies` WHERE `key` = @key"
        const val UPSERT = "INSERT OR REPLACE INTO `cookies` (`key`, `value`) VALUES (@key, @value)"
        val KEY_ARGS = buildJsonObject { put("@key", KEY) }
    }
}
