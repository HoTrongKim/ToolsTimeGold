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
    val refreshRateLimitTexts: List<String> = listOf(
        "kiểm tra cuốc xe quá thường xuyên",
        "thao tác chậm hơn",
        "thử lại sau ít phút"
    ),
    val detailScreenTexts: List<String> = listOf(
        "Chi tiết",
        "Đăng ký giờ làm",
        "Thời gian làm việc",
        "Thu nhập cố định",
        "Loại dịch vụ",
        "Cách thức hoạt động của Giờ vàng"
    ),
    val loadingTexts: List<String> = listOf("Đang tải", "Vui lòng chờ"),
    val prohibitedTexts: List<String> = listOf(
        "CAPTCHA", "Mã OTP", "Nhập OTP", "Xác minh danh tính", "Verification code"
    ),
    val refreshIntervalMs: Long = 500,
    val waitAfterSwipeMs: Long = 100,
    val waitAfterOpenSlotMs: Long = 1_000,
    val registrationTimeoutMs: Long = 8_000,
    val maxRetry: Int = 3,
    val clickDebounceMs: Long = 60,
    val shiftCooldownMs: Long = 30_000,
    val maxRegistrations: Int = 10,
    val maxRunMinutes: Int = 60,
    val maxClicksPerMinute: Int = 20,
    val maxRefreshesPerMinute: Int = 120,
    val maxUnknownScreens: Int = 5,
    val refreshSwipeDurationMs: Long = 120,
    val loadSwipeDurationMs: Long = 450,
    val registerAll: Boolean = true,
    val stopAfterSuccess: Boolean = false,
    val autoReturnToList: Boolean = true,
    val soundOnSuccess: Boolean = true,
    val vibrateOnSuccess: Boolean = true,
    val showOverlay: Boolean = true,
    val keepScreenOn: Boolean = false,
    val continuousMode: Boolean = true,
    val coordinates: List<CoordinatePoint> = CoordinatePoint.defaults
)
