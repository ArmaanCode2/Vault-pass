package com.example

import android.content.ContentValues
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
import com.example.data.AppDatabase
import com.vaultpass.synccore.SyncRecords
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseMigrationTest {

    private val TEST_DB = "migration-test.db"

    @Test
    fun migrate1To2_addsIsDeletedAndDeletedAtColumns_andPreservesExistingData() {
        val context = ApplicationProvider.getApplicationContext<VaultPassApplication>()
        context.deleteDatabase(TEST_DB)

        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS vault_entries (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            titleEnc TEXT NOT NULL,
                            usernameEnc TEXT NOT NULL,
                            passwordEnc TEXT NOT NULL,
                            websiteEnc TEXT NOT NULL,
                            notesEnc TEXT NOT NULL,
                            categoryEnc TEXT NOT NULL,
                            tagsEnc TEXT NOT NULL,
                            customFieldsEnc TEXT NOT NULL,
                            isFavorite INTEGER NOT NULL,
                            timestamp INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val v1Db = helper.writableDatabase

        // Insert a sample record in v1
        val values = ContentValues().apply {
            put("id", 42)
            put("titleEnc", "EncryptedTitle")
            put("usernameEnc", "EncryptedUser")
            put("passwordEnc", "EncryptedPassword")
            put("websiteEnc", "https://example.com")
            put("notesEnc", "EncryptedNotes")
            put("categoryEnc", "Logins")
            put("tagsEnc", "[]")
            put("customFieldsEnc", "[]")
            put("isFavorite", 1)
            put("timestamp", 1600000000000L)
        }
        v1Db.insert("vault_entries", 0, values)

        // Run Migration 1 -> 2
        AppDatabase.MIGRATION_1_2.migrate(v1Db)

        // Query the migrated database
        val cursor = v1Db.query("SELECT * FROM vault_entries WHERE id = 42")
        assertTrue("Migrated entry must exist", cursor.moveToFirst())

        val idIndex = cursor.getColumnIndex("id")
        val titleIndex = cursor.getColumnIndex("titleEnc")
        val isDeletedIndex = cursor.getColumnIndex("isDeleted")
        val deletedAtIndex = cursor.getColumnIndex("deletedAt")

        assertTrue("isDeleted column must exist", isDeletedIndex != -1)
        assertTrue("deletedAt column must exist", deletedAtIndex != -1)

        assertEquals(42, cursor.getInt(idIndex))
        assertEquals("EncryptedTitle", cursor.getString(titleIndex))
        assertEquals("Default isDeleted must be 0", 0, cursor.getInt(isDeletedIndex))
        assertTrue("Default deletedAt must be NULL", cursor.isNull(deletedAtIndex))

        cursor.close()
        v1Db.close()
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun migrate2To3_givesEveryEntryADistinctSyncId_andAddsTheTombstoneTable() {
        val context = ApplicationProvider.getApplicationContext<VaultPassApplication>()
        context.deleteDatabase(TEST_DB)

        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB)
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // As in app/schemas/com.example.data.AppDatabase/2.json
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `vault_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`titleEnc` TEXT NOT NULL, `usernameEnc` TEXT NOT NULL, `passwordEnc` TEXT NOT NULL, " +
                            "`websiteEnc` TEXT NOT NULL, `notesEnc` TEXT NOT NULL, `categoryEnc` TEXT NOT NULL, " +
                            "`tagsEnc` TEXT NOT NULL, `customFieldsEnc` TEXT NOT NULL, `isFavorite` INTEGER NOT NULL, " +
                            "`timestamp` INTEGER NOT NULL, `isDeleted` INTEGER NOT NULL, `deletedAt` INTEGER)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val v2Db = helper.writableDatabase
        for (id in 1..3) {
            v2Db.insert("vault_entries", 0, ContentValues().apply {
                put("id", id)
                put("titleEnc", "EncryptedTitle$id")
                put("usernameEnc", "EncryptedUser")
                put("passwordEnc", "EncryptedPassword")
                put("websiteEnc", "")
                put("notesEnc", "")
                put("categoryEnc", "")
                put("tagsEnc", "[]")
                put("customFieldsEnc", "[]")
                put("isFavorite", 0)
                put("timestamp", 1600000000000L + id)
                put("isDeleted", if (id == 3) 1 else 0)
                if (id == 3) put("deletedAt", 1700000000000L)
            })
        }

        AppDatabase.MIGRATION_2_3.migrate(v2Db)

        val syncIds = mutableMapOf<Int, String>()
        v2Db.query("SELECT id, titleEnc, isDeleted, deletedAt, syncId FROM vault_entries ORDER BY id").use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getInt(0)
                assertEquals("EncryptedTitle$id", cursor.getString(1))
                assertEquals(if (id == 3) 1 else 0, cursor.getInt(2))
                if (id == 3) assertEquals(1700000000000L, cursor.getLong(3)) else assertTrue(cursor.isNull(3))
                syncIds[id] = cursor.getString(4)
            }
        }
        assertEquals(setOf(1, 2, 3), syncIds.keys)
        assertEquals("Every row (recycle bin included) gets its own syncId", 3, syncIds.values.toSet().size)
        assertTrue(syncIds.values.all { SyncRecords.isValidSyncId(it) && java.util.UUID.fromString(it).toString() == it })

        v2Db.query("SELECT sql FROM sqlite_master WHERE type = 'index' AND name = 'index_vault_entries_syncId'").use { cursor ->
            assertTrue("Unique syncId index must exist", cursor.moveToFirst())
            assertTrue(cursor.getString(0).contains("UNIQUE"))
        }
        try {
            v2Db.execSQL("UPDATE vault_entries SET syncId = '${syncIds[1]}' WHERE id = 2")
            fail("Duplicate syncIds must be rejected")
        } catch (expected: android.database.sqlite.SQLiteConstraintException) {
        }
        v2Db.query("SELECT COUNT(*) FROM sync_tombstones").use { cursor ->
            assertTrue("Tombstone table must exist", cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        v2Db.version = 3
        v2Db.close()

        // Room itself must accept the migrated schema (it validates it against the entities).
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = roomDb.vaultDao()
            runBlocking {
                assertEquals(syncIds[1], dao.getEntryById(1)?.syncId)
                assertEquals(3, dao.getEntryBySyncId(syncIds[3]!!)?.id)
                assertTrue(dao.getAllTombstones().isEmpty())
            }
        } finally {
            roomDb.close()
            context.deleteDatabase(TEST_DB)
        }
    }

    /** Every column of every row of [table], in rowid order, exactly as stored. */
    private fun dumpTable(db: SupportSQLiteDatabase, table: String): List<Map<String, Any?>> {
        val rows = mutableListOf<Map<String, Any?>>()
        db.query("SELECT * FROM `$table` ORDER BY rowid").use { cursor ->
            while (cursor.moveToNext()) {
                rows += (0 until cursor.columnCount).associate { index ->
                    cursor.getColumnName(index) to when (cursor.getType(index)) {
                        android.database.Cursor.FIELD_TYPE_NULL -> null
                        android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                        else -> cursor.getString(index)
                    }
                }
            }
        }
        return rows
    }

    @Test
    fun migrate3To4_keepsEveryEntryAndTombstone_andAddsAnEmptyAppLinkTable() {
        val context = ApplicationProvider.getApplicationContext<VaultPassApplication>()
        context.deleteDatabase(TEST_DB)

        // A v3 database exactly as Room created it (app/schemas/com.example.data.AppDatabase/3.json).
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB)
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `vault_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`titleEnc` TEXT NOT NULL, `usernameEnc` TEXT NOT NULL, `passwordEnc` TEXT NOT NULL, " +
                            "`websiteEnc` TEXT NOT NULL, `notesEnc` TEXT NOT NULL, `categoryEnc` TEXT NOT NULL, " +
                            "`tagsEnc` TEXT NOT NULL, `customFieldsEnc` TEXT NOT NULL, `isFavorite` INTEGER NOT NULL, " +
                            "`timestamp` INTEGER NOT NULL, `isDeleted` INTEGER NOT NULL, `deletedAt` INTEGER, `syncId` TEXT NOT NULL)"
                    )
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_vault_entries_syncId` ON `vault_entries` (`syncId`)")
                    db.execSQL("CREATE TABLE IF NOT EXISTS `sync_tombstones` (`syncId` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL, PRIMARY KEY(`syncId`))")
                    db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                    db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'efba1d9a604a0b1ab7f3ca41fd14c22f')")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val v3Db = helper.writableDatabase
        for (id in listOf(1, 2, 3, 7, 40)) {
            v3Db.insert("vault_entries", 0, ContentValues().apply {
                put("id", id)
                put("titleEnc", "EncryptedTitle$id")
                put("usernameEnc", if (id == 2) "" else "EncryptedUser$id")
                put("passwordEnc", "EncryptedPassword$id")
                put("websiteEnc", if (id == 7) "EncryptedAndroidAppWebsite" else "")
                put("notesEnc", "Notes with ünïcödé and 'quotes' $id")
                put("categoryEnc", "")
                put("tagsEnc", "[]")
                put("customFieldsEnc", "[]")
                put("isFavorite", if (id % 2 == 0) 1 else 0)
                put("timestamp", 1600000000000L + id)
                put("isDeleted", if (id == 3) 1 else 0)
                if (id == 3) put("deletedAt", 1700000000000L)
                put("syncId", "00000000-0000-4000-8000-0000000000%02d".format(id))
            })
        }
        for (n in 1..2) {
            v3Db.insert("sync_tombstones", 0, ContentValues().apply {
                put("syncId", "tombstone-$n")
                put("deletedAt", 1650000000000L + n)
            })
        }
        val entriesBefore = dumpTable(v3Db, "vault_entries")
        val tombstonesBefore = dumpTable(v3Db, "sync_tombstones")
        assertEquals(5, entriesBefore.size)
        v3Db.close()

        // Room runs MIGRATION_3_4 itself and validates the result against the v4 entities.
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()
        try {
            val db = roomDb.openHelper.writableDatabase
            assertEquals(4, db.version)
            assertEquals("Every entry (recycle bin included) is unchanged", entriesBefore, dumpTable(db, "vault_entries"))
            assertEquals("Every tombstone is unchanged", tombstonesBefore, dumpTable(db, "sync_tombstones"))
            assertTrue("The app link table starts empty", dumpTable(db, "autofill_app_links").isEmpty())

            val dao = roomDb.vaultDao()
            runBlocking {
                assertEquals(5, dao.countAllEntities())
                assertEquals(3, dao.getEntryBySyncId("00000000-0000-4000-8000-000000000003")?.id)
                assertTrue(dao.getAllAppLinks().isEmpty())
                dao.insertAppLink(com.example.data.models.AutofillAppLinkEntity("com.example.bank", "00000000-0000-4000-8000-000000000001", 1L))
                assertEquals(listOf("00000000-0000-4000-8000-000000000001"), dao.getLinkedSyncIds("com.example.bank"))
            }
        } finally {
            roomDb.close()
            context.deleteDatabase(TEST_DB)
        }
    }
}
