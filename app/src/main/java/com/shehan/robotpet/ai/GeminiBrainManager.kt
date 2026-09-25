package com.shehan.robotpet.ai

import com.shehan.robotpet.brain.AiAction
import com.shehan.robotpet.brain.AiDirective
import com.shehan.robotpet.brain.PetMindSnapshot
import com.shehan.robotpet.settings.RobotPrefs
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.pow

data class GeminiStatus(
    val enabled: Boolean = false,
    val keyConfigured: Boolean = false,
    val busy: Boolean = false,
    val selectedModel: String = "AUTO",
    val availableModels: List<String> = emptyList(),
    val lastError: String = "",
    val lastReply: String = "",
    val lastAction: String = "",
    val lastReason: String = "",
    val cooldownUntilMs: Long = 0L
)

data class GeminiContext(
    val mind: PetMindSnapshot,
    val faceVisible: Boolean,
    val objectLabel: String?,
    val objectConfidence: Float,
    val objectsSummary: String = "",
    val phoneBattery: Int,
    val charging: Boolean,
    val robotConnected: Boolean,
    val robotSafe: Boolean,
    val status: String
)

/**
 * Optional high-level planner. Local V6 brain remains primary. Gemini never receives
 * low-level motor control, and 429 responses open a circuit breaker instead of retry-spam.
 */
