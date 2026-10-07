package com.example.security

import android.util.Base64
import com.example.data.models.VaultEntryEntity
import com.example.domain.models.CustomField
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * In-memory steps of the legacy-vault upgrade (F18). Nothing here reads or writes storage:
 * the caller reads the rows, and writes only after every step here has succeeded.
 */
object LegacyVaultMigration {

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    /** A row whose every field was decrypted. [fields] follow [encryptedFields] order. */
    class PlainRow(val original: VaultEntryEntity, val fields: List<String>)

    class Decrypted(
        /** Rows whose every field decrypted: these are migrated. */
        val readable: List<PlainRow>,
        /** Rows with any field no legacy key opens: left byte-for-byte as they are. */
        val unreadable: List<VaultEntryEntity>
    ) {
        /**
         * False when rows with content exist but not one of them could be read (no legacy key on
         * this device, or the wrong one). Then there is nothing to migrate and nothing is written.
         */
        val canMigrate: Boolean
            get() = (readable.map { it.original } + unreadable).none { hasContent(it) } ||
                readable.any { hasContent(it.original) }
    }

    /** The eight encrypted columns, in a fixed order. */
    fun encryptedFields(entity: VaultEntryEntity): List<String> = listOf(
        entity.titleEnc,
        entity.usernameEnc,
        entity.passwordEnc,
        entity.websiteEnc,
        entity.notesEnc,
        entity.categoryEnc,
        entity.tagsEnc,
        entity.customFieldsEnc
    )

    /** True when the row has any non-empty encrypted field ("" decrypts under any key). */
    fun hasContent(entity: VaultEntryEntity): Boolean = encryptedFields(entity).any { it.isNotEmpty() }

    /**
     * Decrypts every row with the legacy [keys], trying each key per field. Throws
     * [LegacyKeyUnusableException] when a key fails for any reason other than a wrong key.
     */
    fun decryptAll(rows: List<VaultEntryEntity>, keys: List<SecretKey>): Decrypted {
        val readable = ArrayList<PlainRow>()
        val unreadable = ArrayList<VaultEntryEntity>()
        for (row in rows) {
            val plain = encryptedFields(row).map { LegacyVaultKeys.decryptField(it, keys) }
            if (plain.any { it == null } || !jsonFieldsValid(plain[6]!!, plain[7]!!)) {
                unreadable += row
            } else {
                readable += PlainRow(row, plain.map { it!! })
            }
        }
        return Decrypted(readable, unreadable)
    }

    /** Tags and custom fields must parse, or the row would break the entry list after the upgrade. */
    private fun jsonFieldsValid(tags: String, customFields: String): Boolean = try {
        if (tags.isNotEmpty()) Json.decodeFromString<List<String>>(tags)
        if (customFields.isNotEmpty()) Json.decodeFromString<List<CustomField>>(customFields)
        true
    } catch (e: Exception) {
        false
    }

    /**
     * The [rows] re-encrypted with [dek] (same format as [CryptoManager.encrypt]), every other
     * column unchanged. Each field is decrypted again and compared; throws on any mismatch.
     */
    fun reencrypt(rows: List<PlainRow>, dek: ByteArray): List<VaultEntryEntity> {
        val key = SecretKeySpec(dek, "AES")
        return rows.map { row ->
            val enc = row.fields.map { plain ->
                val stored = encryptStrict(plain, key)
                check(decryptWith(stored, key) == plain) { "Re-encryption check failed" }
                stored
            }
            row.original.copy(
                titleEnc = enc[0],
                usernameEnc = enc[1],
                passwordEnc = enc[2],
                websiteEnc = enc[3],
                notesEnc = enc[4],
                categoryEnc = enc[5],
                tagsEnc = enc[6],
                customFieldsEnc = enc[7]
            )
        }
    }

    /**
     * Rows with a field that doesn't decrypt with [dek] (the current vault key), for the retry with
     * the legacy keys at unlock. A row whose check fails in any other way is listed too: the legacy
     * step decides about it, and stops on such errors.
     */
    fun unreadableWithDek(rows: List<VaultEntryEntity>, dek: ByteArray): List<VaultEntryEntity> {
        val key = SecretKeySpec(dek, "AES")
        return rows.filter { !readableWith(it, key) }
    }

    /** True when every field of [row] decrypts with [key]. Any error counts as "not readable". */
    fun readableWith(row: VaultEntryEntity, key: SecretKey): Boolean = try {
        encryptedFields(row).all { decryptWith(it, key) != null }
    } catch (e: Exception) {
        false
    }

    /**
     * Later unlocks: of [unreadable] (rows the current [dek] can't read), the ones that now fully
     * decrypt with the legacy [keys] (or a mix of those and the DEK), re-encrypted with [dek] and
     * checked. Returns (originals, replacements) in the same order; rows that still fail are absent.
     * Throws [LegacyKeyUnusableException] like [decryptAll]: then nothing may be written.
     */
    fun recoverWithLegacyKeys(
        unreadable: List<VaultEntryEntity>,
        keys: List<SecretKey>,
        dek: ByteArray
    ): Pair<List<VaultEntryEntity>, List<VaultEntryEntity>> {
        if (unreadable.isEmpty() || keys.isEmpty()) return emptyList<VaultEntryEntity>() to emptyList()
        val readable = decryptAll(unreadable, keys + SecretKeySpec(dek, "AES")).readable
        if (readable.isEmpty()) return emptyList<VaultEntryEntity>() to emptyList()
        return readable.map { it.original } to reencrypt(readable, dek)
    }

    class DekCheck(
        /** Rows with content exist and at least one of them fully decrypts with the DEK. */
        val anyContentReadable: Boolean,
        /** Rows with content exist. */
        val hasContent: Boolean,
        /** Rows that don't fully decrypt with the DEK. */
        val unreadableCount: Int
    )

    /** How [rows] relate to [dek]: tells whether the upgrade transaction was committed. */
    fun checkWithDek(rows: List<VaultEntryEntity>, dek: ByteArray): DekCheck {
        val key = SecretKeySpec(dek, "AES")
        var anyContentReadable = false
        var unreadable = 0
        for (row in rows) {
            val readable = encryptedFields(row).all { decryptWith(it, key) != null }
            if (!readable) unreadable++ else if (hasContent(row)) anyContentReadable = true
        }
        return DekCheck(anyContentReadable, rows.any { hasContent(it) }, unreadable)
    }

    private fun encryptStrict(plainText: String, key: SecretKey): String {
        if (plainText.isEmpty()) return ""
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    private fun decryptWith(stored: String, key: SecretKey): String? = LegacyVaultKeys.decryptField(stored, listOf(key))
}
