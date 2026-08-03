package com.vrcx.android.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `friend_notify` (
                `compositeId` TEXT NOT NULL,
                `ownerUserId` TEXT NOT NULL,
                `friendUserId` TEXT NOT NULL,
                PRIMARY KEY(`compositeId`)
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_friend_notify_ownerUserId`
            ON `friend_notify` (`ownerUserId`)
            """.trimIndent()
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            ALTER TABLE `notifications_v2`
            ADD COLUMN `responsesJson` TEXT NOT NULL DEFAULT ''
            """.trimIndent()
        )
        database.execSQL(
            """
            ALTER TABLE `notifications_v2`
            ADD COLUMN `ignoreDND` INTEGER NOT NULL DEFAULT 0
            """.trimIndent()
        )
        database.execSQL(
            """
            ALTER TABLE `notifications_v2`
            ADD COLUMN `relatedNotificationsId` TEXT NOT NULL DEFAULT ''
            """.trimIndent()
        )
        database.execSQL(
            """
            ALTER TABLE `notifications_v2`
            ADD COLUMN `responseDataJson` TEXT NOT NULL DEFAULT ''
            """.trimIndent()
        )
        database.execSQL(
            """
            ALTER TABLE `notifications_v2`
            ADD COLUMN `expiryAfterSeen` INTEGER
            """.trimIndent()
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        val tables = listOf("feed_gps", "feed_status", "feed_bio", "feed_avatar", "feed_online_offline")
        tables.forEach { table ->
            database.execSQL("DROP INDEX IF EXISTS `index_${table}_userId`")
            database.execSQL("DROP INDEX IF EXISTS `index_${table}_createdAt`")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_${table}_ownerUserId_id` ON `$table` (`ownerUserId`, `id`)")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_${table}_ownerUserId_userId_id` ON `$table` (`ownerUserId`, `userId`, `id`)")
        }
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        listOf(
            "moderation",
            "avatar_history",
            "mutual_graph_friends",
            "mutual_graph_links",
            "cache_avatar",
            "cache_world",
            "favorite_world",
            "favorite_avatar",
            "favorite_friend",
            "world_memos",
            "avatar_memos",
            "avatar_tags",
        ).forEach { table ->
            database.execSQL("DROP TABLE IF EXISTS `$table`")
        }
    }
}
