package com.shehan.robotpet.settings

import android.content.Context

class RobotPrefs(context: Context) {
    private val p = context.getSharedPreferences("robot_pet", Context.MODE_PRIVATE)
    var robotUrl: String
        get() = p.getString("robot_url", "ws://192.168.4.1/ws") ?: "ws://192.168.4.1/ws"
        set(value) = p.edit().putString("robot_url", value.trim()).apply()

    var followEnabled: Boolean
        get() = p.getBoolean("follow_enabled", false)
        set(value) = p.edit().putBoolean("follow_enabled", value).apply()
}
