package com.fersaiyan.cyanbridge.memoryvault

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MemoryPolicyServiceTest {
    @Test
    fun screenOcrDefaultsToLocalOnly() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val policy = MemoryPolicyService.classifyForMemoryRef(
            context = context,
            memoryRef = "file:screen_captures/2026-03-19.jsonl",
            text = "example screen text",
        )

        assertEquals(MemorySourceType.SCREEN_OCR, policy.sourceType)
        assertEquals(MemorySyncEligibility.LOCAL_ONLY, policy.syncEligibility)
        assertTrue(policy.requiresExplicitConsentForCloud)
    }

    @Test
    fun secretLikeTextIsDetected() {
        assertTrue(MemoryPolicyService.containsPotentialSecrets("password=abc123"))
        assertTrue(MemoryPolicyService.containsPotentialSecrets("api_key: test"))
        assertFalse(MemoryPolicyService.containsPotentialSecrets("went to the gym"))
    }

    @Test
    fun inheritRestrictionsTightensPolicy() {
        val p1 = MemoryPolicyMetadata(
            memoryRef = "a",
            sourceType = MemorySourceType.EXPLICIT_USER_FACT,
            sensitivityLevel = MemorySensitivityLevel.MEDIUM,
            syncEligibility = MemorySyncEligibility.ENCRYPTED_SYNC_ALLOWED,
            retentionPolicy = null,
            derivedFromIds = emptyList(),
            provenance = null,
            containsPotentialSecrets = false,
            requiresExplicitConsentForCloud = false,
            sourceTimestampMs = 1,
        )
        val p2 = p1.copy(
            memoryRef = "b",
            sourceType = MemorySourceType.SCREEN_OCR,
            sensitivityLevel = MemorySensitivityLevel.HIGH,
            syncEligibility = MemorySyncEligibility.LOCAL_ONLY,
        )

        val inherited = MemoryPolicyService.inheritRestrictions(listOf(p1, p2))
        assertEquals(MemorySensitivityLevel.HIGH, inherited?.first)
        assertEquals(MemorySyncEligibility.LOCAL_ONLY, inherited?.second)
    }

    @Test
    fun cloudModesFilterLocalOnlyItems() {
        val localOnly = MemoryPolicyMetadata(
            memoryRef = "x",
            sourceType = MemorySourceType.SCREEN_OCR,
            sensitivityLevel = MemorySensitivityLevel.HIGH,
            syncEligibility = MemorySyncEligibility.LOCAL_ONLY,
            retentionPolicy = null,
            derivedFromIds = emptyList(),
            provenance = null,
            containsPotentialSecrets = false,
            requiresExplicitConsentForCloud = true,
            sourceTimestampMs = 1,
        )

        assertFalse(MemoryPolicyService.isEligibleForRetrieval(MemoryPrivacyMode.FAST_CLOUD_MEMORY, localOnly))
        assertTrue(MemoryPolicyService.isEligibleForRetrieval(MemoryPrivacyMode.PRIVATE_LOCAL, localOnly))
        assertTrue(MemoryPolicyService.isEligibleForRetrieval(MemoryPrivacyMode.ENCRYPTED_SYNC, localOnly))
    }
}
