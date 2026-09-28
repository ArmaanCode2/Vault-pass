package com.example.network.sync

import com.example.security.CryptoManager
import com.vaultpass.synccore.Hex
import com.vaultpass.synccore.PairKeyFormat
import com.vaultpass.synccore.PairKeyProtector
import com.vaultpass.synccore.SyncCrypto

/** Stores pair keys encrypted with the vault key (DEK); needs an unlocked vault. */
class AndroidPairKeyProtector(private val cryptoManager: CryptoManager) : PairKeyProtector {

    override fun seal(pairKey: ByteArray): String? {
        if (pairKey.size != SyncCrypto.KEY_BYTES) return null
        // encrypt() returns "" when the vault is locked or encryption fails.
        val sealed = cryptoManager.encrypt(Hex.encode(pairKey))
        return if (sealed.isEmpty()) null else PairKeyFormat.PREFIX + sealed
    }

    override fun open(stored: String): ByteArray? {
        if (!PairKeyFormat.isCurrent(stored)) return null
        val sealed = stored.removePrefix(PairKeyFormat.PREFIX)
        if (sealed.isEmpty()) return null
        val hex = cryptoManager.decrypt(sealed) ?: return null
        return try {
            Hex.decode(hex).takeIf { it.size == SyncCrypto.KEY_BYTES }
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
