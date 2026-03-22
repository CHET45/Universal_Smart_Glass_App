package com.fersaiyan.cyanbridge.memoryvault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Base64

class MemorySyncPreparationServiceTest {
    @Test
    fun preparedPayloadEncodesAllCryptoFields() {
        val payload = MemorySyncPreparationService.buildPreparedSyncPayload(
            memoryRef = "file:USER_FACTS.md",
            cryptoVersion = 1,
            nonce = byteArrayOf(1, 2, 3),
            ciphertext = byteArrayOf(4, 5, 6),
            aad = "file:USER_FACTS.md",
            keyRef = "k1",
            wrappingVersion = 1,
            wrappedKeyNonce = byteArrayOf(7, 8),
            wrappedKeyCiphertext = byteArrayOf(9, 10),
        )

        assertEquals("file:USER_FACTS.md", payload.blobManifest.memoryRef)
        assertEquals(1, payload.blobManifest.cryptoVersion)
        assertEquals(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)), payload.blobManifest.nonceB64)
        assertEquals(Base64.getEncoder().encodeToString(byteArrayOf(4, 5, 6)), payload.blobManifest.ciphertextB64)
        assertEquals("k1", payload.keyManifest.keyRef)
        assertNotNull(payload.keyManifest.wrappedKeyNonceB64)
        assertNotNull(payload.keyManifest.wrappedKeyCiphertextB64)
    }
}
