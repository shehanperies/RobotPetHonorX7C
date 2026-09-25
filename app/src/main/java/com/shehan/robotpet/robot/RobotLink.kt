package com.shehan.robotpet.robot

import com.shehan.robotpet.brain.MotionCommand
import com.shehan.robotpet.brain.RobotTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import java.util.concurrent.atomic.AtomicLong

data class RobotLinkDebug(
    val requested: MotionCommand = MotionCommand.STOP,
    val actual: MotionCommand? = null,
    val durationMs: Long = 0L,
    val queuedToWebSocket: Boolean = false,
    val blockedBySafety: Boolean = false,
    val sentAtMs: Long = 0L,
    val txSeq: Long = 0L,
    val ackSeq: Long = 0L,
    val ackAccepted: Boolean? = null,
    val ackReason: String = ""
)

class RobotLink {
    private val client = OkHttpClient.Builder().pingInterval(5, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val seq = AtomicLong(1L)
    private var socket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var ackWatchJob: Job? = null

    private val _telemetry = MutableStateFlow(RobotTelemetry())
    val telemetry: StateFlow<RobotTelemetry> = _telemetry
    private val _debug = MutableStateFlow(RobotLinkDebug())
    val debug: StateFlow<RobotLinkDebug> = _debug

    fun connect(url: String) {
        disconnect()
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) return
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _telemetry.value = _telemetry.value.copy(connected = true, safeToMove = false, lastSeenMs = 0L)
                startHeartbeatAndWatchdog()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val j = JSONObject(text)
                    when (j.optString("type")) {
                        "telemetry" -> {
                            fun optionalFloat(name: String): Float? = if (j.has(name) && !j.isNull(name)) j.optDouble(name).toFloat() else null
                            _telemetry.value = RobotTelemetry(
                                connected = true,
                                safeToMove = j.optBoolean("safeToMove", false),
                                obstacleCm = optionalFloat("obstacleCm") ?: optionalFloat("centerCm"),
                                leftCm = optionalFloat("leftCm"),
                                centerCm = optionalFloat("centerCm") ?: optionalFloat("obstacleCm"),
                                rightCm = optionalFloat("rightCm"),
                                batteryPercent = if (j.has("batteryPercent")) j.optInt("batteryPercent") else null,
                                lastSeenMs = System.currentTimeMillis()
                            )
                        }
                        "ack" -> {
                            val ack = j.optLong("seq", 0L)
                            _debug.value = _debug.value.copy(
                                ackSeq = ack,
                                ackAccepted = if (j.has("accepted")) j.optBoolean("accepted") else true,
                                ackReason = j.optString("reason")
                            )
                        }
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = markDisconnected()
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = markDisconnected()
        })
    }

    fun send(command: MotionCommand, durationMs: Long = 0L): Long {
        val now = System.currentTimeMillis()
        val t = _telemetry.value
        val telemetryFresh = t.connected && t.lastSeenMs > 0L && now - t.lastSeenMs <= 1200L
        val blocked = command != MotionCommand.STOP && (!t.safeToMove || !telemetryFresh)
        val actual = if (blocked) MotionCommand.STOP else command
        val actualDuration = if (actual == MotionCommand.STOP) 0L else durationMs
        val commandSeq = seq.getAndIncrement()
        val queued = socket?.send(RobotProtocol.commandJson(actual, actualDuration, commandSeq)) == true
        _debug.value = _debug.value.copy(
            requested = command,
            actual = actual,
            durationMs = actualDuration,
            queuedToWebSocket = queued,
            blockedBySafety = blocked,
            sentAtMs = now,
            txSeq = commandSeq,
            ackAccepted = null,
            ackReason = if (command != MotionCommand.STOP && !telemetryFresh) "STALE_TELEMETRY" else ""
        )
        if (queued && actual != MotionCommand.STOP) watchAck(commandSeq)
        return commandSeq
    }

    private fun watchAck(commandSeq: Long) {
        ackWatchJob?.cancel()
        ackWatchJob = scope.launch {
            delay(800L)
            val d = _debug.value
            if (d.txSeq == commandSeq && d.ackSeq != commandSeq) {
                _debug.value = d.copy(ackAccepted = false, ackReason = "ACK_TIMEOUT")
                send(MotionCommand.STOP, 0L)
            }
        }
    }

    private fun startHeartbeatAndWatchdog() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            var tick = 0
            while (isActive) {
                if (tick % 4 == 0) socket?.send(RobotProtocol.heartbeatJson())
                val t = _telemetry.value
                val now = System.currentTimeMillis()
                if (t.connected && t.lastSeenMs > 0L && now - t.lastSeenMs > 1200L && t.safeToMove) {
                    _telemetry.value = t.copy(safeToMove = false)
                    send(MotionCommand.STOP, 0L)
                }
                tick++
                delay(250L)
            }
        }
    }

    private fun markDisconnected() {
        heartbeatJob?.cancel(); ackWatchJob?.cancel()
        _telemetry.value = RobotTelemetry()
    }

    fun disconnect() {
        heartbeatJob?.cancel(); ackWatchJob?.cancel()
        socket?.close(1000, "bye"); socket = null
        _telemetry.value = RobotTelemetry()
    }

    fun shutdown() {
        disconnect(); client.dispatcher.executorService.shutdown(); scope.cancel()
    }
}
