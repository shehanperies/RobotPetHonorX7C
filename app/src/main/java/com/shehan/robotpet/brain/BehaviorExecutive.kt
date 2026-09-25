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
 * V8 foreground-behavior coordinator.
 *
 * Rules:
 *  - exactly one meaningful PRIMARY behavior at a time;
 *  - TTS is an output of a behavior, never a PRIMARY behavior itself;
 *  - low-priority interruptMotion can NOT punch through a higher-priority behavior;
 *  - while TTS is speaking, passive/vision/idle events are dropped instead of queued;
 *  - safety/manual/user commands may preempt lower-priority autonomous work.
 */
class BehaviorExecutive {
    private val leases = mutableMapOf<BehaviorResource, ResourceLease>()
    private var listening = false
    private var speaking = false
    private var remoteManual = false
    private var lastStartedKey = ""
    private var lastStartedMs = 0L

    fun setListening(active: Boolean, now: Long = System.currentTimeMillis()) {
        listening = active
        leases.entries.removeAll { it.value.source == DecisionSource.LISTENING }
        if (active) {
            val lease = ResourceLease(DecisionSource.LISTENING, priorityOf(DecisionSource.LISTENING), "listening", Long.MAX_VALUE)
            leases[BehaviorResource.PRIMARY] = lease
            leases[BehaviorResource.MIC] = lease
            leases[BehaviorResource.SPEECH] = lease
            leases[BehaviorResource.DRIVE] = lease
            leases[BehaviorResource.FORK] = lease
        }
        expire(now)
    }

    /** Speaking does not replace PRIMARY. It only blocks new low-priority reactions. */
    fun setSpeaking(active: Boolean, now: Long = System.currentTimeMillis()) {
        speaking = active
        expire(now)
    }

    fun setRemoteManual(active: Boolean, now: Long = System.currentTimeMillis()) {
        remoteManual = active
        leases.entries.removeAll { it.value.source == DecisionSource.REMOTE }
        if (active) {
            val lease = ResourceLease(DecisionSource.REMOTE, priorityOf(DecisionSource.REMOTE), "remote", Long.MAX_VALUE)
            leases[BehaviorResource.PRIMARY] = lease
            leases[BehaviorResource.DRIVE] = lease
            leases[BehaviorResource.FORK] = lease
        }
        expire(now)
    }

    fun hasActivePrimary(now: Long = System.currentTimeMillis()): Boolean {
        expire(now)
        return leases.containsKey(BehaviorResource.PRIMARY)
    }

    fun activePrimaryKey(now: Long = System.currentTimeMillis()): String {
        expire(now)
        return leases[BehaviorResource.PRIMARY]?.behaviorKey ?: "idle"
    }

    fun complete(behaviorKey: String, now: Long = System.currentTimeMillis()) {
        leases.entries.removeAll { it.value.behaviorKey == behaviorKey && it.value.untilMs != Long.MAX_VALUE }
        expire(now)
    }

