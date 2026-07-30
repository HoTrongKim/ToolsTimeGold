package com.autoregistershift.automation

import com.autoregistershift.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenAnalyzerTest {
    private val analyzer = ScreenAnalyzer()
    private val settings = AppSettings()

    @Test
    fun noSlots() {
        val result = analyze(node("Lịch nhận cuốc", children = listOf(node("Không có ca"))))
        assertTrue(result.noSlots)
        assertTrue(result.slotRanges.isEmpty())
    }

    @Test
    fun oneSlot() {
        val result = analyze(node("Lịch nhận cuốc", children = listOf(node("17:00 – 21:00", true))))
        assertEquals(listOf("17:00" to "21:00"), result.slotRanges)
    }

    @Test
    fun multipleSlots() {
        val result = analyze(
            node(
                "Khung giờ",
                children = listOf(node("07:00-11:00", true), node("13:30-17:30", true))
            )
        )
        assertEquals(2, result.slotRanges.size)
    }

    @Test
    fun slotWinsEvenWhenEndOfListAlsoSaysNoSlots() {
        val result = analyze(
            node(
                "Lịch nhận cuốc",
                children = listOf(
                    node("Giờ vàng 17:00 - 21:00 Đà Nẵng_Khu 1", true),
                    node("Các khung giờ đã được đặt hết.")
                )
            )
        )
        assertTrue(result.noSlots)
        assertEquals(1, result.slotRanges.size)
        assertEquals(
            SlotSelection.ACCESSIBILITY_NODE,
            SlotSelectionPolicy.choose(
                hasDetectedSlot = result.slotRanges.isNotEmpty(),
                hasTimeRangeSignal = result.slotRanges.isNotEmpty(),
                fallbackEnabled = true
            )
        )
    }

    @Test
    fun emptyRealScreenNeverUsesBlindCoordinate() {
        val result = analyze(
            node(
                "Lịch nhận cuốc",
                children = listOf(
                    node("Hiện chưa có cuốc xe nào"),
                    node("Vui lòng thử lại hoặc chuyển sang ngày khác.")
                )
            )
        )
        assertTrue(result.noSlots)
        assertEquals(
            SlotSelection.WAIT_FOR_NEXT_REFRESH,
            SlotSelectionPolicy.choose(
                hasDetectedSlot = false,
                hasTimeRangeSignal = false,
                fallbackEnabled = true
            )
        )
    }

    @Test
    fun coordinateFallbackRequiresVisibleTimeRange() {
        assertEquals(
            SlotSelection.SEMANTIC_COORDINATE_FALLBACK,
            SlotSelectionPolicy.choose(
                hasDetectedSlot = false,
                hasTimeRangeSignal = true,
                fallbackEnabled = true
            )
        )
    }

    @Test
    fun registerButton() {
        assertTrue(analyze(node("Chi tiết", children = listOf(node("Đăng ký giờ làm", true)))).hasRegisterButton)
    }

    @Test
    fun fullShift() {
        assertTrue(analyze(node("Ca đã đầy")).full)
    }

    @Test
    fun registrationSuccess() {
        assertTrue(analyze(node("Đăng ký thành công")).success)
    }

    @Test
    fun networkError() {
        assertTrue(analyze(node("Không thể kết nối. Vui lòng thử lại")).networkError)
    }

    @Test
    fun prolongedLoadingRemainsNonClickable() {
        val result = analyze(node("Lịch nhận cuốc", loading = true))
        assertTrue(result.loading)
        assertTrue(result.slotRanges.isEmpty())
    }

    @Test
    fun changedPackageCannotInteract() {
        assertFalse(AutomationSafetyGate.canInteract("com.other", "com.target", true, false))
        assertTrue(AutomationSafetyGate.canInteract("com.target", "com.target", true, false))
        assertFalse(AutomationSafetyGate.canInteract("com.target", "com.target", true, true))
    }

    private fun analyze(node: FakeAccessibilityNode) = analyzer.analyze(node, settings)
    private fun node(
        text: String,
        clickable: Boolean = false,
        loading: Boolean = false,
        children: List<FakeAccessibilityNode> = emptyList()
    ) = FakeAccessibilityNode(text, clickable, loading, children)
}

private data class FakeAccessibilityNode(
    override val text: String,
    override val clickable: Boolean = false,
    override val loading: Boolean = false,
    override val children: List<FakeAccessibilityNode> = emptyList()
) : UiNodeSnapshot
