package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.models.AutofillAppLinkEntity
import com.example.data.models.SyncTombstoneEntity
import com.example.data.models.VaultEntryEntity

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

@Database(
    entities = [VaultEntryEntity::class, SyncTombstoneEntity::class, AutofillAppLinkEntity::class],
    version = 4,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun vaultDao(): VaultDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vault_entries ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE vault_entries ADD COLUMN deletedAt INTEGER DEFAULT NULL")
            }
        }

        // Sync data model v2: every entry (recycle bin included) gets its own syncId.
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vault_entries ADD COLUMN syncId TEXT NOT NULL DEFAULT ''")
                val ids = mutableListOf<Long>()
                db.query("SELECT id FROM vault_entries").use { cursor ->
                    while (cursor.moveToNext()) ids += cursor.getLong(0)
                }
                ids.forEach { id ->
                    db.execSQL("UPDATE vault_entries SET syncId = ? WHERE id = ?", arrayOf<Any>(UUID.randomUUID().toString(), id))
                }
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_vault_entries_syncId` ON `vault_entries` (`syncId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `sync_tombstones` (`syncId` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL, PRIMARY KEY(`syncId`))")
            }
        }

        // Autofill app links (local only). Creates the new table and changes nothing else.
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `autofill_app_links` (`packageName` TEXT NOT NULL, `syncId` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`packageName`, `syncId`))"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vaultpass_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