    fun submit(decision: BrainDecision, source: DecisionSource, now: Long = System.currentTimeMillis()): ExecutiveResult {
        expire(now)
        val key = decision.behaviorKey.ifBlank { source.name.lowercase() }
        val priority = priorityOf(source)
        val requested = requestedResources(decision, source)
        val allowed = linkedSetOf<BehaviorResource>()
        val denied = linkedSetOf<BehaviorResource>()
        val preempted = linkedSetOf<BehaviorResource>()

        val currentPrimary = leases[BehaviorResource.PRIMARY]
        val primaryRequested = BehaviorResource.PRIMARY in requested

        // Speech is a child output. While it is active, vision/idle/AI chatter is stale noise.
        if (speaking && primaryRequested && priority < priorityOf(DecisionSource.DIRECT_COMMAND) &&
            source !in setOf(DecisionSource.SAFETY, DecisionSource.REMOTE)) {
            denied += requested.filter { it != BehaviorResource.FACE }
            if (BehaviorResource.FACE in requested) allowed += BehaviorResource.FACE
            return result(decision, source, allowed, preempted, denied, now)
        }

        // Safety always wins. interruptMotion only preempts if this source is actually allowed to
        // beat the current behavior. This closes the V7 low-priority interrupt loophole.
        val safety = source == DecisionSource.SAFETY
        val mayPreemptPrimary = currentPrimary == null || currentPrimary.behaviorKey == key ||
            priority > currentPrimary.priority || (source == DecisionSource.REMOTE && remoteManual)

        if (safety) {
            for (r in listOf(BehaviorResource.PRIMARY, BehaviorResource.DRIVE, BehaviorResource.FORK)) {
                if (leases.containsKey(r)) preempted += r
                leases.remove(r)
                allowed += r
            }
        } else if (decision.interruptMotion && mayPreemptPrimary) {
            for (r in listOf(BehaviorResource.PRIMARY, BehaviorResource.DRIVE, BehaviorResource.FORK)) {
                val current = leases[r]
                if (current != null && current.behaviorKey != key) preempted += r
                leases.remove(r)
            }
        }

        val primaryAfterInterrupt = leases[BehaviorResource.PRIMARY]
        val samePrimary = primaryAfterInterrupt?.behaviorKey == key
        val canTakePrimary = when {
            !primaryRequested -> true
            BehaviorResource.PRIMARY in allowed -> true
            primaryAfterInterrupt == null -> true
            samePrimary -> true
            priority > primaryAfterInterrupt.priority -> true
            source == DecisionSource.REMOTE && remoteManual -> true
            else -> false
        }

        if (primaryRequested && !canTakePrimary) {
            denied += requested.filter { it != BehaviorResource.FACE }
            if (BehaviorResource.FACE in requested) allowed += BehaviorResource.FACE
        } else {
            for (resource in requested) {
                if (resource in allowed) continue
                val current = leases[resource]
                val canUse = current == null || current.behaviorKey == key || priority > current.priority ||
                    (source == DecisionSource.REMOTE && remoteManual)
                if (canUse) {
                    if (current != null && current.behaviorKey != key) preempted += resource
                    allowed += resource
                } else {
                    denied += resource
                }
            }
        }

        // Same event repeated by detector frames may update FACE only. FOLLOW/SEARCH are continuous
        // controllers and need repeated drive pulses, so they are exempt.
        val duplicateWindow = maxOf(1100L, decision.minimumHoldMs.coerceAtMost(4500L))
        val duplicate = key == lastStartedKey && now - lastStartedMs < duplicateWindow
        if (duplicate && source !in setOf(DecisionSource.SAFETY, DecisionSource.REMOTE, DecisionSource.FOLLOW, DecisionSource.SEARCH)) {
            denied += allowed.filter { it != BehaviorResource.FACE }
            allowed.removeAll { it != BehaviorResource.FACE }
        }

        val hold = if (decision.minimumHoldMs > 0L) decision.minimumHoldMs else defaultHoldMs(source, decision)
        val speechHold = if (!decision.speech.isNullOrBlank()) maxOf(hold, 3200L) else hold
        for (resource in allowed) {
            val until = when {
                resource == BehaviorResource.FACE && source in setOf(DecisionSource.VISION, DecisionSource.FOLLOW, DecisionSource.SEARCH) -> now + 350L
                source == DecisionSource.REMOTE && remoteManual -> Long.MAX_VALUE
                source == DecisionSource.LISTENING && listening -> Long.MAX_VALUE
                resource == BehaviorResource.PRIMARY -> now + speechHold
                else -> now + hold
            }
            leases[resource] = ResourceLease(source, priority, key, until)
        }

        if (BehaviorResource.PRIMARY in allowed && !duplicate) {
            lastStartedKey = key
            lastStartedMs = now
        }

        return result(decision, source, allowed, preempted, denied, now)
    }

    fun activeLocks(now: Long = System.currentTimeMillis()): String {
        expire(now)
        if (leases.isEmpty()) return "none"
        return leases.entries.sortedBy { it.key.name }
            .joinToString(",") { (r, l) -> "${r.name}:${l.behaviorKey}" }
    }

    fun reset() {
        leases.clear()
        listening = false
        speaking = false
        remoteManual = false
        lastStartedKey = ""
        lastStartedMs = 0L
    }

