package com.vrcx.android.data.db

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        VrcxDatabase::class.java,
    )

    // Schemas are only exported from version 2 onwards, so the 1 -> 2 hop has no
    // JSON to validate against and keeps the hand-written fixture below.
    @Test
    fun `migrations 2 to 6 match the exported schemas`() {
        helper.createDatabase(SCHEMA_DB, 2).close()

        helper.runMigrationsAndValidate(SCHEMA_DB, 3, true, MIGRATION_2_3).close()
        helper.runMigrationsAndValidate(SCHEMA_DB, 4, true, MIGRATION_3_4).close()
        helper.runMigrationsAndValidate(SCHEMA_DB, 5, true, MIGRATION_4_5).close()
        helper.runMigrationsAndValidate(SCHEMA_DB, 6, true, MIGRATION_5_6).close()
    }

    @Test
    fun `migration 1 to 2 adds friend_notify table without touching existing data`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration-test.db"
        context.deleteDatabase(dbName)

        val openHelper = FrameworkSQLiteOpenHelperFactory().create(
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

        openHelper.writableDatabase.use { db ->
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
        val openHelper = FrameworkSQLiteOpenHelperFactory().create(
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

        openHelper.writableDatabase.use { db ->
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

    // Room's schema validation compares columns but not indices, so the index half of
    // this migration has to be asserted against sqlite_master by hand.
    @Test
    fun `migration 5 to 6 rebuilds notes and moves the indices`() {
        val db = helper.createDatabase(SCHEMA_DB, 5)
        db.execSQL(
            """
            INSERT INTO `notes` (`compositeId`, `ownerUserId`, `odUserId`, `displayName`, `note`, `createdAt`)
            VALUES ('usr_me:usr_friend', 'usr_me', 'usr_me:usr_friend', 'Friend', 'hello', '2026-03-19T00:00:00Z')
            """.trimIndent()
        )
        db.close()

        helper.runMigrationsAndValidate(SCHEMA_DB, 6, true, MIGRATION_5_6).use { migrated ->
            migrated.query("SELECT `compositeId`, `ownerUserId`, `note` FROM notes").use { cursor ->
                assertEquals(1, cursor.count)
                cursor.moveToFirst()
                assertEquals("usr_me:usr_friend", cursor.getString(0))
                assertEquals("usr_me", cursor.getString(1))
                assertEquals("hello", cursor.getString(2))
            }

            assertEquals(1, countRows(migrated, indexCountQuery("index_notes_ownerUserId")))
            assertEquals(1, countRows(migrated, indexCountQuery("index_memos_ownerUserId")))
            assertEquals(0, countRows(migrated, indexCountQuery("index_friend_log_history_createdAt")))
        }
    }

    private fun tableCountQuery(table: String): String =
        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '$table'"

    private fun indexCountQuery(index: String): String =
        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = '$index'"

    private fun countRows(db: SupportSQLiteDatabase, sql: String): Int {
        db.query(sql).use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(0)
        }
    }

    private companion object {
        const val SCHEMA_DB = "migration-schema-test.db"
    }
}
