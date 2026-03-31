package com.copilotreview

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import com.intellij.openapi.wm.ToolWindowManager
import java.util.Date
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap

class CopilotReviewService(private val project: Project) {

    private val log = Logger.getInstance(CopilotReviewService::class.java)
    private val debounceTimers = ConcurrentHashMap<String, TimerTask>()
    private val timer = Timer("CopilotReviewDebounce", true)
    private val reviewInProgress = ConcurrentHashMap.newKeySet<String>()
    private val gson = Gson()

    @Volatile
    private var cachedToken: String? = null
    @Volatile
    private var tokenExpiry: Long = 0

    var statusCallback: ((String) -> Unit)? = null

    fun isGitProject(): Boolean {
        val basePath = project.basePath ?: return false
        return File(basePath, ".git").exists()
    }

    fun isCopilotInstalled(): Boolean {
        val copilotId = PluginId.getId("com.github.copilot")
        val plugin = PluginManagerCore.getPlugin(copilotId)
        return plugin != null && plugin.isEnabled
    }

    fun scheduleReview(file: VirtualFile) {
        val settings = CopilotReviewSettings.getInstance(project).state
        if (!settings.enabled) return

        val excluded = settings.excludedExtensions.split(",").map { it.trim().lowercase() }
        val ext = file.extension?.lowercase() ?: ""
        if (ext in excluded) return

        val path = file.path
        debounceTimers[path]?.cancel()

        val task = object : TimerTask() {
            override fun run() {
                debounceTimers.remove(path)
                reviewFile(file)
            }
        }
        debounceTimers[path] = task
        timer.schedule(task, settings.debounceMs)
    }

    fun reviewFile(file: VirtualFile) {
        val path = file.path
        if (reviewInProgress.contains(path)) return
        reviewInProgress.add(path)

        statusCallback?.invoke("Reviewing...")

        Thread {
            try {
                val (content, lineCount) = ApplicationManager.getApplication().runReadAction<Pair<String, Int>> {
                    val text = String(file.contentsToByteArray())
                    val lines = text.count { it == '\n' } + 1
                    Pair(text, lines)
                }
                val fileName = file.name
                val lang = file.fileType.name

                val issues = callCopilotForReview(content, fileName, lang)

                ApplicationManager.getApplication().invokeLater {
                    ReviewAnnotator.applyAnnotations(project, path, issues, lineCount)
                    statusCallback?.invoke(if (issues.isEmpty()) "No issues" else "${issues.size} issue(s)")

                    // Update the tool window panel
                    val result = ReviewResult(fileName, path, issues, Date())
                    ReviewToolWindowPanel.update(project, result)

                    // Show and activate the tool window
                    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Copilot Review")
                    toolWindow?.show()
                }
            } catch (e: Exception) {
                log.warn("Copilot review failed: ${e.message}", e)
                ApplicationManager.getApplication().invokeLater {
                    statusCallback?.invoke("Review failed: ${e.message}")
                }
            } finally {
                reviewInProgress.remove(path)
            }
        }.start()
    }

    private fun callCopilotForReview(code: String, fileName: String, lang: String): List<ReviewIssue> {
        val token = getCopilotApiToken()
        val prompt = buildReviewPrompt(code, fileName, lang)

        val requestBody = gson.toJson(mapOf(
            "messages" to listOf(
                mapOf("role" to "system", "content" to "You are a code reviewer. You respond only with valid JSON arrays."),
                mapOf("role" to "user", "content" to prompt)
            ),
            "model" to "gpt-4o",
            "temperature" to 0.1,
            "stream" to false
        ))

        val url = URI("https://api.githubcopilot.com/chat/completions").toURL()
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Editor-Version", "JetBrains-IC/2024.2")
        conn.setRequestProperty("Editor-Plugin-Version", "copilot-code-review/0.0.1")
        conn.setRequestProperty("Openai-Organization", "github-copilot")
        conn.setRequestProperty("Copilot-Integration-Id", "vscode-chat")
        conn.connectTimeout = 30000
        conn.readTimeout = 60000
        conn.doOutput = true

        conn.outputStream.use { it.write(requestBody.toByteArray()) }

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            val errorBody = try {
                conn.errorStream?.bufferedReader()?.readText() ?: "no error body"
            } catch (_: Exception) { "unreadable" }
            throw RuntimeException("Copilot API returned $responseCode: $errorBody")
        }

