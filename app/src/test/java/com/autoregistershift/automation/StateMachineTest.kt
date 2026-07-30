package com.autoregistershift.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StateMachineTest {
    @Test
    fun followsValidAutomationPath() {
        var now = 100L
        val machine = StateMachine { now }
        machine.transition(AutomationState.WAITING_FOR_TARGET_APP)
        now = 200
        machine.transition(AutomationState.WAITING_FOR_SCHEDULE_SCREEN)
        machine.transition(AutomationState.REFRESHING)
        machine.transition(AutomationState.WAITING_FOR_DATA)
        machine.transition(AutomationState.CHECKING_SLOTS)
        machine.transition(AutomationState.OPENING_SLOT)
        machine.transition(AutomationState.WAITING_FOR_DETAIL)
        machine.transition(AutomationState.FINDING_REGISTER_BUTTON)
        machine.transition(AutomationState.REGISTERING)
        machine.transition(AutomationState.CHECKING_RESULT)
        machine.transition(AutomationState.RETURNING_TO_LIST)
        assertEquals(AutomationState.RETURNING_TO_LIST, machine.state)
    }

    @Test
    fun rejectsClickStateDirectlyFromIdle() {
        val machine = StateMachine()
        assertThrows(IllegalArgumentException::class.java) {
            machine.transition(AutomationState.REGISTERING)
        }
    }

    @Test
    fun timeoutUsesStateEntryTime() {
        var now = 1_000L
        val machine = StateMachine { now }
        machine.transition(AutomationState.WAITING_FOR_TARGET_APP)
        now = 1_499
        assertFalse(machine.timedOut(500))
        now = 1_500
        assertTrue(machine.timedOut(500))
    }
}
