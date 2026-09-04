package com.example

import android.content.ContentValues
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
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
}
