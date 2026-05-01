package com.fersaiyan.cyanbridge.devices

/**
 * Capability gating for the Glasses Manager screen.
 *
 * Eyevue/S2 uses the same visible manager controls as the HeyCyan-class glasses,
 * but the commands are routed through the GlassesProtocol abstraction instead of
 * the legacy Oudmon/HeyCyan SDK connection state.
 */
object GlassesManagerGating {

    enum class Action {
        MEETING_CAPTURE,
        STATUS_BATTERY,
        STATUS_STORAGE,
        HEY_CYAN_EXTRAS,
    }

    data class UiModel(
        val visibleActions: Set<Action>,
    ) {
        fun isVisible(action: Action): Boolean = visibleActions.contains(action)
    }

    fun uiModel(profile: DeviceProfile?): UiModel = UiModel(visibleActions(profile))

    fun visibleActions(profile: DeviceProfile?): Set<Action> {
        val selected = profile?.selectedClass ?: DeviceClass.UNKNOWN
        return visibleActions(selected)
    }

    fun visibleActions(deviceClass: DeviceClass): Set<Action> {
        val base = linkedSetOf(Action.MEETING_CAPTURE)
        if (deviceClass == DeviceClass.HEY_CYAN || deviceClass == DeviceClass.EYEVUE_S2 || deviceClass == DeviceClass.HSC_H5_15) {
            base.add(Action.HEY_CYAN_EXTRAS)
            base.add(Action.STATUS_BATTERY)
            base.add(Action.STATUS_STORAGE)
        }
        return base
    }
}
