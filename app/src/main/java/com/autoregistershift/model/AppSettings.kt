package com.autoregistershift.model

data class AppSettings(
    val targetPackage: String = "",
    val scheduleScreenTexts: List<String> = listOf("Lịch nhận cuốc", "Giờ vàng", "Khung giờ"),
    val registerButtonTexts: List<String> = listOf("Đăng ký giờ làm", "Đăng ký"),
    val noSlotTexts: List<String> = listOf(
        "Các khung giờ đã được đặt hết",
        "Hiện chưa có cuốc xe nào",
        "Vui lòng thử lại hoặc chuyển sang ngày khác",
        "Không có khung giờ",
        "Không có ca"
    ),
    val successTexts: List<String> = listOf("Đăng ký thành công", "Đã đăng ký"),
    val fullTexts: List<String> = listOf("Ca đã đầy", "Không còn chỗ", "Khung giờ đã được đặt hết"),
    val networkErrorTexts: List<String> = listOf("Có lỗi xảy ra", "Không thể kết nối", "Vui lòng thử lại"),
    val detailScreenTexts: List<String> = listOf("Chi tiết", "Đăng ký giờ làm", "Thời gian làm việc"),
    val loadingTexts: List<String> = listOf("Đang tải", "Vui lòng chờ"),
    val prohibitedTexts: List<String> = listOf(
        "CAPTCHA", "Mã OTP", "Nhập OTP", "Xác minh danh tính", "Verification code"
    ),
    val refreshIntervalMs: Long = 3_000,
    val waitAfterSwipeMs: Long = 1_500,
    val waitAfterOpenSlotMs: Long = 1_000,
    val registrationTimeoutMs: Long = 8_000,
    val maxRetry: Int = 3,
    val clickDebounceMs: Long = 500,
    val shiftCooldownMs: Long = 30_000,
    val maxRegistrations: Int = 10,
    val maxRunMinutes: Int = 60,
    val maxClicksPerMinute: Int = 20,
    val maxRefreshesPerMinute: Int = 20,
    val maxUnknownScreens: Int = 5,
    val refreshSwipeDurationMs: Long = 550,
    val loadSwipeDurationMs: Long = 450,
    val registerAll: Boolean = true,
    val stopAfterSuccess: Boolean = false,
    val autoReturnToList: Boolean = true,
    val soundOnSuccess: Boolean = true,
    val vibrateOnSuccess: Boolean = true,
    val showOverlay: Boolean = true,
    val keepScreenOn: Boolean = false,
    val coordinates: List<CoordinatePoint> = CoordinatePoint.defaults
)
