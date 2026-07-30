package com.autoregistershift.automation

enum class AutomationState {
    IDLE,
    WAITING_FOR_TARGET_APP,
    WAITING_FOR_SCHEDULE_SCREEN,
    REFRESHING,
    WAITING_FOR_DATA,
    CHECKING_SLOTS,
    OPENING_SLOT,
    WAITING_FOR_DETAIL,
    FINDING_REGISTER_BUTTON,
    REGISTERING,
    CHECKING_RESULT,
    RETURNING_TO_LIST,
    PAUSED,
    STOPPED,
    ERROR
}

data class StateSnapshot(
    val state: AutomationState = AutomationState.IDLE,
    val enteredAtMs: Long = System.currentTimeMillis(),
    val message: String = "Đã dừng",
    val refreshCount: Int = 0,
    val successCount: Int = 0
)
