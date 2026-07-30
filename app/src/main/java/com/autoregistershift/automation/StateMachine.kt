package com.autoregistershift.automation

class StateMachine(
    private val now: () -> Long = System::currentTimeMillis
) {
    var state: AutomationState = AutomationState.IDLE
        private set
    var enteredAtMs: Long = now()
        private set

    fun transition(next: AutomationState): AutomationState {
        require(next in allowedTransitions.getValue(state)) {
            "Chuyển trạng thái không hợp lệ: $state -> $next"
        }
        state = next
        enteredAtMs = now()
        return state
    }

    fun force(next: AutomationState): AutomationState {
        state = next
        enteredAtMs = now()
        return state
    }

    fun timedOut(timeoutMs: Long): Boolean = now() - enteredAtMs >= timeoutMs

    companion object {
        private val runningStates = AutomationState.entries.toSet() -
            setOf(AutomationState.IDLE, AutomationState.PAUSED, AutomationState.STOPPED, AutomationState.ERROR)

        private val allowedTransitions: Map<AutomationState, Set<AutomationState>> =
            AutomationState.entries.associateWith { current ->
                when (current) {
                    AutomationState.IDLE, AutomationState.STOPPED ->
                        setOf(AutomationState.WAITING_FOR_TARGET_APP, AutomationState.STOPPED)
                    AutomationState.PAUSED ->
                        setOf(AutomationState.WAITING_FOR_TARGET_APP, AutomationState.STOPPED)
                    AutomationState.ERROR ->
                        setOf(AutomationState.WAITING_FOR_TARGET_APP, AutomationState.STOPPED)
                    else -> runningStates + setOf(
                        AutomationState.PAUSED,
                        AutomationState.STOPPED,
                        AutomationState.ERROR
                    )
                }
            }
    }
}
