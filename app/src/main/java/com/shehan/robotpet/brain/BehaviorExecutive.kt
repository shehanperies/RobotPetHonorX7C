package com.shehan.robotpet.brain

private data class ResourceLease(
    val source: DecisionSource,
    val priority: Int,
    val behaviorKey: String,
    val untilMs: Long
)

data class ExecutiveResult(
    val decision: BrainDecision,
    val source: DecisionSource,
    val allowed: Set<BehaviorResource>,
    val preempted: Set<BehaviorResource>,
    val denied: Set<BehaviorResource>,
    val summary: String
)

/**
 * V6 central arbiter. Perception/brain modules may propose decisions, but only this class
 * decides which resources may actually be used. This prevents a new camera frame from
 * cancelling an in-progress fork gesture, speech turn or drive pulse.
 */
class BehaviorExecutive {
    private val leases = mutableMapOf<BehaviorResource, ResourceLease>()
    private var listening = false
    private var remoteManual = false
    private var lastAcceptedKey = ""
    private var lastAcceptedMs = 0L

    fun setListening(active: Boolean, now: Long = System.currentTimeMillis()) {
        listening = active
        if (active) {
            val lease = ResourceLease(
                source = DecisionSource.LISTENING,
                priority = priorityOf(DecisionSource.LISTENING),
                behaviorKey = "listening",
                untilMs = Long.MAX_VALUE
            )
            leases[BehaviorResource.MIC] = lease
            leases[BehaviorResource.SPEECH] = lease
            leases[BehaviorResource.DRIVE] = lease
            leases[BehaviorResource.FORK] = lease
        } else {
            leases.entries.removeAll { it.value.source == DecisionSource.LISTENING }
        }
        expire(now)
    }

    fun setRemoteManual(active: Boolean, now: Long = System.currentTimeMillis()) {
        remoteManual = active
        if (active) {
            val lease = ResourceLease(
                DecisionSource.REMOTE,
                priorityOf(DecisionSource.REMOTE),
                "remote",
                Long.MAX_VALUE
            )
            leases[BehaviorResource.DRIVE] = lease
            leases[BehaviorResource.FORK] = lease
        } else {
            leases.entries.removeAll { it.value.source == DecisionSource.REMOTE }
        }
        expire(now)
    }

    fun submit(
        decision: BrainDecision,
        source: DecisionSource,
        now: Long = System.currentTimeMillis()
    ): ExecutiveResult {
        expire(now)

        val key = decision.behaviorKey.ifBlank { source.name.lowercase() }
        val priority = priorityOf(source)
        val requested = requestedResources(decision)
        val allowed = linkedSetOf<BehaviorResource>()
        val denied = linkedSetOf<BehaviorResource>()
        val preempted = linkedSetOf<BehaviorResource>()

        if (source == DecisionSource.SAFETY || decision.interruptMotion) {
            for (resource in listOf(BehaviorResource.DRIVE, BehaviorResource.FORK)) {
                if (leases.containsKey(resource)) preempted += resource
                leases.remove(resource)
                allowed += resource
            }
        }

        for (resource in requested) {
            if (resource in allowed) continue

            val current = leases[resource]
            val canUse = when {
                current == null -> true
                current.behaviorKey == key -> true
                priority > current.priority -> true
                source == DecisionSource.REMOTE && remoteManual -> true
                else -> false
            }

            if (canUse) {
                if (current != null && current.behaviorKey != key) preempted += resource
                allowed += resource
            } else {
                denied += resource
            }
        }

        // Repeated identical candidate frames within a very short interval should update
        // perception state, not restart the same behavior sequence/speech.
        val duplicate = key == lastAcceptedKey && now - lastAcceptedMs < 700L
        if (duplicate && source !in setOf(DecisionSource.SAFETY, DecisionSource.REMOTE, DecisionSource.FOLLOW)) {
            denied += allowed.filter { it != BehaviorResource.FACE }
            allowed.removeAll { it != BehaviorResource.FACE }
        }

        val hold = if (decision.minimumHoldMs > 0L) decision.minimumHoldMs else defaultHoldMs(source, decision)
        for (resource in allowed) {
            val until = when {
                resource == BehaviorResource.FACE && source in setOf(DecisionSource.VISION, DecisionSource.FOLLOW) -> now + 350L
                source == DecisionSource.REMOTE && remoteManual && resource in setOf(BehaviorResource.DRIVE, BehaviorResource.FORK) -> Long.MAX_VALUE
                else -> now + hold
            }
            leases[resource] = ResourceLease(source, priority, key, until)
        }

        if (allowed.isNotEmpty()) {
            lastAcceptedKey = key
            lastAcceptedMs = now
        }

        val filtered = decision.copy(
            speech = if (BehaviorResource.SPEECH in allowed) decision.speech else null,
            motion = if (
                (decision.motion in driveCommands && BehaviorResource.DRIVE in allowed) ||
                (decision.motion in forkCommands && BehaviorResource.FORK in allowed)
            ) decision.motion else MotionCommand.STOP,
            motionDurationMs = if (
                (decision.motion in driveCommands && BehaviorResource.DRIVE in allowed) ||
                (decision.motion in forkCommands && BehaviorResource.FORK in allowed)
            ) decision.motionDurationMs else 0L,
            sequence = decision.sequence.filter { step ->
                when (step.command) {
                    MotionCommand.STOP -> BehaviorResource.DRIVE in allowed || BehaviorResource.FORK in allowed
                    else -> when {
                        step.command in driveCommands -> BehaviorResource.DRIVE in allowed
                        step.command in forkCommands -> BehaviorResource.FORK in allowed
                        else -> false
                    }
                }
            },
            interruptMotion = decision.interruptMotion &&
                (BehaviorResource.DRIVE in allowed || BehaviorResource.FORK in allowed)
        )

        val lockText = activeLocks(now)
        return ExecutiveResult(
            decision = filtered,
            source = source,
            allowed = allowed,
            preempted = preempted,
            denied = denied,
            summary = "${source.name}:${key} • $lockText"
        )
    }

