package com.autoregistershift.model

data class CoordinatePoint(
    val id: String,
    val name: String,
    val xRatio: Float,
    val yRatio: Float,
    val enabled: Boolean = true
) {
    companion object {
        val defaults = listOf(
            CoordinatePoint("refresh_start", "Bắt đầu vuốt làm mới", .50f, .35f),
            CoordinatePoint("refresh_end", "Kết thúc vuốt làm mới", .50f, .75f),
            CoordinatePoint("load_start", "Bắt đầu vuốt tải thêm", .50f, .75f),
            CoordinatePoint("load_end", "Kết thúc vuốt tải thêm", .50f, .35f),
            CoordinatePoint("first_slot", "Ca đầu tiên", .50f, .45f),
            CoordinatePoint("slot_fallback", "Bên phải thẻ ca", .88f, .45f),
            CoordinatePoint("register", "Nút đăng ký", .50f, .91f),
            CoordinatePoint("close_dialog", "Đóng thông báo", .50f, .70f),
            CoordinatePoint("back_fallback", "Quay lại dự phòng", .08f, .07f)
        )
    }
}