class GeminiBrainManager(
    private val prefs: RobotPrefs,
    private val secrets: SecureSecretStore,
    private val onStatus: (GeminiStatus) -> Unit,
    private val onDirective: (AiDirective) -> Unit,
    private val onPlanFailure: (String) -> Unit = {}
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(22, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    private val busy = AtomicBoolean(false)
    private var models: List<String> = emptyList()
    private var lastRequestMs = 0L
    private var pendingWasUserRequest = false
    private var rateLimitStrikes = 0
    private var cooldownUntilMs = 0L
    private val conversation = ArrayDeque<Pair<String, String>>()

    private var status = GeminiStatus(
        enabled = prefs.geminiEnabled,
        keyConfigured = secrets.hasGeminiKey(),
        selectedModel = prefs.geminiModel.ifBlank { "AUTO" }
    )

    init {
        publish()
        if (status.enabled && status.keyConfigured) refreshModels()
    }

    fun setEnabled(value: Boolean) {
        prefs.geminiEnabled = value
        status = status.copy(enabled = value)
        publish()
        if (value && secrets.hasGeminiKey() && models.isEmpty()) refreshModels()
    }

    fun saveKey(value: String) {
        if (value.isNotBlank()) secrets.saveGeminiKey(value)
        status = status.copy(keyConfigured = secrets.hasGeminiKey(), lastError = "")
        publish()
        if (status.keyConfigured) refreshModels()
    }

    fun clearKey() {
        secrets.clearGeminiKey()
        models = emptyList()
        conversation.clear()
        status = status.copy(
            keyConfigured = false,
            availableModels = emptyList(),
            lastError = "",
            lastReply = "",
            lastAction = ""
        )
        publish()
    }

    fun selectModel(value: String) {
        val clean = value.trim().ifBlank { "AUTO" }
        prefs.geminiModel = clean
        status = status.copy(selectedModel = clean)
        publish()
    }

    fun refreshModels(temporaryKey: String = "") {
        val key = temporaryKey.trim().ifBlank { secrets.geminiKey() }
        if (key.isBlank()) {
            status = status.copy(lastError = "NO_API_KEY", keyConfigured = false)
            publish(); return
        }
        if (!busy.compareAndSet(false, true)) return
        status = status.copy(busy = true, lastError = "")
        publish()

        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000")
            .header("x-goog-api-key", key)
            .get().build()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                finishBusy(error = "MODEL_LIST: ${e.message ?: "network error"}")
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        finishBusy(error = "MODEL_LIST HTTP ${it.code}")
                        return
                    }
                    val parsed = runCatching {
                        val arr = JSONObject(body).optJSONArray("models") ?: JSONArray()
                        buildList {
                            for (i in 0 until arr.length()) {
                                val item = arr.optJSONObject(i) ?: continue
                                val methods = item.optJSONArray("supportedGenerationMethods")
                                var generate = false
                                if (methods != null) for (j in 0 until methods.length()) {
                                    if (methods.optString(j) == "generateContent") generate = true
                                }
                                val name = item.optString("name").removePrefix("models/").trim()
                                if (generate && name.startsWith("gemini") && name.isNotBlank()) add(name)
                            }
                        }.distinct().sorted()
                    }.getOrElse {
                        finishBusy(error = "MODEL_LIST_PARSE")
                        return
                    }
                    models = parsed
                    busy.set(false)
                    status = status.copy(
                        busy = false,
                        keyConfigured = true,
                        availableModels = parsed,
                        lastError = if (parsed.isEmpty()) "NO_GENERATE_MODELS_FOUND" else "",
                        lastReply = if (parsed.isNotEmpty()) "Gemini ready • ${resolveModel()}" else status.lastReply
                    )
                    publish()
                }
            }
        })
    }

    fun testConnection(temporaryKey: String = "") = refreshModels(temporaryKey)

    fun plan(
        context: GeminiContext,
        reason: String,
        userText: String? = null,
        bypassRateLimit: Boolean = false
    ) {
        if (!prefs.geminiEnabled) return
        val key = secrets.geminiKey()
        if (key.isBlank()) {
            status = status.copy(keyConfigured = false, lastError = "NO_API_KEY")
            publish(); return
        }

        val now = System.currentTimeMillis()
        val isUser = !userText.isNullOrBlank()
        if (now < cooldownUntilMs) {
            val seconds = ((cooldownUntilMs - now) / 1000L).coerceAtLeast(1L)
            status = status.copy(lastError = "GEMINI_COOLDOWN ${seconds}s", cooldownUntilMs = cooldownUntilMs)
            publish()
            if (isUser) onPlanFailure("GEMINI_COOLDOWN")
            return
        }
        if (!bypassRateLimit && now - lastRequestMs < 60_000L) return
        if (!busy.compareAndSet(false, true)) return

        lastRequestMs = now
        pendingWasUserRequest = isUser
        val model = resolveModel()
        if (model.isBlank()) {
            busy.set(false)
            status = status.copy(busy = false, lastError = "NO_MODEL")
            publish()
            if (isUser) onPlanFailure("GEMINI_NO_MODEL")
            refreshModels(); return
        }

        status = status.copy(
            busy = true,
            keyConfigured = true,
            lastError = "",
            lastReason = reason,
            cooldownUntilMs = cooldownUntilMs
        )
        publish()

        if (isUser) rememberTurn("user", userText!!.take(500))
        val requestJson = buildRequest(context, reason, userText)
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .post(requestJson.toString().toRequestBody(jsonMedia))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                finishBusy(error = "GEMINI: ${e.message ?: "network error"}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string().orEmpty()
                    if (it.code == 429) {
                        openRateLimitCircuit(it)
                        return
                    }
                    if (!it.isSuccessful) {
                        finishBusy(error = "GEMINI HTTP ${it.code}")
                        return
                    }

                    val text = extractText(body)
                    val directive = parseDirective(text)
                    if (directive == null) {
                        finishBusy(error = "GEMINI_BAD_REPLY", reply = text.take(180))
                        return
                    }

                    rateLimitStrikes = 0
                    cooldownUntilMs = 0L
                    if (!directive.speech.isNullOrBlank()) rememberTurn("model", directive.speech)
                    busy.set(false)
                    pendingWasUserRequest = false
                    status = status.copy(
                        busy = false,
                        lastError = "",
                        lastReply = directive.speech.orEmpty(),
                        lastAction = directive.action.name,
                        cooldownUntilMs = 0L
                    )
                    publish()
                    onDirective(directive)
                }
            }
        })
    }

    private fun buildRequest(c: GeminiContext, reason: String, userText: String?): JSONObject {
        val contents = JSONArray()
        conversation.forEach { (role, text) ->
            contents.put(JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text))))
        }
        if (userText.isNullOrBlank()) {
            contents.put(
                JSONObject().put("role", "user").put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", contextMessage(c, reason)))
                )
            )
        } else {
            contents.put(
                JSONObject().put("role", "user").put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", contextMessage(c, reason, omitUserText = true)))
                )
            )
        }

        val schema = JSONObject()
            .put("type", "OBJECT")
            .put(
                "properties",
                JSONObject()
                    .put("action", JSONObject().put("type", "STRING"))
                    .put("speech", JSONObject().put("type", "STRING"))
                    .put("emotion", JSONObject().put("type", "STRING"))
                    .put("reason", JSONObject().put("type", "STRING"))
            )
            .put("required", JSONArray(listOf("action", "speech", "emotion", "reason")))

        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction()))))
            .put("contents", contents)
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.75)
                    .put("maxOutputTokens", 180)
                    .put("responseMimeType", "application/json")
                    .put("responseSchema", schema)
            )
    }

    private fun systemInstruction(): String = """
You are the optional high-level personality planner for ${prefs.robotName}, a small friendly forklift-shaped robot pet.
Owner name: ${prefs.ownerName.ifBlank { "unknown" }}.
Character: ${prefs.characterInstructions.ifBlank { "curious, playful, affectionate, independent, concise, never repetitive" }}
Never do: ${prefs.characterNeverDo.ifBlank { "unsafe actions, threats, claiming physical actions happened when they did not" }}

The LOCAL PHONE BRAIN and ESP32 own safety, motors, fork timing, obstacle checks and behavior arbitration.
You may only choose a high-level intent. Never output speeds, motor timing, coordinates or hardware instructions.
Do not invent camera/sensor facts. Do not demand attention repeatedly. Keep speech natural and short.
Use FOLLOW only after a user invitation/request. Use SEARCH only as a gentle bounded search.
For ordinary idle, gestures, follow, search, sleep and safety, prefer NONE because the local brain already handles them.
Allowed action values: NONE, CHAT, GREET, PLAY, SEARCH, FOLLOW, LOOK_LEFT, LOOK_RIGHT, FORK_WAVE, REST, INSPECT_OBJECT.
Allowed emotion values: IDLE, HAPPY, CURIOUS, SLEEPY, STARTLED, SAD, PLAYFUL, LOVE, LONELY.
Return only the requested JSON object.
""".trimIndent()

    private fun contextMessage(c: GeminiContext, reason: String, omitUserText: Boolean = false): String {
        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        return """
Context update. Trigger=$reason. Time=$time.
FaceVisible=${c.faceVisible}; objects=${c.objectsSummary.ifBlank { c.objectLabel ?: "none" }}; primaryConfidence=${"%.2f".format(c.objectConfidence)}.
PhoneBattery=${c.phoneBattery}% charging=${c.charging}; robotConnected=${c.robotConnected}; robotSafe=${c.robotSafe}.
Status=${c.status}.
Mind: mood=${c.mind.mood}, annoyance=${c.mind.annoyance}, social=${c.mind.socialNeed}, boredom=${c.mind.boredom}, curiosity=${c.mind.curiosity}, energy=${c.mind.energy}, affection=${c.mind.affection}, confidence=${c.mind.confidence}.
${if (omitUserText) "The user's message is already present in conversation history; answer it." else "Choose at most one useful high-level action."}
""".trimIndent()
    }

    private fun openRateLimitCircuit(response: Response) {
        rateLimitStrikes = (rateLimitStrikes + 1).coerceAtMost(6)
        val retryHeaderSeconds = response.header("Retry-After")?.trim()?.toLongOrNull()
        val exponential = (60.0 * 2.0.pow((rateLimitStrikes - 1).toDouble())).toLong().coerceAtMost(900L)
        val seconds = (retryHeaderSeconds ?: exponential).coerceIn(30L, 1800L)
        cooldownUntilMs = System.currentTimeMillis() + seconds * 1000L
        val notify = pendingWasUserRequest
        pendingWasUserRequest = false
        busy.set(false)
        status = status.copy(
            busy = false,
            lastError = "GEMINI HTTP 429 • cooldown ${seconds}s",
            cooldownUntilMs = cooldownUntilMs
        )
        publish()
        if (notify) onPlanFailure("GEMINI_429")
    }

    private fun resolveModel(): String {
        val configured = prefs.geminiModel.trim()
        if (configured.isNotBlank() && !configured.equals("AUTO", true)) return configured.removePrefix("models/")
        if (models.isEmpty()) return ""
        val stableFlash = models.filter { "flash" in it.lowercase() && "preview" !in it.lowercase() && "exp" !in it.lowercase() }
        if (stableFlash.isNotEmpty()) return stableFlash.last()
        return models.filter { "flash" in it.lowercase() }.lastOrNull() ?: models.last()
    }

    private fun rememberTurn(role: String, text: String) {
        if (text.isBlank()) return
        conversation.addLast(role to text.take(500))
        while (conversation.size > 8) conversation.removeFirst()
    }

    private fun extractText(body: String): String = runCatching {
        val candidates = JSONObject(body).optJSONArray("candidates") ?: return@runCatching ""
        val parts = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return@runCatching ""
        buildString {
            for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty())
        }
    }.getOrDefault("")

    private fun parseDirective(raw: String): AiDirective? {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            val j = JSONObject(cleaned.substring(start, end + 1))
            val action = runCatching { AiAction.valueOf(j.optString("action", "NONE").uppercase()) }.getOrDefault(AiAction.NONE)
            AiDirective(
                action = action,
                speech = j.optString("speech").trim().take(120).ifBlank { null },
                emotion = j.optString("emotion").trim().uppercase(),
                reason = j.optString("reason").trim().take(100)
            )
        }.getOrNull()
    }

    private fun finishBusy(error: String, reply: String = "") {
        val notify = pendingWasUserRequest && error.startsWith("GEMINI")
        pendingWasUserRequest = false
        busy.set(false)
        status = status.copy(busy = false, lastError = error, lastReply = reply, cooldownUntilMs = cooldownUntilMs)
        publish()
        if (notify) onPlanFailure(error)
    }

    private fun publish() = onStatus(
        status.copy(
            enabled = prefs.geminiEnabled,
            keyConfigured = secrets.hasGeminiKey(),
            selectedModel = prefs.geminiModel.ifBlank { "AUTO" },
            availableModels = models,
            cooldownUntilMs = cooldownUntilMs
        )
    )

    fun shutdown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
