# Vault Pass Mobile - Database Schema

## Database Type
SQLite via AndroidX Room ORM. The database file itself is unencrypted, relying on application-layer field encryption.

## Tables/Entities

### vault_entries
| Column | Type | Constraints | Encrypted | Notes |
|--------|------|-------------|-----------|-------|
| `id` | `INTEGER` | `PRIMARY KEY (autoGenerate = true)` | No | Auto-incrementing unique identifier |
| `titleEnc` | `TEXT` | `NOT NULL` | Yes | Encrypted payload of the entry's title |
| `usernameEnc` | `TEXT` | `NOT NULL` | Yes | Encrypted payload of the username/email |
| `passwordEnc` | `TEXT` | `NOT NULL` | Yes | Encrypted payload of the actual password |
| `websiteEnc` | `TEXT` | `NOT NULL` | Yes | Encrypted payload of the URL/URI |
| `notesEnc` | `TEXT` | `NOT NULL` | Yes | Encrypted payload of secure notes |
| `categoryEnc` | `TEXT` | `NOT NULL` | Yes | Encrypted payload of the category |
| `tagsEnc` | `TEXT` | `NOT NULL` | Yes | Encrypted JSON Array representing a list of tags |
| `customFieldsEnc` | `TEXT` | `NOT NULL` | Yes | Encrypted JSON Array representing key-value custom fields |
| `isFavorite` | `INTEGER` | `NOT NULL` | No | Boolean flag (SQLite stores as 0/1) indicating user favorite status |
| `timestamp` | `INTEGER` | `NOT NULL` | No | Epoch timestamp of creation or last modification |
| `isDeleted` | `INTEGER` | `NOT NULL DEFAULT 0` | No | Boolean flag indicating if the entry is in the Recycle Bin (Added in DB Version 2) |
| `deletedAt` | `INTEGER` | `DEFAULT NULL` | No | Epoch timestamp of when the entry was soft-deleted (Added in DB Version 2) |
| `syncId` | `TEXT` | `NOT NULL DEFAULT ''`, unique index | No | Identifier the entry keeps on every synced device. The version 3 migration gave each existing row a random UUID (Added in DB Version 3) |

### sync_tombstones
| Column | Type | Constraints | Encrypted | Notes |
|--------|------|-------------|-----------|-------|
| `syncId` | `TEXT` | `PRIMARY KEY`, `NOT NULL` | No | `syncId` of an entry that was permanently deleted |
| `deletedAt` | `INTEGER` | `NOT NULL` | No | When the entry was deleted |

A row is written when an entry is permanently deleted, by hand or by the 7-day Recycle Bin cleanup, so the next LAN sync can pass the deletion on to the other device. A sync that brings the entry back removes its row. Added in DB Version 3.

### autofill_app_links
| Column | Type | Constraints | Encrypted | Notes |
|--------|------|-------------|-----------|-------|
| `packageName` | `TEXT` | `NOT NULL`, part of `PRIMARY KEY(packageName, syncId)` | No | Android package of the native app the entry was picked for |
| `syncId` | `TEXT` | `NOT NULL`, part of the primary key | No | `syncId` of the linked entry |
| `createdAt` | `INTEGER` | `NOT NULL` | No | When the link was saved (epoch milliseconds) |

A row is written when the user picks an entry for a native app through autofill's "Search VaultPass…" item; autofill then offers that entry to the app directly. Browser packages (`AutofillCredentialMatcher.BROWSER_PACKAGE_NAMES`) and picks on web pages are never linked. The table is local to the device: it is not part of sync records, exports or backups. Links move with their entry when a sync renames its `syncId` (`renameAppLinks`) and are deleted when the entry is permanently deleted, by hand or by the Recycle Bin cleanup (`deleteAppLinksForSyncId`). Rows whose entry no longer exists are ignored. Added in DB Version 4.

