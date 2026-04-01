package com.copilotreview.lm

import com.github.copilot.AuthHelper
import com.github.copilot.GitHubAccountCredentials
import com.intellij.lm.LmChatMessage
import com.intellij.lm.LmChatRequestOptions
import com.intellij.lm.LmChatRole
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.net.HttpConfigurable
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets

class CopilotHttpClient {

    private val log = Logger.getInstance(CopilotHttpClient::class.java)
    private val gson = Gson()

    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiry: Long = 0

    fun chatCompletion(model: String, messages: List<LmChatMessage>, options: LmChatRequestOptions): String {
        log.info("[CopilotReview] chatCompletion: starting API request for model='$model', messages=${messages.size}")
        val token = getApiToken()

        val messagesJson = messages.map { msg ->
            mapOf(
                "role" to when (msg.role) {
                    LmChatRole.USER -> "user"
                    LmChatRole.ASSISTANT -> "assistant"
                    LmChatRole.SYSTEM -> "system"
                },
                "content" to msg.content
            )
        }

        val requestMap = mutableMapOf<String, Any>(
            "messages" to messagesJson,
            "model" to model,
            "stream" to false
        )
        options.temperature?.let { requestMap["temperature"] = it }
        options.maxTokens?.let { requestMap["max_tokens"] = it }

        val url = URI("https://api.githubcopilot.com/chat/completions").toURL()
        val conn = openConnection(url)
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Editor-Version", "JetBrains/2024.3")
        conn.setRequestProperty("Editor-Plugin-Version", "copilot-code-review/0.0.1")
        conn.setRequestProperty("Openai-Organization", "github-copilot")
        conn.setRequestProperty("Copilot-Integration-Id", "vscode-chat")
        conn.connectTimeout = 30000
        conn.readTimeout = 60000
        conn.doOutput = true

        val requestStartTime = System.currentTimeMillis()

        try {
            conn.outputStream.use { it.write(gson.toJson(requestMap).toByteArray(StandardCharsets.UTF_8)) }

            val responseCode = conn.responseCode
            val elapsed = System.currentTimeMillis() - requestStartTime

            if (responseCode != 200) {
                val errorBody = try { conn.errorStream?.bufferedReader()?.readText() ?: "no error body" } catch (_: Exception) { "unreadable" }
                log.warn("[CopilotReview] chatCompletion: API error HTTP $responseCode after ${elapsed}ms: $errorBody")
                when (responseCode) {
                    401, 403 -> {
                        cachedToken = null
                        throw RuntimeException("Copilot auth failed ($responseCode). Token cleared for retry. $errorBody")
                    }
                    429 -> throw RuntimeException("Copilot rate limit exceeded. Try again later.")
                    in 500..599 -> throw RuntimeException("Copilot server error ($responseCode). $errorBody")
                    else -> throw RuntimeException("Copilot API returned $responseCode: $errorBody")
                }
            }

            val responseBody = conn.inputStream.bufferedReader().readText()
            log.info("[CopilotReview] chatCompletion: response received in ${elapsed}ms, bodyLength=${responseBody.length}")
            return extractContent(responseBody)
        } catch (e: SocketTimeoutException) {
            val elapsed = System.currentTimeMillis() - requestStartTime
            log.warn("[CopilotReview] chatCompletion: timeout after ${elapsed}ms for model=$model", e)
            throw RuntimeException("Copilot API timed out after ${elapsed}ms. File may be too large or server is slow.", e)
        } catch (e: IOException) {
            val elapsed = System.currentTimeMillis() - requestStartTime
            log.warn("[CopilotReview] chatCompletion: network error after ${elapsed}ms: ${e.message}", e)
            throw RuntimeException("Network error connecting to Copilot API: ${e.message}. Check connection and proxy settings.", e)
        } catch (e: RuntimeException) {
            throw e
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - requestStartTime
            log.warn("[CopilotReview] chatCompletion: unexpected error after ${elapsed}ms: ${e.message}", e)
            throw RuntimeException("Unexpected error during Copilot API call: ${e.message}", e)
        }
    }

