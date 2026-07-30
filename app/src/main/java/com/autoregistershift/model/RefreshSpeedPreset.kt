package com.autoregistershift.model

enum class RefreshSpeedPreset(
    val label: String,
    val buttonLabel: String,
    val refreshIntervalMs: Long,
    val waitAfterSwipeMs: Long,
    val maxRefreshesPerMinute: Int,
    val clickDebounceMs: Long,
    val refreshSwipeDurationMs: Long
) {
    FAST("Cực nhanh an toàn · 1 giây", "1 giây", 1_000, 300, 35, 100, 350),
    BALANCED("Cân bằng · 2 giây", "2 giây", 2_000, 600, 30, 150, 450),
    STABLE("Ổn định · 3 giây", "3 giây", 3_000, 1_000, 20, 250, 550);

    fun applyTo(settings: AppSettings): AppSettings = settings.copy(
        refreshIntervalMs = refreshIntervalMs,
        waitAfterSwipeMs = waitAfterSwipeMs,
        maxRefreshesPerMinute = maxRefreshesPerMinute,
        clickDebounceMs = clickDebounceMs,
        refreshSwipeDurationMs = refreshSwipeDurationMs
    )

    fun matches(settings: AppSettings): Boolean =
        settings.refreshIntervalMs == refreshIntervalMs &&
            settings.waitAfterSwipeMs == waitAfterSwipeMs
}
