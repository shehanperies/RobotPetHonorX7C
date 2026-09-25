package com.shehan.robotpet.voice

object WakeWordMatcher {
    private val accepted = setOf("welly", "wellie", "weli", "welly")

    fun matches(text: String): Boolean {
        val normalized = text.lowercase()
            .replace(Regex("[^a-z ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isBlank()) return false
        val words = normalized.split(' ')
        return words.any { it in accepted } ||
            accepted.any { "hey $it" in normalized || "hi $it" in normalized || "hello $it" in normalized }
    }
}