    private fun extractContent(responseBody: String): String {
        try {
            val json = JsonParser.parseString(responseBody).asJsonObject
            val choices = json.getAsJsonArray("choices")
            if (choices == null || choices.size() == 0) {
                log.warn("[CopilotReview] extractContent: no choices in response")
                return ""
            }
            return choices[0].asJsonObject.getAsJsonObject("message").get("content")?.asString ?: ""
        } catch (e: JsonSyntaxException) {
            log.warn("[CopilotReview] extractContent: malformed JSON response: ${e.message}")
            return ""
        }
    }

    private fun getApiToken(): String {
        if (cachedToken != null && System.currentTimeMillis() / 1000 < tokenExpiry - 300) {
            log.info("[CopilotReview] getApiToken: using cached token (expires at $tokenExpiry)")
            return cachedToken!!
        }

        log.info("[CopilotReview] getApiToken: cached token expired or absent, acquiring new token")

        val oauthToken = try {
            val accounts: Set<GitHubAccountCredentials> = runBlocking { AuthHelper.getAccounts() }
            accounts.firstOrNull()?.token
        } catch (e: Exception) {
            log.warn("[CopilotReview] getApiToken: failed to get token from Copilot plugin: ${e.message}", e)
            null
        }
        if (oauthToken == null) {
            log.warn("[CopilotReview] getApiToken: no OAuth token available from GitHub Copilot accounts")
            throw IllegalStateException("Could not get OAuth token from GitHub Copilot. Make sure you are signed in.")
        }

        val tokenRequestStart = System.currentTimeMillis()
        try {
            val url = URI("https://api.github.com/copilot_internal/v2/token").toURL()
            val conn = openConnection(url)
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "token $oauthToken")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "copilot-code-review/0.0.1")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val responseCode = conn.responseCode
            val elapsed = System.currentTimeMillis() - tokenRequestStart
            if (responseCode != 200) {
                val errorBody = try { conn.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
                log.warn("[CopilotReview] getApiToken: token exchange failed HTTP $responseCode after ${elapsed}ms: $errorBody")
                throw RuntimeException("Failed to get Copilot token (HTTP $responseCode): $errorBody")
            }

            val responseBody = conn.inputStream.bufferedReader().readText()
            val json = JsonParser.parseString(responseBody).asJsonObject
            cachedToken = json.get("token").asString
            tokenExpiry = json.get("expires_at").asLong
            log.info("[CopilotReview] getApiToken: token acquired in ${elapsed}ms, expires at $tokenExpiry")
            return cachedToken!!
        } catch (e: SocketTimeoutException) {
            log.warn("[CopilotReview] getApiToken: timeout during token exchange", e)
            throw RuntimeException("Copilot token exchange timed out. Check network and proxy settings.", e)
        } catch (e: IOException) {
            log.warn("[CopilotReview] getApiToken: network error: ${e.message}", e)
            throw RuntimeException("Network error during Copilot token exchange: ${e.message}", e)
        } catch (e: RuntimeException) {
            throw e
        } catch (e: Exception) {
            log.warn("[CopilotReview] getApiToken: unexpected error: ${e.message}", e)
            throw RuntimeException("Token exchange failed: ${e.message}", e)
        }
    }

    private fun openConnection(url: URL): HttpURLConnection {
        val httpConfigurable = HttpConfigurable.getInstance()
        if (httpConfigurable.USE_HTTP_PROXY) {
            log.info("[CopilotReview] openConnection: using proxy ${httpConfigurable.PROXY_HOST}:${httpConfigurable.PROXY_PORT} for $url")
        }
        return httpConfigurable.openHttpConnection(url.toString()) as HttpURLConnection
    }

    companion object {
        private var instance: CopilotHttpClient? = null

        @Synchronized
        fun getInstance(): CopilotHttpClient {
            if (instance == null) instance = CopilotHttpClient()
            return instance!!
        }
    }
}
