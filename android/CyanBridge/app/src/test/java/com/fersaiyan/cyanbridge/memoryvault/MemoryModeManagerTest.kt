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
class MemoryModeManagerTest {
    @Test
    fun modePersistsAndRestores() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        MemoryModeManager.setSelectedMode(context, MemoryPrivacyMode.ENCRYPTED_SYNC)
        assertEquals(MemoryPrivacyMode.ENCRYPTED_SYNC, MemoryModeManager.getSelectedMode(context))
    }

    @Test
    fun sourceSyncTogglesPersist() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        MemoryModeManager.setSourceSyncEnabled(context, MemorySourceType.SCREEN_OCR, true)
        assertTrue(MemoryModeManager.isSourceSyncEnabled(context, MemorySourceType.SCREEN_OCR))

        MemoryModeManager.setSourceSyncEnabled(context, MemorySourceType.SCREEN_OCR, false)
        assertFalse(MemoryModeManager.isSourceSyncEnabled(context, MemorySourceType.SCREEN_OCR))
    }

    @Test
    fun backendAvailabilityStatesAreHonest() {
        assertFalse(MemoryModeManager.isModeBackendAvailable(MemoryPrivacyMode.ENCRYPTED_SYNC))
        assertFalse(MemoryModeManager.isModeBackendAvailable(MemoryPrivacyMode.FAST_CLOUD_MEMORY))
        assertFalse(MemoryModeManager.isModeBackendAvailable(MemoryPrivacyMode.CONFIDENTIAL_CLOUD_BETA))
        assertTrue(MemoryModeManager.isModeBackendAvailable(MemoryPrivacyMode.PRIVATE_LOCAL))
    }
}
