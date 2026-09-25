package com.shehan.robotpet.brain

enum class EventGateState { ARMED, CANDIDATE, WAIT_RELEASE }

/**
 * Converts a noisy/held boolean signal into a single event.
 * After firing, the signal MUST go inactive for releaseMs before it can fire again.
 * This prevents the V7 "cooldown expires while pose is still held" spam failure.
 */
class OneShotEventGate(
    private val confirmMs: Long,
    private val releaseMs: Long,
    private val minimumGapMs: Long = 0L
) {
    var state: EventGateState = EventGateState.ARMED
        private set

    private var candidateSince = 0L
    private var releaseSince = 0L
    private var lastFiredAt = Long.MIN_VALUE / 4

    fun update(active: Boolean, nowMs: Long): Boolean {
        when (state) {
            EventGateState.ARMED -> {
                if (active) {
                    state = EventGateState.CANDIDATE
                    candidateSince = nowMs
                    if (confirmMs == 0L && nowMs - lastFiredAt >= minimumGapMs) {
                        fire(nowMs)
                        return true
                    }
                }
            }

            EventGateState.CANDIDATE -> {
                if (!active) {
                    state = EventGateState.ARMED
                    candidateSince = 0L
                } else if (nowMs - candidateSince >= confirmMs && nowMs - lastFiredAt >= minimumGapMs) {
                    fire(nowMs)
                    return true
                }
            }

            EventGateState.WAIT_RELEASE -> {
                if (active) {
                    releaseSince = 0L
                } else {
                    if (releaseSince == 0L) releaseSince = nowMs
                    if (nowMs - releaseSince >= releaseMs) {
                        state = EventGateState.ARMED
                        candidateSince = 0L
                        releaseSince = 0L
                    }
                }
            }
        }
        return false
    }

    fun forceWaitRelease(nowMs: Long) {
        state = EventGateState.WAIT_RELEASE
        lastFiredAt = nowMs
        candidateSince = 0L
        releaseSince = 0L
    }

    fun reset() {
        state = EventGateState.ARMED
        candidateSince = 0L
        releaseSince = 0L
        lastFiredAt = Long.MIN_VALUE / 4
    }

    private fun fire(nowMs: Long) {
        state = EventGateState.WAIT_RELEASE
        lastFiredAt = nowMs
        candidateSince = 0L
        releaseSince = 0L
    }
}
