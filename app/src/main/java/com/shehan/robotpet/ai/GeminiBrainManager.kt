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

data class GeminiStatus(
    val enabled: Boolean = false,
    val keyConfigured: Boolean = false,
    val busy: Boolean = false,
    val selectedModel: String = "AUTO",
    val availableModels: List<String> = emptyList(),
    val lastError: String = "",
    val lastReply: String = "",
    val lastAction: String = ""
)

data class GeminiContext(
    val mind: PetMindSnapshot,
    val faceVisible: Boolean,
    val objectLabel: String?,
    val objectConfidence: Float,
    val phoneBattery: Int,
    val charging: Boolean,
    val robotConnected: Boolean,
    val robotSafe: Boolean,
    val status: String
)

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
        .readTimeout(18, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    private val busy = AtomicBoolean(false)
    private var models: List<String> = emptyList()
    private var lastRequestMs = 0L
    private var pendingWasUserRequest = false

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
        status = status.copy(
            keyConfigured = secrets.hasGeminiKey(),
            lastError = ""
        )
        publish()
        if (status.keyConfigured) refreshModels()
    }

    fun clearKey() {
        secrets.clearGeminiKey()
        models = emptyList()
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
            publish()
            return
        }

        if (!busy.compareAndSet(false, true)) return
        status = status.copy(busy = true, lastError = "")
        publish()

        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000")
            .header("x-goog-api-key", key)
            .get()
            .build()

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
                        val root = JSONObject(body)
                        val arr = root.optJSONArray("models") ?: JSONArray()
                        buildList {
                            for (i in 0 until arr.length()) {
                                val item = arr.optJSONObject(i) ?: continue
                                val methods = item.optJSONArray("supportedGenerationMethods")
                                var supportsGenerate = false
                                if (methods != null) {
                                    for (j in 0 until methods.length()) {
                                        if (methods.optString(j) == "generateContent") {
                                            supportsGenerate = true
                                            break
                                        }
                                    }
                                }
                                if (!supportsGenerate) continue

                                val name = item.optString("name")
                                    .removePrefix("models/")
                                    .trim()
                                if (name.startsWith("gemini") && name.isNotBlank()) add(name)
                            }
                        }.distinct().sorted()
                    }.getOrElse {
                        finishBusy(error = "MODEL_LIST_PARSE")
                        return
                    }

                    models = parsed
                    val auto = resolveModel()
                    busy.set(false)
                    status = status.copy(
                        busy = false,
                        keyConfigured = true,
                        availableModels = parsed,
                        lastError = if (parsed.isEmpty()) "NO_GENERATE_MODELS_FOUND" else "",
                        lastReply = if (parsed.isNotEmpty()) "Gemini ready • $auto" else status.lastReply
                    )
                    publish()
                }
            }
        })
    }

    fun testConnection(temporaryKey: String = "") {
        refreshModels(temporaryKey)
    }

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
            publish()
            return
        }

        val now = System.currentTimeMillis()
        if (!bypassRateLimit && now - lastRequestMs < 18_000L) return
        if (!busy.compareAndSet(false, true)) return
        lastRequestMs = now
        pendingWasUserRequest = !userText.isNullOrBlank()

        val model = resolveModel()
        if (model.isBlank()) {
            val notifyUserFallback = pendingWasUserRequest
            pendingWasUserRequest = false
            busy.set(false)
            status = status.copy(busy = false, lastError = "NO_MODEL")
            publish()
            if (notifyUserFallback) onPlanFailure("GEMINI_NO_MODEL")
            refreshModels()
            return
        }

        status = status.copy(
            busy = true,
            keyConfigured = true,
            selectedModel = prefs.geminiModel.ifBlank { "AUTO" },
            lastError = ""
        )
        publish()

        val prompt = buildPrompt(context, reason, userText)
        val requestJson = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put("text", prompt)
                            )
                        )
                )
            )

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

                    busy.set(false)
                    status = status.copy(
                        busy = false,
                        lastError = "",
                        lastReply = directive.speech.orEmpty(),
                        lastAction = directive.action.name
                    )
                    publish()
                    pendingWasUserRequest = false
                    onDirective(directive)
                }
            }
        })
    }

    private fun resolveModel(): String {
        val configured = prefs.geminiModel.trim()
        if (configured.isNotBlank() && !configured.equals("AUTO", ignoreCase = true)) {
            return configured.removePrefix("models/")
        }

        if (models.isEmpty()) return ""

        val stableFlash = models
            .filter { "flash" in it.lowercase() }
            .filterNot { "preview" in it.lowercase() || "exp" in it.lowercase() }
        if (stableFlash.isNotEmpty()) return stableFlash.last()

        val anyFlash = models.filter { "flash" in it.lowercase() }
        return (anyFlash.lastOrNull() ?: models.last()).removePrefix("models/")
    }

    private fun buildPrompt(
        c: GeminiContext,
        reason: String,
        userText: String?
    ): String {
        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

        return """
You are the optional high-level personality planner for a small friendly desktop robot pet.
The phone app and ESP32 own all motor safety. You NEVER output motor speeds, durations, coordinates,
unsafe actions, or low-level hardware instructions.

Return EXACTLY one JSON object and nothing else:
{"action":"NONE|CHAT|GREET|PLAY|SEARCH|FOLLOW|LOOK_LEFT|LOOK_RIGHT|FORK_WAVE|REST",
 "speech":"zero to one short natural sentence, max 110 characters",
 "emotion":"IDLE|HAPPY|CURIOUS|SLEEPY|STARTLED|SAD|PLAYFUL|LOVE|LONELY",
 "reason":"short reason"}

Personality rules:
- Feel alive, curious and playful, but not needy or repetitive.
- Do not follow merely because a person is visible.
- FOLLOW is only appropriate when explicitly asked, strongly contextually invited, or the user's text says so.
- SEARCH means a gentle bounded search; local safety logic decides whether physical movement is allowed.
- If the person is absent, do not repeatedly call for them.
- Prefer expression, gaze, speech and fork gestures over locomotion.
- Ask occasional context-aware questions, e.g. meal-time or late-night, but never repeat them frequently.
- If the user directly spoke, answer their meaning naturally. Keep it concise.
- Never claim you saw/heard something not present in the context.

Current local time: $time
Trigger: $reason
User speech: ${userText ?: "(none)"}
Face visible: ${c.faceVisible}
Object: ${c.objectLabel ?: "none"} confidence=${"%.2f".format(c.objectConfidence)}
Phone battery: ${c.phoneBattery}% charging=${c.charging}
Robot link: connected=${c.robotConnected} safe=${c.robotSafe}
Current status: ${c.status}
Mind: mood=${c.mind.mood}, annoyance=${c.mind.annoyance},
social=${c.mind.socialNeed}, boredom=${c.mind.boredom},
curiosity=${c.mind.curiosity}, energy=${c.mind.energy},
followActive=${c.mind.followActive}, searchActive=${c.mind.searchActive}
""".trimIndent()
    }

    private fun extractText(body: String): String {
        return runCatching {
            val root = JSONObject(body)
            val candidates = root.optJSONArray("candidates") ?: return@runCatching ""
            val content = candidates.optJSONObject(0)?.optJSONObject("content")
                ?: return@runCatching ""
            val parts = content.optJSONArray("parts") ?: return@runCatching ""
            buildString {
                for (i in 0 until parts.length()) {
                    val t = parts.optJSONObject(i)?.optString("text").orEmpty()
                    if (t.isNotBlank()) append(t)
                }
            }
        }.getOrDefault("")
    }

    private fun parseDirective(raw: String): AiDirective? {
        val cleaned = raw
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null

        return runCatching {
            val j = JSONObject(cleaned.substring(start, end + 1))
            val action = runCatching {
                AiAction.valueOf(j.optString("action", "NONE").uppercase())
            }.getOrDefault(AiAction.NONE)

            AiDirective(
                action = action,
                speech = j.optString("speech").trim().take(110).ifBlank { null },
                emotion = j.optString("emotion").trim().uppercase(),
                reason = j.optString("reason").trim().take(100)
            )
        }.getOrNull()
    }

    private fun finishBusy(error: String, reply: String = "") {
        val notify = pendingWasUserRequest && error.startsWith("GEMINI")
        pendingWasUserRequest = false
        busy.set(false)
        status = status.copy(
            busy = false,
            lastError = error,
            lastReply = reply
        )
        publish()
        if (notify) onPlanFailure(error)
    }

    private fun publish() {
        onStatus(
            status.copy(
                enabled = prefs.geminiEnabled,
                keyConfigured = secrets.hasGeminiKey(),
                selectedModel = prefs.geminiModel.ifBlank { "AUTO" },
                availableModels = models
            )
        )
    }

    fun shutdown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