    fun activeLocks(now: Long = System.currentTimeMillis()): String {
        expire(now)
        if (leases.isEmpty()) return "none"
        return leases.entries
            .sortedBy { it.key.name }
            .joinToString(",") { (r, l) -> "${r.name}:${l.behaviorKey}" }
    }

    fun reset() {
        leases.clear()
        listening = false
        remoteManual = false
        lastAcceptedKey = ""
        lastAcceptedMs = 0L
    }

    private fun expire(now: Long) {
        leases.entries.removeAll { it.value.untilMs != Long.MAX_VALUE && it.value.untilMs <= now }
        if (!listening) leases.entries.removeAll { it.value.source == DecisionSource.LISTENING }
        if (!remoteManual) leases.entries.removeAll { it.value.source == DecisionSource.REMOTE }
    }

    private fun requestedResources(d: BrainDecision): Set<BehaviorResource> = buildSet {
        add(BehaviorResource.FACE)
        if (!d.speech.isNullOrBlank()) add(BehaviorResource.SPEECH)
        if (d.interruptMotion) {
            add(BehaviorResource.DRIVE)
            add(BehaviorResource.FORK)
        }
        if (d.motion in driveCommands) add(BehaviorResource.DRIVE)
        if (d.motion in forkCommands) add(BehaviorResource.FORK)
        if (d.sequence.any { it.command in driveCommands }) add(BehaviorResource.DRIVE)
        if (d.sequence.any { it.command in forkCommands }) add(BehaviorResource.FORK)
    }

    private fun priorityOf(source: DecisionSource): Int = when (source) {
        DecisionSource.SAFETY -> 100
        DecisionSource.REMOTE -> 90
        DecisionSource.DIRECT_COMMAND -> 80
        DecisionSource.LISTENING -> 75
        DecisionSource.GESTURE -> 70
        DecisionSource.TOUCH -> 65
        DecisionSource.FOLLOW -> 60
        DecisionSource.SEARCH -> 55
        DecisionSource.VISION -> 45
        DecisionSource.PHONE -> 42
        DecisionSource.BATTERY -> 40
        DecisionSource.AI -> 35
        DecisionSource.IDLE -> 20
    }

    private fun defaultHoldMs(source: DecisionSource, d: BrainDecision): Long = when (source) {
        DecisionSource.SAFETY -> 1000L
        DecisionSource.REMOTE -> 1200L
        DecisionSource.DIRECT_COMMAND -> 1800L
        DecisionSource.LISTENING -> 2500L
        DecisionSource.GESTURE -> maxOf(1800L, sequenceLength(d))
        DecisionSource.TOUCH -> maxOf(1800L, sequenceLength(d))
        DecisionSource.FOLLOW -> 800L
        DecisionSource.SEARCH -> 1100L
        DecisionSource.VISION -> if (d.speech != null || d.sequence.isNotEmpty()) 1800L else 350L
        DecisionSource.PHONE -> 1600L
        DecisionSource.BATTERY -> 1800L
        DecisionSource.AI -> 2400L
        DecisionSource.IDLE -> if (d.sequence.isNotEmpty()) maxOf(1800L, sequenceLength(d)) else 500L
    }

    private fun sequenceLength(d: BrainDecision): Long =
        d.sequence.sumOf { it.durationMs + it.delayAfterMs }.coerceAtLeast(d.motionDurationMs)

    companion object {
        val driveCommands = setOf(
            MotionCommand.FORWARD, MotionCommand.BACKWARD,
            MotionCommand.LEFT, MotionCommand.RIGHT
        )
        val forkCommands = setOf(MotionCommand.FORK_UP, MotionCommand.FORK_DOWN)
    }
}
