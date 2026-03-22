package com.fersaiyan.cyanbridge.memoryvault

import com.fersaiyan.cyanbridge.memoryvault.crypto.VaultCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class VaultCryptoTest {
    @Test
    fun aesGcmRoundTripWorks() {
        val key = VaultCrypto.newAesKeyBytes()
        val plaintext = "hello secure memory".toByteArray()
        val aad = "ref:abc".toByteArray()

        val envelope = VaultCrypto.encryptAesGcm(key, plaintext, aad)
        val decrypted = VaultCrypto.decryptAesGcm(key, envelope, aad)

        assertArrayEquals(plaintext, decrypted)
        assertNotEquals(String(plaintext), String(envelope.ciphertext))
    }

    @Test(expected = Throwable::class)
    fun wrongAadFailsAuthentication() {
        val key = VaultCrypto.newAesKeyBytes()
        val plaintext = "memory payload".toByteArray()
        val envelope = VaultCrypto.encryptAesGcm(key, plaintext, "a".toByteArray())
        VaultCrypto.decryptAesGcm(key, envelope, "b".toByteArray())
    }
}