    private fun result(
        decision: BrainDecision,
        source: DecisionSource,
        allowed: Set<BehaviorResource>,
        preempted: Set<BehaviorResource>,
        denied: Set<BehaviorResource>,
        now: Long
    ): ExecutiveResult {
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
                when {
                    step.command in driveCommands -> BehaviorResource.DRIVE in allowed
                    step.command in forkCommands -> BehaviorResource.FORK in allowed
                    step.command == MotionCommand.STOP -> BehaviorResource.DRIVE in allowed || BehaviorResource.FORK in allowed
                    else -> false
                }
            },
            interruptMotion = decision.interruptMotion &&
                (BehaviorResource.DRIVE in allowed || BehaviorResource.FORK in allowed)
        )
        return ExecutiveResult(
            decision = filtered,
            source = source,
            allowed = allowed,
            preempted = preempted,
            denied = denied,
            summary = "${source.name}:${decision.behaviorKey} • ${activeLocks(now)}"
        )
    }

    private fun requestedResources(d: BrainDecision, source: DecisionSource): Set<BehaviorResource> = buildSet {
        val meaningful = source !in setOf(DecisionSource.VISION, DecisionSource.IDLE) ||
            !d.speech.isNullOrBlank() || d.motion != MotionCommand.STOP || d.sequence.isNotEmpty() || d.interruptMotion ||
            d.mode in setOf(PetMode.FOLLOWING, PetMode.SEARCHING, PetMode.CONVERSATION, PetMode.OBJECT_PLAY, PetMode.EMERGENCY)
        if (meaningful) add(BehaviorResource.PRIMARY)
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

    private fun expire(now: Long) {
        leases.entries.removeAll { it.value.untilMs != Long.MAX_VALUE && it.value.untilMs <= now }
        if (!listening) leases.entries.removeAll { it.value.source == DecisionSource.LISTENING }
        if (!remoteManual) leases.entries.removeAll { it.value.source == DecisionSource.REMOTE }
    }

    private fun priorityOf(source: DecisionSource): Int = when (source) {
        DecisionSource.SAFETY -> 100
        DecisionSource.REMOTE -> 90
        DecisionSource.DIRECT_COMMAND -> 85
        DecisionSource.LISTENING -> 82
        DecisionSource.GESTURE -> 72
        DecisionSource.TOUCH -> 68
        DecisionSource.FOLLOW -> 60
        DecisionSource.SEARCH -> 55
        DecisionSource.VISION -> 45
        DecisionSource.PHONE -> 42
        DecisionSource.BATTERY -> 40
        DecisionSource.AI -> 35
        DecisionSource.IDLE -> 20
    }

    private fun defaultHoldMs(source: DecisionSource, d: BrainDecision): Long = when (source) {
        DecisionSource.SAFETY -> 1200L
        DecisionSource.REMOTE -> 1200L
        DecisionSource.DIRECT_COMMAND -> maxOf(1800L, sequenceLength(d))
        DecisionSource.LISTENING -> 2500L
        DecisionSource.GESTURE -> maxOf(2000L, sequenceLength(d))
        DecisionSource.TOUCH -> maxOf(1800L, sequenceLength(d))
        DecisionSource.FOLLOW -> 800L
        DecisionSource.SEARCH -> 1000L
        DecisionSource.VISION -> if (d.speech != null || d.sequence.isNotEmpty()) maxOf(2200L, sequenceLength(d)) else 450L
        DecisionSource.PHONE -> 1800L
        DecisionSource.BATTERY -> 2200L
        DecisionSource.AI -> 2600L
        DecisionSource.IDLE -> if (d.sequence.isNotEmpty()) maxOf(2200L, sequenceLength(d)) else 800L
    }

    private fun sequenceLength(d: BrainDecision): Long =
        d.sequence.sumOf { it.durationMs + it.delayAfterMs }.coerceAtLeast(d.motionDurationMs)

    companion object {
        val driveCommands = setOf(MotionCommand.FORWARD, MotionCommand.BACKWARD, MotionCommand.LEFT, MotionCommand.RIGHT)
        val forkCommands = setOf(MotionCommand.FORK_UP, MotionCommand.FORK_DOWN)
    }
}
