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
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets

class CopilotHttpClient {

    private val log = Logger.getInstance(CopilotHttpClient::class.java)
    private val gson = Gson()

    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiry: Long = 0

    fun chatCompletion(model: String, messages: List<LmChatMessage>, options: LmChatRequestOptions): String {
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

        conn.outputStream.use { it.write(gson.toJson(requestMap).toByteArray(StandardCharsets.UTF_8)) }

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            val errorBody = try { conn.errorStream?.bufferedReader()?.readText() ?: "no error body" } catch (_: Exception) { "unreadable" }
            throw RuntimeException("Copilot API returned $responseCode: $errorBody")
        }

        val responseBody = conn.inputStream.bufferedReader().readText()
        val json = JsonParser.parseString(responseBody).asJsonObject
        val choices = json.getAsJsonArray("choices")
        if (choices == null || choices.size() == 0) return ""
        return choices[0].asJsonObject.getAsJsonObject("message").get("content")?.asString ?: ""
    }

    private fun getApiToken(): String {
        if (cachedToken != null && System.currentTimeMillis() / 1000 < tokenExpiry - 300) {
            return cachedToken!!
        }

        val oauthToken = try {
            val accounts: Set<GitHubAccountCredentials> = runBlocking { AuthHelper.getAccounts() }
            accounts.firstOrNull()?.token
        } catch (e: Exception) {
            log.warn("Failed to get token from Copilot plugin: ${e.message}")
            null
        } ?: throw IllegalStateException("Could not get OAuth token from GitHub Copilot. Make sure you are signed in.")

        val url = URI("https://api.github.com/copilot_internal/v2/token").toURL()
        val conn = openConnection(url)
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "token $oauthToken")
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "copilot-code-review/0.0.1")
        conn.connectTimeout = 10000
        conn.readTimeout = 10000

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            val errorBody = try { conn.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
            throw RuntimeException("Failed to get Copilot token (HTTP $responseCode): $errorBody")
        }

        val responseBody = conn.inputStream.bufferedReader().readText()
        val json = JsonParser.parseString(responseBody).asJsonObject
        cachedToken = json.get("token").asString
        tokenExpiry = json.get("expires_at").asLong
        return cachedToken!!
    }

    private fun openConnection(url: URL): HttpURLConnection {
        return HttpConfigurable.getInstance().openHttpConnection(url.toString()) as HttpURLConnection
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
