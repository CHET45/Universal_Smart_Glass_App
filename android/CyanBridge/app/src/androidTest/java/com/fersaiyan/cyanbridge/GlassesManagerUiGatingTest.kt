package com.fersaiyan.cyanbridge

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import com.fersaiyan.cyanbridge.devices.DeviceClass
import com.fersaiyan.cyanbridge.devices.DeviceProfile
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlassesManagerUiGatingTest {

    @Test
    fun heyCyanProfile_showsExtrasPanel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        DeviceProfileStore.saveLastSelected(
            context,
            DeviceProfile(
                macAddress = "AA:BB:CC:DD:EE:FF",
                advertisedName = "HeyCyan_123",
                detectedClass = DeviceClass.HEY_CYAN,
                selectedClass = DeviceClass.HEY_CYAN,
                userOverridden = false,
            )
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.layout_heycyan_extras))
                .check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
            onView(withId(R.id.layout_status_metrics))
                .check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
        }
    }

    @Test
    fun genericAudioProfile_hidesExtrasPanel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        DeviceProfileStore.saveLastSelected(
            context,
            DeviceProfile(
                macAddress = "10:20:30:40:50:60",
                advertisedName = "BT Headset",
                detectedClass = DeviceClass.GENERIC_AUDIO,
                selectedClass = DeviceClass.GENERIC_AUDIO,
                userOverridden = true,
            )
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.layout_heycyan_extras))
                .check(matches(withEffectiveVisibility(Visibility.GONE)))
            onView(withId(R.id.layout_status_metrics))
                .check(matches(withEffectiveVisibility(Visibility.GONE)))
        }
    }
}
