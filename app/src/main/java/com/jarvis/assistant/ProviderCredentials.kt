package com.jarvis.assistant

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The preference slot is authenticated so a stored key cannot be moved to another provider. */
internal class ProviderCredentials(
    private val key: (create: Boolean) -> SecretKey? = AndroidCredentialKey::load,
    private val resetKey: () -> Unit = AndroidCredentialKey::reset,
) {
    fun decrypt(slot: String, encoded: String): String? = try {
        val sealed = Base64.decode(encoded, Base64.NO_WRAP)
        check(sealed.size >= 1 + IV_BYTES + TAG_BITS / 8 && sealed[0] == VERSION)
        val secret = key(false)
        if (secret == null) null else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, sealed, 1, IV_BYTES))
            cipher.updateAAD(slot.toByteArray())
            cipher.doFinal(sealed, 1 + IV_BYTES, sealed.size - 1 - IV_BYTES).decodeToString()
        }
    } catch (_: Exception) {
        null // Never expose provider input or Keystore diagnostics to callers.
    }

    fun encrypt(slot: String, value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, checkNotNull(key(true)))
        } catch (_: KeyPermanentlyInvalidatedException) {
            // Other saved keys remain unreadable and must be re-entered by the user.
            resetKey()
            cipher.init(Cipher.ENCRYPT_MODE, checkNotNull(key(true)))
        }
        cipher.updateAAD(slot.toByteArray())
        val iv = cipher.iv
        check(iv.size == IV_BYTES)
        val encrypted = cipher.doFinal(value.toByteArray())
        val sealed = ByteArray(1 + IV_BYTES + encrypted.size)
        sealed[0] = VERSION
        iv.copyInto(sealed, 1)
        encrypted.copyInto(sealed, 1 + IV_BYTES)
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

private object AndroidCredentialKey {
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "ash.provider.credentials.v1"
    private val store by lazy { KeyStore.getInstance(PROVIDER).apply { load(null) } }

    // Settings serializes writes, including migration and reset, across all its instances.
    fun load(create: Boolean): SecretKey? {
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec) }.generateKey()
    }

    fun reset() = store.deleteEntry(ALIAS)
}
