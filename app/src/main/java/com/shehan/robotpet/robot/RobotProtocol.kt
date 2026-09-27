package com.shehan.robotpet.robot

import com.shehan.robotpet.brain.MotionCommand
import org.json.JSONObject

object RobotProtocol {
    fun commandJson(command: MotionCommand, durationMs: Long): String = JSONObject()
        .put("type", "command")
        .put("command", command.name)
        .put("durationMs", durationMs.coerceIn(0, 3000))
        .put("ts", System.currentTimeMillis())
        .toString()

    fun heartbeatJson(): String = JSONObject()
        .put("type", "heartbeat")
        .put("ts", System.currentTimeMillis())
        .toString()
}
