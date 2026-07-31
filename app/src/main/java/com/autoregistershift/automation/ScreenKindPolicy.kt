package com.autoregistershift.automation

/**
 * Keeps the schedule list and shift detail screens mutually exclusive.
 *
 * Both Grab screens contain generic labels such as "Giờ vàng". A detail-only
 * marker must therefore win over a generic schedule marker, even after the
 * register button disappears following a successful registration.
 */
object ScreenKindPolicy {
    fun isSchedule(
        hasScheduleMarker: Boolean,
        hasNoSlotMarker: Boolean,
        hasDetailMarker: Boolean,
        hasRegisterButton: Boolean
    ): Boolean =
        (hasScheduleMarker || hasNoSlotMarker) && !hasDetailMarker && !hasRegisterButton

    fun isDetail(
        hasDetailMarker: Boolean,
        hasRegisterButton: Boolean
    ): Boolean = hasDetailMarker || hasRegisterButton
}
