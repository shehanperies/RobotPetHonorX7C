package com.shehan.robotpet.settings

import android.content.Context

class RobotPrefs(context: Context) {
    private val p = context.getSharedPreferences("robot_pet", Context.MODE_PRIVATE)

    var robotUrl: String
        get() = p.getString("robot_url", "ws://192.168.4.1/ws") ?: "ws://192.168.4.1/ws"
        set(value) = p.edit().putString("robot_url", value.trim()).apply()

    // Permission to follow after an explicit command/gesture/AI decision, not always-on following.
    var followEnabled: Boolean
        get() = p.getBoolean("follow_enabled", true)
        set(value) = p.edit().putBoolean("follow_enabled", value).apply()

    var voiceLanguage: String
        get() = p.getString("voice_language", "en-US") ?: "en-US"
        set(value) = p.edit().putString("voice_language", value.trim().ifBlank { "en-US" }).apply()

    var voiceName: String
        get() = p.getString("voice_name", "") ?: ""
        set(value) = p.edit().putString("voice_name", value).apply()

    var voicePreset: String
        get() = p.getString("voice_preset", "Robot") ?: "Robot"
        set(value) = p.edit().putString("voice_preset", value).apply()

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
}
