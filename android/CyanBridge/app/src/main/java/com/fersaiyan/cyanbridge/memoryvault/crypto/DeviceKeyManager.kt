package com.fersaiyan.cyanbridge.memoryvault.crypto

import android.os.Build
import android.util.Base64
import androidx.annotation.RequiresApi
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class DeviceWrappedBlob(
    val nonce: ByteArray,
    val ciphertext: ByteArray,
) {
    fun encode(): String {
        val n = Base64.encodeToString(nonce, Base64.NO_WRAP)
        val c = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        return "$n:$c"
    }

    companion object {
        fun decode(raw: String?): DeviceWrappedBlob? {
            val v = raw?.trim().orEmpty()
            if (v.isBlank() || !v.contains(':')) return null
            val parts = v.split(':')
            if (parts.size != 2) return null
            return runCatching {
                DeviceWrappedBlob(
                    nonce = Base64.decode(parts[0], Base64.NO_WRAP),
                    ciphertext = Base64.decode(parts[1], Base64.NO_WRAP),
                )
            }.getOrNull()
        }
    }
}

object DeviceKeyManager {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "memory_vault_device_wrap_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun wrap(plaintext: ByteArray): DeviceWrappedBlob {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val nonce = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return DeviceWrappedBlob(nonce = nonce, ciphertext = ciphertext)
    }

    fun unwrap(blob: DeviceWrappedBlob): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateSecretKey(),
            GCMParameterSpec(128, blob.nonce),
        )
        return cipher.doFinal(blob.ciphertext)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            "Android KeyStore AES generation requires API 23+"
        }
        return createAesKeyApi23()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun createAesKeyApi23(): SecretKey {
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build()

        val kg = KeyGenerator.getInstance(
            android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER,
        )
        kg.init(spec)
        return kg.generateKey()
    }
}
