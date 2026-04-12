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
