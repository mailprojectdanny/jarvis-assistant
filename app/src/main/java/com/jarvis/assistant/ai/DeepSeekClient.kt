package com.jarvis.assistant.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Talks to DeepSeek's OpenAI-compatible chat completions endpoint.
 * Only ever called for UNKNOWN_COMPLEX intents (see LocalIntentRouter) — never for
 * simple device commands. Only the user's utterance + minimal conversation context
 * is sent; no memory/device data is auto-attached (spec: "never automatically upload
 * personal memory/device data").
 */
class DeepSeekClient(private val apiKeyProvider: () -> String?) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable data class ChatMessage(val role: String, val content: String)
    @Serializable data class ChatRequest(val model: String = "deepseek-chat", val messages: List<ChatMessage>)
    @Serializable data class ChatChoice(val message: ChatMessage)
    @Serializable data class ChatResponse(val choices: List<ChatChoice>)

    sealed class Result {
        data class Success(val text: String) : Result()
        data class Failure(val reason: String) : Result()
        object NoApiKey : Result()
    }

    suspend fun ask(userText: String, recentContext: List<String> = emptyList()): Result = withContext(Dispatchers.IO) {
        val key = apiKeyProvider() ?: return@withContext Result.NoApiKey
        val messages = buildList {
            add(ChatMessage("system", "You are JARVIS, a witty but efficient voice assistant. Keep answers short enough to speak aloud naturally."))
            recentContext.takeLast(6).forEach { add(ChatMessage("user", it)) }
            add(ChatMessage("user", userText))
        }
        val body = json.encodeToString(ChatRequest(messages = messages)).toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://api.deepseek.com/chat/completions")
            .addHeader("Authorization", "Bearer $key")
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext Result.Failure("DeepSeek error ${resp.code}")
                val text = resp.body?.string() ?: return@withContext Result.Failure("Empty response")
                val parsed = json.decodeFromString<ChatResponse>(text)
                val answer = parsed.choices.firstOrNull()?.message?.content
                    ?: return@withContext Result.Failure("No answer in response")
                Result.Success(answer.trim())
            }
        } catch (e: Exception) {
            Result.Failure(e.message ?: "Network error")
        }
    }

    /** Validates a key with a minimal cheap call before saving it in settings. */
    suspend fun testKey(candidateKey: String): Boolean = withContext(Dispatchers.IO) {
        val body = json.encodeToString(
            ChatRequest(messages = listOf(ChatMessage("user", "ping")))
        ).toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://api.deepseek.com/chat/completions")
            .addHeader("Authorization", "Bearer $candidateKey")
            .post(body)
            .build()
        try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }
}
