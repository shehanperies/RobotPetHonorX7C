package com.shehan.robotpet.settings

import android.content.Context

class RobotPrefs(context: Context) {
    private val p = context.getSharedPreferences("robot_pet", Context.MODE_PRIVATE)

    var robotUrl: String
        get() = p.getString("robot_url", "ws://192.168.4.1/ws") ?: "ws://192.168.4.1/ws"
        set(value) = p.edit().putString("robot_url", value.trim()).apply()
    var followEnabled: Boolean
        get() = p.getBoolean("follow_enabled", true)
        set(value) = p.edit().putBoolean("follow_enabled", value).apply()
    var wakeWordEnabled: Boolean
        get() = p.getBoolean("wake_word_enabled", true)
        set(value) = p.edit().putBoolean("wake_word_enabled", value).apply()
    val wakeWord: String get() = "Welly"
    var voiceLanguage: String
        get() = p.getString("voice_language", "en-US") ?: "en-US"
        set(value) = p.edit().putString("voice_language", value.trim().ifBlank { "en-US" }).apply()
    var voiceName: String
        get() = p.getString("voice_name", "") ?: ""
        set(value) = p.edit().putString("voice_name", value).apply()
    var voicePreset: String
        get() = p.getString("voice_preset", "Normal") ?: "Normal"
        set(value) = p.edit().putString("voice_preset", value).apply()
    var robotName: String
        get() {
            val saved = p.getString("robot_name", null)
            return if (saved.isNullOrBlank() || saved == "Milo") "Welly" else saved
        }
        set(value) = p.edit().putString("robot_name", value.trim().ifBlank { "Welly" }).apply()
    var ownerName: String
        get() = p.getString("owner_name", "") ?: ""
        set(value) = p.edit().putString("owner_name", value.trim()).apply()
    var characterInstructions: String
        get() = p.getString("character_instructions", "Welly is curious, playful, affectionate and independent. Short natural replies. Sometimes self-play, sometimes quietly observe.") ?: "Welly is curious, playful, affectionate and independent."
        set(value) = p.edit().putString("character_instructions", value.trim()).apply()
    var characterNeverDo: String
        get() = p.getString("character_never_do", "Never be aggressive, never nag repeatedly, never claim a physical action happened unless the local executor confirmed it.") ?: "Never be aggressive."
        set(value) = p.edit().putString("character_never_do", value.trim()).apply()
    var geminiEnabled: Boolean
        get() = p.getBoolean("gemini_enabled", false)
        set(value) = p.edit().putBoolean("gemini_enabled", value).apply()
    var geminiModel: String
        get() = p.getString("gemini_model", "AUTO") ?: "AUTO"
        set(value) = p.edit().putString("gemini_model", value.trim().ifBlank { "AUTO" }).apply()
    var remoteEnabled: Boolean
        get() = p.getBoolean("remote_enabled", false)
        set(value) = p.edit().putBoolean("remote_enabled", value).apply()
    var remotePort: Int
        get() = p.getInt("remote_port", 8080).coerceIn(1024, 65535)
        set(value) = p.edit().putInt("remote_port", value.coerceIn(1024, 65535)).apply()

    var mindVersion: Int
        get() = p.getInt("mind_version", 0)
        set(value) = p.edit().putInt("mind_version", value).apply()
    var mood: Int
        get() = p.getInt("mood", 0)
        set(value) = p.edit().putInt("mood", value.coerceIn(-100, 100)).apply()
    var annoyance: Int
        get() = p.getInt("annoyance", 0)
        set(value) = p.edit().putInt("annoyance", value.coerceIn(0, 100)).apply()
    var socialNeed: Int
        get() = p.getInt("social_need", 20)
        set(value) = p.edit().putInt("social_need", value.coerceIn(0, 100)).apply()
    var boredom: Int
        get() = p.getInt("boredom", 15)
        set(value) = p.edit().putInt("boredom", value.coerceIn(0, 100)).apply()
    var curiosity: Int
        get() = p.getInt("curiosity", 45)
        set(value) = p.edit().putInt("curiosity", value.coerceIn(0, 100)).apply()
    var energy: Int
        get() = p.getInt("energy", 90)
        set(value) = p.edit().putInt("energy", value.coerceIn(0, 100)).apply()
    var affection: Int
        get() = p.getInt("affection", 50)
        set(value) = p.edit().putInt("affection", value.coerceIn(0, 100)).apply()
    var confidence: Int
        get() = p.getInt("confidence", 50)
        set(value) = p.edit().putInt("confidence", value.coerceIn(0, 100)).apply()

    fun resetMindForV8() {
        mood = 0; annoyance = 0; socialNeed = 20; boredom = 15
        curiosity = 45; energy = 90; affection = 50; confidence = 50
        mindVersion = 8
    }
}
