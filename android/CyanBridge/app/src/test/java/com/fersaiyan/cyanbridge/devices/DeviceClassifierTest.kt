package com.fersaiyan.cyanbridge.devices

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceClassifierTest {

    @Test
    fun heyCyan_detectedByName() {
        assertEquals(DeviceClass.HEY_CYAN, DeviceClassifier.guessDeviceClass("HeyCyan-123"))
        assertEquals(DeviceClass.HEY_CYAN, DeviceClassifier.guessDeviceClass("cyan glasses"))
        assertEquals(DeviceClass.HEY_CYAN, DeviceClassifier.guessDeviceClass("O_ABC"))
        assertEquals(DeviceClass.HEY_CYAN, DeviceClassifier.guessDeviceClass("Q_001"))
    }

    @Test
    fun metaRayban_detectedByName() {
        assertEquals(DeviceClass.META_RAYBAN, DeviceClassifier.guessDeviceClass("Ray-Ban Meta"))
        assertEquals(DeviceClass.META_RAYBAN, DeviceClassifier.guessDeviceClass("rayban"))
    }

    @Test
    fun genericAudio_detectedByName() {
        assertEquals(DeviceClass.GENERIC_AUDIO, DeviceClassifier.guessDeviceClass("AirPods Pro"))
        assertEquals(DeviceClass.GENERIC_AUDIO, DeviceClassifier.guessDeviceClass("BT Headset"))
    }

    @Test
    fun unknownWhenEmpty() {
        assertEquals(DeviceClass.UNKNOWN, DeviceClassifier.guessDeviceClass(null))
        assertEquals(DeviceClass.UNKNOWN, DeviceClassifier.guessDeviceClass(""))
        assertEquals(DeviceClass.UNKNOWN, DeviceClassifier.guessDeviceClass("   "))
    }
}
