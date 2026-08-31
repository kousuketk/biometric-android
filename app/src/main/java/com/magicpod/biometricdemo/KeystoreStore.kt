package com.magicpod.biometricdemo

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * A Keystore key that can only be used after a Class 3 (BIOMETRIC_STRONG) authentication,
 * which is what a banking app does. Handing the resulting [Cipher] to BiometricPrompt as a
 * CryptoObject is the part that a Class 2 sensor cannot satisfy, so this is the case that
 * decides whether the emulator is usable for that kind of app at all.
 */
object KeystoreStore {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "biometric-demo-key"
    const val PLAINTEXT = "s3cr3t-42"

    class KeyInvalidatedException(cause: Throwable) : Exception(cause)

    fun createKey() {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
            // The key is unusable until the user authenticates, every single time.
            .setUserAuthenticationRequired(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // The iOS counterpart of this flag is kSecAccessControlBiometryCurrentSet: enrolling or
            // removing a biometric permanently invalidates the key.
            builder.setInvalidatedByBiometricEnrollment(true)
        }
        generator.init(builder.build())
        generator.generateKey()
    }

    fun deleteKey() {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    fun hasKey(): Boolean {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return keyStore.containsAlias(KEY_ALIAS)
    }

    /**
     * @throws KeyInvalidatedException when the enrolled biometrics changed since the key was made
     */
    fun encryptCipher(): Cipher {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val key = keyStore.getKey(KEY_ALIAS, null) as SecretKey
        val cipher = Cipher.getInstance(
            "${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_CBC}/" +
                KeyProperties.ENCRYPTION_PADDING_PKCS7,
        )
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw KeyInvalidatedException(e)
        }
        return cipher
    }
}
