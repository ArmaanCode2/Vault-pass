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

### Code Location
- Entity Definition: `com.example.data.models.VaultEntryEntity`
- DAO: `com.example.data.VaultDao`
- Database Configuration: `com.example.data.AppDatabase`

## Relationships
- None. VaultPass utilizes a highly denormalized, single-table architecture (`vault_entries`). One-to-many properties (like Tags and Custom Fields) are serialized as JSON strings and encrypted directly into single columns rather than using relational child tables.

## Indexes
```sql
-- Extracted from Room schema generation
CREATE TABLE IF NOT EXISTS `vault_entries` (
  `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  /* ... other columns ... */
)
-- No additional @Index annotations are defined on the Entity.
```

## Query Patterns
- **Get all active passwords:** `SELECT * FROM vault_entries WHERE isDeleted = 0 ORDER BY timestamp DESC` (Location: `VaultDao.getAllEntries()`)
- **Get specific password:** `SELECT * FROM vault_entries WHERE id = :id` (Location: `VaultDao.getEntryById()`)
- **Soft delete password:** `UPDATE vault_entries SET isDeleted = 1, deletedAt = :timestamp WHERE id = :id` (Location: `VaultDao.softDeleteEntry()`)
- **Permanent delete password:** `DELETE FROM vault_entries WHERE id = :id` (Location: `VaultDao.permanentlyDeleteEntry()`)
- **Get recycle bin entries:** `SELECT * FROM vault_entries WHERE isDeleted = 1 ORDER BY deletedAt DESC` (Location: `VaultDao.getRecycleBinEntries()`)
- **Cleanup old recycle bin entries:** `DELETE FROM vault_entries WHERE isDeleted = 1 AND deletedAt <= :cutoffTimestamp` (Location: `VaultDao.deleteOldRecycleBinEntries()`)

## Data Encryption Strategy
- **Encryption Method:** AES-256-GCM implemented at the Repository layer (`com.example.repository.VaultRepository`).
- **Encrypted Fields:** `titleEnc`, `usernameEnc`, `passwordEnc`, `websiteEnc`, `notesEnc`, `categoryEnc`, `tagsEnc`, `customFieldsEnc`.
- **Unencrypted Fields:** 
  - `id`: Required unencrypted for SQLite Primary Key indexing and standard CRUD operations.
  - `isFavorite`: Kept unencrypted to allow fast SQL sorting/filtering of favorites without decrypting the entire database.
  - `timestamp`: Kept unencrypted to allow fast chronological SQL sorting (`ORDER BY timestamp DESC`).
  - `isDeleted` / `deletedAt`: Kept unencrypted to allow fast SQL filtering of active vs. recycled entries.