### Code Location
- Entity Definitions: `com.example.data.models.VaultEntryEntity`, `com.example.data.models.SyncTombstoneEntity`, `com.example.data.models.AutofillAppLinkEntity`
- DAO: `com.example.data.VaultDao`
- Database Configuration and migrations (`MIGRATION_1_2`, `MIGRATION_2_3`, `MIGRATION_3_4`): `com.example.data.AppDatabase`. `MIGRATION_3_4` only creates `autofill_app_links` and leaves every other table unchanged.
- Exported schemas: `app/schemas/com.example.data.AppDatabase/` (`2.json`, `3.json`, `4.json`)

## Relationships
- None. Entries live in a single table (`vault_entries`). One-to-many properties (like Tags and Custom Fields) are serialized as JSON strings and encrypted directly into single columns rather than using relational child tables. `sync_tombstones` and `autofill_app_links` are linked to entries only by `syncId` value, with no foreign key: tombstones outlive the entries they describe, and app links are cleaned up by the repository instead.

## Indexes
```sql
-- Extracted from Room schema generation
CREATE TABLE IF NOT EXISTS `vault_entries` (
  `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  /* ... other columns ... */
)
CREATE UNIQUE INDEX IF NOT EXISTS `index_vault_entries_syncId` ON `vault_entries` (`syncId`)
```

## Query Patterns
- **Get all active passwords:** `SELECT * FROM vault_entries WHERE isDeleted = 0 ORDER BY timestamp DESC` (Location: `VaultDao.getAllEntries()`)
- **Get specific password:** `SELECT * FROM vault_entries WHERE id = :id` (Location: `VaultDao.getEntryById()`)
- **Soft delete password:** `UPDATE vault_entries SET isDeleted = 1, deletedAt = :timestamp WHERE id = :id` (Location: `VaultDao.softDeleteEntry()`)
- **Permanent delete password:** `DELETE FROM vault_entries WHERE id = :id` (Location: `VaultDao.permanentlyDeleteEntry()`)
- **Get recycle bin entries:** `SELECT * FROM vault_entries WHERE isDeleted = 1 ORDER BY deletedAt DESC` (Location: `VaultDao.getRecycleBinEntries()`)
- **Cleanup old recycle bin entries:** `DELETE FROM vault_entries WHERE isDeleted = 1 AND deletedAt <= :cutoffTimestamp` (Location: `VaultDao.deleteOldRecycleBinEntries()`)
- **Entries linked to an app (autofill):** `SELECT syncId FROM autofill_app_links WHERE packageName = :packageName` (Location: `VaultDao.getLinkedSyncIds()`)
- **Save an app link:** `INSERT OR REPLACE` of an `AutofillAppLinkEntity` (Location: `VaultDao.insertAppLink()`)

## Data Encryption Strategy
- **Encryption Method:** AES-256-GCM implemented at the Repository layer (`com.example.repository.VaultRepository`).
- **Encrypted Fields:** `titleEnc`, `usernameEnc`, `passwordEnc`, `websiteEnc`, `notesEnc`, `categoryEnc`, `tagsEnc`, `customFieldsEnc`.
- **Unencrypted Fields:** 
  - `id`: Required unencrypted for SQLite Primary Key indexing and standard CRUD operations.
  - `isFavorite`: Kept unencrypted to allow fast SQL sorting/filtering of favorites without decrypting the entire database.
  - `timestamp`: Kept unencrypted to allow fast chronological SQL sorting (`ORDER BY timestamp DESC`).
  - `isDeleted` / `deletedAt`: Kept unencrypted to allow fast SQL filtering of active vs. recycled entries.
  - `syncId` and the `sync_tombstones` table: random identifiers with no vault content, kept unencrypted so a sync can match entries without decrypting the vault.
  - The `autofill_app_links` table: package names and `syncId`s are stored in plain text so the autofill service can look up linked entries by package. Anyone who can read the database file can see which apps have linked entries, but not the entries' contents.
