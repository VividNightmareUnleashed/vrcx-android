package com.vrcx.android.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DatabaseMigrationTest {
    @Test
    fun `migration 1 to 2 adds friend_notify table without touching existing data`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration-test.db"
        context.deleteDatabase(dbName)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `notes` (
                                `compositeId` TEXT NOT NULL,
                                `ownerUserId` TEXT NOT NULL,
                                `odUserId` TEXT NOT NULL,
                                `displayName` TEXT NOT NULL,
                                `note` TEXT NOT NULL,
                                `createdAt` TEXT NOT NULL,
                                PRIMARY KEY(`compositeId`)
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            INSERT INTO `notes` (`compositeId`, `ownerUserId`, `odUserId`, `displayName`, `note`, `createdAt`)
                            VALUES ('usr_me:usr_friend', 'usr_me', 'usr_me:usr_friend', 'Friend', 'hello', '2026-03-19T00:00:00Z')
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build()
        )

        helper.writableDatabase.use { db ->
            MIGRATION_1_2.migrate(db)

            assertEquals(1, countRows(db, "SELECT COUNT(*) FROM notes"))
            assertEquals(
                1,
                countRows(
                    db,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'friend_notify'",
                ),
            )
            assertEquals(
                1,
                countRows(
                    db,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_friend_notify_ownerUserId'",
                ),
            )
        }

        context.deleteDatabase(dbName)
    }

    @Test
    fun `migration 4 to 5 drops unreachable tables and preserves live data`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration-4-5-test.db"
        context.deleteDatabase(dbName)

        val droppedTables = listOf(
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
        )
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        droppedTables.forEach { table ->
                            db.execSQL("CREATE TABLE `$table` (`id` TEXT NOT NULL PRIMARY KEY)")
                            db.execSQL("INSERT INTO `$table` (`id`) VALUES ('legacy')")
                        }
                        db.execSQL("CREATE TABLE `notes` (`id` TEXT NOT NULL PRIMARY KEY)")
                        db.execSQL("CREATE TABLE `memos` (`id` TEXT NOT NULL PRIMARY KEY)")
                        db.execSQL("CREATE TABLE `friend_notify` (`id` TEXT NOT NULL PRIMARY KEY)")
                        db.execSQL("INSERT INTO `notes` (`id`) VALUES ('note')")
                        db.execSQL("INSERT INTO `memos` (`id`) VALUES ('memo')")
                        db.execSQL("INSERT INTO `friend_notify` (`id`) VALUES ('notify')")
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build()
        )

        helper.writableDatabase.use { db ->
            MIGRATION_4_5.migrate(db)

            droppedTables.forEach { table ->
                assertEquals(0, countRows(db, tableCountQuery(table)))
            }
            assertEquals(1, countRows(db, "SELECT COUNT(*) FROM notes"))
            assertEquals(1, countRows(db, "SELECT COUNT(*) FROM memos"))
            assertEquals(1, countRows(db, "SELECT COUNT(*) FROM friend_notify"))
        }

        context.deleteDatabase(dbName)
    }

    private fun tableCountQuery(table: String): String =
        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '$table'"

    private fun countRows(db: SupportSQLiteDatabase, sql: String): Int {
        db.query(sql).use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(0)
        }
    }
}
