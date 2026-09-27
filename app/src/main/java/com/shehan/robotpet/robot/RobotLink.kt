package com.shehan.robotpet.robot

import com.shehan.robotpet.brain.MotionCommand
import com.shehan.robotpet.brain.RobotTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class RobotLinkDebug(
    val requested: MotionCommand = MotionCommand.STOP,
    val actual: MotionCommand? = null,
    val durationMs: Long = 0L,
    val queuedToWebSocket: Boolean = false,
    val blockedBySafety: Boolean = false,
    val sentAtMs: Long = 0L
)

class RobotLink {
    private val client = OkHttpClient.Builder()
        .pingInterval(5, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO)
    private var socket: WebSocket? = null
    private var heartbeatJob: Job? = null

    private val _telemetry = MutableStateFlow(RobotTelemetry())
    val telemetry: StateFlow<RobotTelemetry> = _telemetry

    private val _debug = MutableStateFlow(RobotLinkDebug())
    val debug: StateFlow<RobotLinkDebug> = _debug

    fun connect(url: String) {
        disconnect()
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) return

        val request = Request.Builder().url(url).build()

        socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    _telemetry.value = _telemetry.value.copy(
                        connected = true,
                        lastSeenMs = System.currentTimeMillis()
                    )
                    startHeartbeat()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        val j = JSONObject(text)
                        if (j.optString("type") == "telemetry") {
                            _telemetry.value = RobotTelemetry(
                                connected = true,
                                safeToMove = j.optBoolean("safeToMove", false),
                                obstacleCm = if (j.has("obstacleCm")) {
                                    j.optDouble("obstacleCm").toFloat()
                                } else null,
                                batteryPercent = if (j.has("batteryPercent")) {
                                    j.optInt("batteryPercent")
                                } else null,
                                lastSeenMs = System.currentTimeMillis()
                            )
                        }
                    }
                }

                override fun onClosed(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String
                ) = markDisconnected()

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?
                ) = markDisconnected()
            }
        )
    }

    fun send(command: MotionCommand, durationMs: Long = 0L) {
        val blocked = command != MotionCommand.STOP &&
            !_telemetry.value.safeToMove

        val actual = if (blocked) MotionCommand.STOP else command
        val actualDuration = if (actual == MotionCommand.STOP) 0L else durationMs

        val queued = socket?.send(
            RobotProtocol.commandJson(actual, actualDuration)
        ) == true

        _debug.value = RobotLinkDebug(
            requested = command,
            actual = actual,
            durationMs = actualDuration,
            queuedToWebSocket = queued,
            blockedBySafety = blocked,
            sentAtMs = System.currentTimeMillis()
        )
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                socket?.send(RobotProtocol.heartbeatJson())
                delay(1000)
            }
        }
    }

    private fun markDisconnected() {
        heartbeatJob?.cancel()
        _telemetry.value = RobotTelemetry()
    }

    fun disconnect() {
        heartbeatJob?.cancel()
        socket?.close(1000, "bye")
        socket = null
        _telemetry.value = RobotTelemetry()
    }

    fun shutdown() {
        disconnect()
        client.dispatcher.executorService.shutdown()
    }
}
