package com.shortsmaker.app.pipeline

import com.shortsmaker.app.model.Candidate
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Titles/hooks/virality score per clip. Works with no setup (built-in heuristic from
 * [MomentDetector.heuristicMeta]) or, if the user supplies an API key in Settings, calls any
 * OpenAI-compatible chat endpoint (OpenAI, OpenRouter, Groq, local llama.cpp server, etc.) or
 * Anthropic's API directly - entirely optional, mirrors the desktop pipeline's llm.py.
 */
class TitleScorer(
    private val provider: String = "none",        // "none" | "openai" | "anthropic" | "custom"
    private val baseUrl: String = "https://api.openai.com/v1",
    private val model: String = "gpt-4o-mini",
    private val apiKey: String? = null
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    fun score(c: Candidate, languageName: String) {
        if (provider == "none" || apiKey.isNullOrBlank()) return
        try {
            val result = callLlm(c.text.take(6000), c.endSec - c.startSec, languageName)
            if (result != null) {
                c.title = result.optString("title").take(80).ifBlank { c.title }
                c.hookLine = result.optString("hook").ifBlank { c.hookLine }
                c.viralityScore = result.optDouble("virality_score", c.viralityScore.toDouble()).toInt().coerceIn(0, 100)
                c.reason = result.optString("reason")
            }
        } catch (e: Exception) {
            // Network hiccup / bad key -> silently keep the heuristic title already set.
        }
    }

    private fun callLlm(text: String, durationSec: Double, languageName: String): JSONObject? {
        val system = "You are a senior short-form video editor. You judge whether a transcript " +
            "excerpt would make a viral vertical clip and write its on-screen copy. Reply with " +
            "one JSON object only, no markdown."
        val user = "Transcript excerpt (${durationSec.toInt()}s, language: $languageName):\n\"\"\"\n$text\n\"\"\"\n" +
            "Return ONLY: {\"title\": \"<max 60 chars>\", \"hook\": \"<max 12 words>\", " +
            "\"virality_score\": <0-100 integer>, \"reason\": \"<one sentence>\"}. " +
            "Write title/hook in the SAME language and script as the transcript."

        val raw: String = if (provider == "anthropic") {
            val body = JSONObject().apply {
                put("model", model); put("max_tokens", 400); put("temperature", 0.4)
                put("system", system)
                put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", user)))
            }
            val req = Request.Builder().url("https://api.anthropic.com/v1/messages")
                .addHeader("x-api-key", apiKey ?: "").addHeader("anthropic-version", "2023-06-01")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            client.newCall(req).execute().use { resp ->
                val j = JSONObject(resp.body?.string() ?: "{}")
                val content = j.optJSONArray("content") ?: return null
                buildString { for (i in 0 until content.length()) append(content.getJSONObject(i).optString("text")) }
            }
        } else {
            val body = JSONObject().apply {
                put("model", model); put("temperature", 0.4); put("max_tokens", 400)
                put("messages", org.json.JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)))
            }
            val req = Request.Builder().url("$baseUrl/chat/completions")
                .addHeader("Authorization", "Bearer ${apiKey ?: ""}")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            client.newCall(req).execute().use { resp ->
                val j = JSONObject(resp.body?.string() ?: "{}")
                j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content") ?: ""
            }
        }
        val matcher = Pattern.compile("\\{.*}", Pattern.DOTALL).matcher(raw)
        return if (matcher.find()) JSONObject(matcher.group()) else null
    }
}
