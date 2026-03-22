package com.fersaiyan.cyanbridge.memoryvault.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class CipherEnvelope(
    val version: Int,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
)

object VaultCrypto {
    const val CRYPTO_VERSION: Int = 1
    private const val AES_KEY_BITS: Int = 256
    private const val GCM_TAG_BITS: Int = 128
    private const val PBKDF2_ITERATIONS: Int = 150_000

    fun randomBytes(size: Int): ByteArray {
        val out = ByteArray(size)
        SecureRandom().nextBytes(out)
        return out
    }

    fun newAesKeyBytes(): ByteArray = randomBytes(AES_KEY_BITS / 8)

    fun encryptAesGcm(keyBytes: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): CipherEnvelope {
        val nonce = randomBytes(12)
        val key = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        if (aad != null && aad.isNotEmpty()) cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)
        return CipherEnvelope(version = CRYPTO_VERSION, nonce = nonce, ciphertext = ciphertext)
    }

    fun decryptAesGcm(keyBytes: ByteArray, envelope: CipherEnvelope, aad: ByteArray? = null): ByteArray {
        val key = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, envelope.nonce))
        if (aad != null && aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(envelope.ciphertext)
    }

    fun derivePassphraseKey(passphrase: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, AES_KEY_BITS)
        val factory: SecretKeyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val bytes = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return bytes
    }

    fun destroy(bytes: ByteArray?) {
        if (bytes == null) return
        for (i in bytes.indices) bytes[i] = 0
    }
}
