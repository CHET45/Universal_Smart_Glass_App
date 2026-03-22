package com.fersaiyan.cyanbridge.devices

/**
 * Chapter 4: Glasses Manager baseline capability gating.
 *
 * The MVP rule is intentionally simple:
 * - HEY_CYAN: show the expanded "extras" panel and status placeholders (battery/storage).
 * - Other classes: show meeting capture only (plus basic connection/pairing UI).
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
        if (deviceClass == DeviceClass.HEY_CYAN) {
            base.add(Action.HEY_CYAN_EXTRAS)
            base.add(Action.STATUS_BATTERY)
            base.add(Action.STATUS_STORAGE)
        }
        return base
    }
}