        val responseBody = conn.inputStream.bufferedReader().readText()
        return extractReviewFromChatResponse(responseBody)
    }

    private fun getCopilotApiToken(): String {
        // Return cached token if still valid (with 5 min buffer)
        if (cachedToken != null && System.currentTimeMillis() / 1000 < tokenExpiry - 300) {
            return cachedToken!!
        }

        // Read the GitHub OAuth token from Copilot's config
        val oauthToken = readCopilotOAuthToken()
            ?: throw IllegalStateException(
                "Could not find GitHub Copilot OAuth token. Make sure you are signed in to GitHub Copilot."
            )

        // Exchange OAuth token for a Copilot API session token
        val url = URI("https://api.github.com/copilot_internal/v2/token").toURL()
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "token $oauthToken")
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "CopilotCodeReview-IntelliJ/0.0.1")
        conn.connectTimeout = 10000
        conn.readTimeout = 10000

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            val errorBody = try {
                conn.errorStream?.bufferedReader()?.readText() ?: ""
            } catch (_: Exception) { "" }
            throw RuntimeException("Failed to get Copilot token (HTTP $responseCode): $errorBody")
        }

        val responseBody = conn.inputStream.bufferedReader().readText()
        val json = JsonParser.parseString(responseBody).asJsonObject
        cachedToken = json.get("token").asString
        tokenExpiry = json.get("expires_at").asLong

        return cachedToken!!
    }

    private fun readCopilotOAuthToken(): String? {
        // Copilot stores OAuth tokens in well-known config locations
        val homeDir = System.getProperty("user.home")

        // Try the standard locations where Copilot stores credentials
        val configPaths = listOf(
            "$homeDir/.config/github-copilot/hosts.json",
            "$homeDir/.config/github-copilot/apps.json",
            "$homeDir/Library/Application Support/github-copilot/hosts.json",
            "$homeDir/Library/Application Support/github-copilot/apps.json"
        )

        for (path in configPaths) {
            val file = File(path)
            if (!file.exists()) continue

            try {
                val content = file.readText()
                val json = JsonParser.parseString(content).asJsonObject

                // hosts.json format: {"github.com": {"oauth_token": "..."}}
                for (key in json.keySet()) {
                    val entry = json.get(key)
                    if (entry != null && entry.isJsonObject) {
                        val obj = entry.asJsonObject
                        if (obj.has("oauth_token")) {
                            return obj.get("oauth_token").asString
                        }
                    }
                }

                // apps.json format may have the token directly
                if (json.has("oauth_token")) {
                    return json.get("oauth_token").asString
                }
            } catch (e: Exception) {
                log.info("Could not read Copilot config at $path: ${e.message}")
            }
        }

        return null
    }

    private fun extractReviewFromChatResponse(responseBody: String): List<ReviewIssue> {
        val json = JsonParser.parseString(responseBody).asJsonObject
        val choices = json.getAsJsonArray("choices")
        if (choices == null || choices.size() == 0) return emptyList()

        val message = choices[0].asJsonObject.getAsJsonObject("message")
        val content = message.get("content")?.asString ?: return emptyList()

        return parseResponse(content)
    }

    private fun buildReviewPrompt(code: String, fileName: String, lang: String): String {
        return """Review the following $lang file "$fileName" for bugs, security issues, performance problems, and code quality concerns.

For each issue found, respond with ONLY a JSON array. Each element must have:
- "line": the 1-based line number
- "severity": one of "error", "warning", "info", "hint"
- "message": a concise description of the issue

If no issues are found, return an empty array: []

Do NOT include any text outside the JSON array. No markdown fences.

Code:
$code"""
    }

    fun parseResponse(response: String): List<ReviewIssue> {
        var jsonStr = response.trim()

        // Strip markdown fences
        val fenceMatch = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(jsonStr)
        if (fenceMatch != null) {
            jsonStr = fenceMatch.groupValues[1].trim()
        }

        // Find JSON array
        val arrayMatch = Regex("\\[\\s*[\\s\\S]*]").find(jsonStr) ?: return emptyList()
        jsonStr = arrayMatch.value

        return try {
            val type = object : TypeToken<List<Map<String, Any>>>() {}.type
            val raw: List<Map<String, Any>> = gson.fromJson(jsonStr, type)
            raw.mapNotNull { map ->
                val line = (map["line"] as? Number)?.toInt() ?: return@mapNotNull null
                val severity = map["severity"] as? String ?: "warning"
                val message = map["message"] as? String ?: return@mapNotNull null
                ReviewIssue(line, severity, message)
            }
        } catch (e: Exception) {
            log.warn("Failed to parse review response: ${e.message}")
            emptyList()
        }
    }

    fun dispose() {
        timer.cancel()
        debounceTimers.clear()
    }

    companion object {
        fun getInstance(project: Project): CopilotReviewService {
            return project.getService(CopilotReviewService::class.java)
        }
    }
}
