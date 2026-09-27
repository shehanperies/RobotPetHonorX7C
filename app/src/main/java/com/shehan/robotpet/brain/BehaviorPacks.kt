package com.shehan.robotpet.brain

import kotlin.random.Random

enum class BehaviorPack {
    TOUCH,
    PETTING,
    WAVE,
    PRAISE,
    PEACE,
    LOVE,
    FOUND_PERSON,
    ATTENTION,
    SELF_PLAY
}

data class BehaviorStyle(
    val variant: Int,
    val speak: Boolean,
    val activeMotion: Boolean
)

/**
 * Small context-weighted variation selector.
 *
 * This does NOT choose the behavior itself. PetBrain still chooses the correct
 * context/behavior family. This class only chooses a coherent variation inside
 * that family, which keeps random variation from turning into unrelated actions.
 */
class BehaviorPackSelector {
    private val lastVariant = mutableMapOf<BehaviorPack, Int>()
    private val lastSpokenAt = mutableMapOf<BehaviorPack, Long>()

    fun choose(
        pack: BehaviorPack,
        mood: Int,
        annoyance: Int,
        socialNeed: Int,
        boredom: Int,
        energy: Int,
        quiet: Boolean,
        now: Long = System.currentTimeMillis()
    ): BehaviorStyle {
        val count = variantCount(pack)
        val previous = lastVariant[pack] ?: -1
        var variant = Random.nextInt(count)
        if (count > 1 && variant == previous) {
            variant = (variant + 1 + Random.nextInt(count - 1)) % count
        }
        lastVariant[pack] = variant

        var speakChance = when (pack) {
            BehaviorPack.TOUCH -> 46
            BehaviorPack.PETTING -> 34
            BehaviorPack.WAVE -> 58
            BehaviorPack.PRAISE -> 46
            BehaviorPack.PEACE -> 38
            BehaviorPack.LOVE -> 52
            BehaviorPack.FOUND_PERSON -> 62
            BehaviorPack.ATTENTION -> 88
            BehaviorPack.SELF_PLAY -> 8
        }

        // Internal state influences the STYLE, not the trigger itself.
        if (socialNeed >= 70) speakChance += 10
        if (mood >= 55) speakChance += 4
        if (annoyance >= 55) speakChance -= 18
        if (energy <= 30) speakChance -= 20
        if (quiet) speakChance = 0

        // Stop the same family from chattering repeatedly even when several valid
        // trigger cycles happen close together.
        val lastSpeech = lastSpokenAt[pack] ?: 0L
        if (now - lastSpeech < 8_000L && pack != BehaviorPack.ATTENTION) {
            speakChance = minOf(speakChance, 12)
        }

        val speak = Random.nextInt(100) < speakChance.coerceIn(0, 95)
        if (speak) lastSpokenAt[pack] = now

        var activeChance = when (pack) {
            BehaviorPack.PETTING -> 70
            BehaviorPack.SELF_PLAY -> 82
            else -> 76
        }
        if (energy <= 35) activeChance -= 35
        if (boredom >= 70 && pack == BehaviorPack.SELF_PLAY) activeChance += 12
        if (annoyance >= 70 && pack !in setOf(BehaviorPack.LOVE, BehaviorPack.PETTING)) {
            activeChance -= 15
        }

        val active = Random.nextInt(100) < activeChance.coerceIn(10, 95)
        return BehaviorStyle(variant = variant, speak = speak, activeMotion = active)
    }

    private fun variantCount(pack: BehaviorPack): Int = when (pack) {
        BehaviorPack.TOUCH -> 4
        BehaviorPack.PETTING -> 4
        BehaviorPack.WAVE -> 4
        BehaviorPack.PRAISE -> 3
        BehaviorPack.PEACE -> 3
        BehaviorPack.LOVE -> 4
        BehaviorPack.FOUND_PERSON -> 3
        BehaviorPack.ATTENTION -> 4
        BehaviorPack.SELF_PLAY -> 4
    }
}
