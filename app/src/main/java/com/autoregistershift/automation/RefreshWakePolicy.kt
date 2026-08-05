package com.autoregistershift.automation

/** Quyết định có bỏ qua phần còn lại của chu kỳ 500 ms để xử lý ca ngay hay không. */
object RefreshWakePolicy {
    fun shouldWake(
        contentEventForTarget: Boolean,
        targetAppActive: Boolean,
        scheduleScreen: Boolean,
        loading: Boolean,
        unprocessedSlotVisible: Boolean
    ): Boolean = contentEventForTarget &&
        targetAppActive &&
        scheduleScreen &&
        !loading &&
        unprocessedSlotVisible
}
