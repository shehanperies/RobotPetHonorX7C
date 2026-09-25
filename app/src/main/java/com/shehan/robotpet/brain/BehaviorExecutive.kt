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
 * V7 coordinator. The important change is PRIMARY: only one meaningful behavior owns the
 * robot at a time. Vision may keep tracking in the background, but it cannot start another
 * reaction until the current behavior finishes unless a higher-priority source preempts it.
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
        clearOwned(DecisionSource.LISTENING)
        if (active) {
            val lease = ResourceLease(DecisionSource.LISTENING, 82, "listening", Long.MAX_VALUE)
            leases[BehaviorResource.PRIMARY] = lease
            leases[BehaviorResource.MIC] = lease
            leases[BehaviorResource.SPEECH] = lease
            leases[BehaviorResource.DRIVE] = lease
            leases[BehaviorResource.FORK] = lease
        }
        expire(now)
    }

    fun setSpeaking(active: Boolean, now: Long = System.currentTimeMillis()) {
        speaking = active
        leases.entries.removeAll { it.value.behaviorKey == "tts-speaking" }
        if (active && !listening) {
            val lease = ResourceLease(DecisionSource.LISTENING, 78, "tts-speaking", Long.MAX_VALUE)
            val current = leases[BehaviorResource.PRIMARY]
            if (current == null || current.priority <= lease.priority) leases[BehaviorResource.PRIMARY] = lease
            leases[BehaviorResource.SPEECH] = lease
        }
        expire(now)
    }

    fun setRemoteManual(active: Boolean, now: Long = System.currentTimeMillis()) {
        remoteManual = active
        clearOwned(DecisionSource.REMOTE)
        if (active) {
            val lease = ResourceLease(DecisionSource.REMOTE, 90, "remote", Long.MAX_VALUE)
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

    fun submit(decision: BrainDecision, source: DecisionSource, now: Long = System.currentTimeMillis()): ExecutiveResult {
        expire(now)
        val key = decision.behaviorKey.ifBlank { source.name.lowercase() }
        val priority = priorityOf(source)
        val requested = requestedResources(decision, source)
        val allowed = linkedSetOf<BehaviorResource>()
        val denied = linkedSetOf<BehaviorResource>()
        val preempted = linkedSetOf<BehaviorResource>()

        val hardStop = source == DecisionSource.SAFETY || decision.interruptMotion
        if (hardStop) {
            val primary = leases[BehaviorResource.PRIMARY]
            if (primary != null && primary.behaviorKey != key) preempted += BehaviorResource.PRIMARY
            listOf(BehaviorResource.PRIMARY, BehaviorResource.DRIVE, BehaviorResource.FORK).forEach {
                if (leases.containsKey(it)) preempted += it
                leases.remove(it)
                allowed += it
            }
        }

        val currentPrimary = leases[BehaviorResource.PRIMARY]
        val primaryRequested = BehaviorResource.PRIMARY in requested
        val samePrimary = currentPrimary?.behaviorKey == key
        val canTakePrimary = when {
            !primaryRequested -> true
            BehaviorResource.PRIMARY in allowed -> true
            currentPrimary == null -> true
            samePrimary -> true
            priority > currentPrimary.priority -> true
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
                } else denied += resource
            }
        }

        // Do not restart the same reaction from repeated detector frames. Follow/search are
        // continuous controllers, so they are intentionally exempt from this restart guard.
        val duplicateWindow = maxOf(900L, decision.minimumHoldMs.coerceAtMost(3000L))
        val duplicate = key == lastStartedKey && now - lastStartedMs < duplicateWindow
        if (duplicate && source !in setOf(DecisionSource.SAFETY, DecisionSource.REMOTE, DecisionSource.FOLLOW, DecisionSource.SEARCH)) {
            denied += allowed.filter { it != BehaviorResource.FACE }
            allowed.removeAll { it != BehaviorResource.FACE }
        }

        val hold = if (decision.minimumHoldMs > 0L) decision.minimumHoldMs else defaultHoldMs(source, decision)
        for (resource in allowed) {
            val until = when {
                resource == BehaviorResource.FACE && source in setOf(DecisionSource.VISION, DecisionSource.FOLLOW, DecisionSource.SEARCH) -> now + 300L
                source == DecisionSource.REMOTE && remoteManual -> Long.MAX_VALUE
                else -> now + hold
            }
            leases[resource] = ResourceLease(source, priority, key, until)
        }

        if (BehaviorResource.PRIMARY in allowed && !duplicate) {
            lastStartedKey = key
            lastStartedMs = now
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
            summary = "${source.name}:$key • ${activeLocks(now)}"
        )
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

    private fun clearOwned(source: DecisionSource) {
        leases.entries.removeAll { it.value.source == source }
    }

    private fun expire(now: Long) {
        leases.entries.removeAll { it.value.untilMs != Long.MAX_VALUE && it.value.untilMs <= now }
        if (!listening) leases.entries.removeAll { it.value.source == DecisionSource.LISTENING && it.value.behaviorKey == "listening" }
        if (!speaking) leases.entries.removeAll { it.value.behaviorKey == "tts-speaking" }
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
        DecisionSource.SAFETY -> 1000L
        DecisionSource.REMOTE -> 1200L
        DecisionSource.DIRECT_COMMAND -> maxOf(1700L, sequenceLength(d))
        DecisionSource.LISTENING -> 2500L
        DecisionSource.GESTURE -> maxOf(1900L, sequenceLength(d))
        DecisionSource.TOUCH -> maxOf(1700L, sequenceLength(d))
        DecisionSource.FOLLOW -> 700L
        DecisionSource.SEARCH -> 1000L
        DecisionSource.VISION -> if (d.speech != null || d.sequence.isNotEmpty()) maxOf(1800L, sequenceLength(d)) else 300L
        DecisionSource.PHONE -> 1600L
        DecisionSource.BATTERY -> 1800L
        DecisionSource.AI -> 2400L
        DecisionSource.IDLE -> if (d.sequence.isNotEmpty()) maxOf(1800L, sequenceLength(d)) else 600L
    }

    private fun sequenceLength(d: BrainDecision): Long =
        d.sequence.sumOf { it.durationMs + it.delayAfterMs }.coerceAtLeast(d.motionDurationMs)

    companion object {
        val driveCommands = setOf(MotionCommand.FORWARD, MotionCommand.BACKWARD, MotionCommand.LEFT, MotionCommand.RIGHT)
        val forkCommands = setOf(MotionCommand.FORK_UP, MotionCommand.FORK_DOWN)
    }
}
