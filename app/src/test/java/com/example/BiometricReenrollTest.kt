package com.example

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Base64
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.example.security.BiometricCryptoHelper
import com.example.security.BiometricCryptoHelper.DecryptCipher
import com.example.security.BiometricPromptManager
import com.example.security.VaultSessionManager
import com.example.ui.VaultViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** F14: after a fingerprint change, biometric unlock turns itself off and can be turned on again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BiometricReenrollTest {

    /** Stands in for AndroidKeyStore: an in-memory key that a "fingerprint change" invalidates. */
    private class FakeKeyStore : BiometricCryptoHelper.KeyStoreOps {
        var key: ByteArray? = null
        var invalidated = false
        var created = 0
        var deleted = 0
        // Like AndroidKeyStore: creating keeps an existing key, invalidated or not. Only a delete first
        // gives a fresh one, so the tests below catch a helper that stops deleting.
        override fun createKey() {
            if (key != null) return
            key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            invalidated = false
            created++
        }
        override fun cipher(mode: Int, iv: ByteArray?): Cipher? {
            val k = key ?: return null
            if (invalidated) throw KeyPermanentlyInvalidatedException()
            return Cipher.getInstance("AES/GCM/NoPadding").apply {
                if (iv == null) init(mode, SecretKeySpec(k, "AES")) else init(mode, SecretKeySpec(k, "AES"), GCMParameterSpec(128, iv))
            }
        }
        override fun deleteKey() {
            key = null
            deleted++
        }
    }

    private lateinit var app: VaultPassApplication
    private lateinit var viewModel: VaultViewModel
    private val keyStore = FakeKeyStore()
    private lateinit var realKeyStore: BiometricCryptoHelper.KeyStoreOps
    private val settings get() = app.container.settingsRepository
    private val dek = ByteArray(32).also { SecureRandom().nextBytes(it) }

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        VaultSessionManager.resetForTesting()
        app.getSharedPreferences("vaultpass_sync_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        settings.setBiometricEnabled(false)
        realKeyStore = BiometricCryptoHelper.keyStoreOps
        BiometricCryptoHelper.keyStoreOps = keyStore
        viewModel = VaultViewModel(app.container.vaultRepository, settings)
        app.container.vaultRepository.injectSoftwareDek(dek.clone()) // an unlocked vault
    }

    @After
    fun tearDown() = runBlocking {
        BiometricCryptoHelper.keyStoreOps = realKeyStore
        settings.setBiometricEnabled(false)
        app.container.vaultRepository.clearSoftwareDek()
        VaultSessionManager.resetForTesting()
    }

    /** What BiometricPrompt hands back after a successful authentication. */
    private fun enroll() = runBlocking {
        val cipher = viewModel.prepareBiometricEnrollment()
        assertNotNull(cipher)
        assertTrue(viewModel.completeBiometricEnrollment(cipher!!))
    }

    private fun unwrapWithBiometrics(): DecryptCipher {
        val combined = Base64.decode(settings.getDekBioWrappedSync()!!, Base64.NO_WRAP)
        return BiometricCryptoHelper.getDecryptCipherForBiometric(combined.copyOfRange(0, 12))
    }

    @Test
    fun enroll_wrapsTheVaultKey() = runBlocking {
        enroll()
        assertTrue(settings.isBiometricEnabled.first())
        val result = unwrapWithBiometrics()
        assertTrue(result is DecryptCipher.Ready)
        val combined = Base64.decode(settings.getDekBioWrappedSync()!!, Base64.NO_WRAP)
        assertArrayEquals(dek, (result as DecryptCipher.Ready).cipher.doFinal(combined.copyOfRange(12, combined.size)))
    }

    @Test
    fun fingerprintChange_isReported_andReEnablingWorksWithANewKey() = runBlocking {
        enroll()
        keyStore.invalidated = true // a fingerprint was added in Android settings
        assertEquals(DecryptCipher.Invalidated, unwrapWithBiometrics())

        viewModel.disableBiometricUnlock() // what the prompt does on Invalidated
        assertFalse("The lock screen stops prompting", settings.isBiometricEnabled.first())
        assertNull(settings.getDekBioWrappedSync())
        assertNull("The dead key is deleted", keyStore.key)

        enroll() // turned on again in Settings
        assertEquals("A new key every time", 2, keyStore.created)
        assertTrue(settings.isBiometricEnabled.first())
        assertTrue(unwrapWithBiometrics() is DecryptCipher.Ready)
    }

    @Test
    fun reEnablingWithoutTurningOff_stillGetsAFreshKey() = runBlocking {
        enroll()
        keyStore.invalidated = true
        enroll() // the user just turns the switch on again
        assertTrue("The invalidated key was replaced", unwrapWithBiometrics() is DecryptCipher.Ready)
    }

    @Test
    fun aKeyThatIsGone_countsAsInvalidated() = runBlocking {
        enroll()
        keyStore.key = null // e.g. the device's screen lock was removed, which deletes Keystore keys
        assertEquals(DecryptCipher.Invalidated, unwrapWithBiometrics())
    }

    @Test
    fun turningOff_deletesTheWrappedKeyAndTheKeystoreKey() = runBlocking {
        enroll()
        viewModel.disableBiometricUnlock()
        assertFalse(settings.isBiometricEnabled.first())
        assertNull(settings.getDekBioWrappedSync())
        assertNull("The Keystore key is deleted", keyStore.key)
    }

    @Test
    fun prompt_onAnInvalidatedKey_turnsBiometricsOff() = runBlocking {
        enroll()
        keyStore.invalidated = true
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        var invalidatedCalled = false
        var error: String? = null

        BiometricPromptManager(activity).showBiometricPrompt(
            settingsRepository = settings,
            onSuccess = {},
            onError = { error = it },
            onInvalidated = { invalidatedCalled = true; viewModel.disableBiometricUnlock() }
        )
        // The prompt runs on the main looper and waits for DataStore in between: let both finish.
        val deadline = System.currentTimeMillis() + 5_000
        while (error == null && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }

        assertTrue(invalidatedCalled)
        assertNotNull("The user is told what happened", error)
        assertTrue(error!!, error!!.contains("turn biometric unlock on again"))
        assertFalse(settings.isBiometricEnabled.first())
    }

    @Test
    fun prompt_withTheFlagOnButNoWrap_turnsBiometricsOff() = runBlocking {
        settings.setBiometricEnabled(true) // e.g. an older build crashed between the two writes
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        var invalidatedCalled = false
        var error: String? = null

        BiometricPromptManager(activity).showBiometricPrompt(
            settingsRepository = settings,
            onSuccess = {},
            onError = { error = it },
            onInvalidated = { invalidatedCalled = true; viewModel.disableBiometricUnlock() }
        )
        val deadline = System.currentTimeMillis() + 5_000
        while (error == null && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }

        assertTrue("No endless prompt on every lock", invalidatedCalled)
        assertFalse(settings.isBiometricEnabled.first())
    }
}
