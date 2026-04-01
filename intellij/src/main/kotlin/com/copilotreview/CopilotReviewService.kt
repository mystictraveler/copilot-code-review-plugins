package com.copilotreview

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.wm.ToolWindowManager
import com.github.copilot.CopilotPlugin
import java.io.File
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

    var statusCallback: ((String) -> Unit)? = null

    fun isGitProject(): Boolean {
        val basePath = project.basePath ?: return false
        var dir: File? = File(basePath)
        while (dir != null) {
            val gitDir = File(dir, ".git")
            log.info("[CopilotReview] isGitProject: Checking ${gitDir.absolutePath} — exists=${gitDir.exists()}")
            if (gitDir.exists()) return true
            dir = dir.parentFile
        }
        return false
    }

    fun isCopilotInstalled(): Boolean {
        val copilotId = PluginId.getId("com.github.copilot")
        val plugin = PluginManagerCore.getPlugin(copilotId)
        val installed = plugin != null && plugin.isEnabled
        log.info("[CopilotReview] isCopilotInstalled: pluginFound=${plugin != null}, enabled=${plugin?.isEnabled}, result=$installed")
        return installed
    }

    fun scheduleReview(file: VirtualFile) {
        val settings = CopilotReviewSettings.getInstance(project).state
        if (!settings.enabled) {
            log.info("[CopilotReview] scheduleReview: Skipping ${file.name} — plugin disabled in settings")
            return
        }

        val excluded = settings.excludedExtensions.split(",").map { it.trim().lowercase() }
        val ext = file.extension?.lowercase() ?: ""
        if (ext in excluded) {
            log.info("[CopilotReview] scheduleReview: Skipping ${file.name} — extension '$ext' is excluded")
            return
        }

        val path = file.path
        debounceTimers[path]?.cancel()

        log.info("[CopilotReview] scheduleReview: Queuing review for ${file.name} with ${settings.debounceMs}ms debounce")

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
        if (reviewInProgress.contains(path)) {
            log.info("[CopilotReview] reviewFile: Already in progress for ${file.name} — skipping")
            return
        }
        reviewInProgress.add(path)

        log.info("[CopilotReview] reviewFile: Starting review for ${file.name}")
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
                log.info("[CopilotReview] reviewFile: Read ${content.length} chars, $lineCount lines, lang=$lang")

                log.info("[CopilotReview] reviewFile: Calling Copilot via plugin...")
                val issues = callCopilotForReview(content, fileName, lang)
                log.info("[CopilotReview] reviewFile: Got ${issues.size} issue(s) from Copilot")

                for (issue in issues) {
                    log.info("[CopilotReview]   Line ${issue.line} [${issue.severity}]: ${issue.message}")
                }

                ApplicationManager.getApplication().invokeLater {
                    ReviewAnnotator.applyAnnotations(project, path, issues, lineCount)
                    statusCallback?.invoke(if (issues.isEmpty()) "No issues" else "${issues.size} issue(s)")

                    val result = ReviewResult(fileName, path, issues, Date())

                    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Copilot Review")
                    log.info("[CopilotReview] reviewFile: toolWindow=${if (toolWindow != null) "found" else "NOT FOUND"}")
                    toolWindow?.show {
                        log.info("[CopilotReview] reviewFile: Tool window shown, updating panel")
                        ReviewToolWindowPanel.update(project, result)
                    }
                    if (toolWindow == null) {
                        log.warn("[CopilotReview] reviewFile: Tool window 'Copilot Review' not registered — results will not be displayed")
                    }
                }
            } catch (e: Exception) {
                log.warn("[CopilotReview] reviewFile: FAILED — ${e.message}", e)
                ApplicationManager.getApplication().invokeLater {
                    statusCallback?.invoke("Review failed: ${e.message}")
                }
            } finally {
                reviewInProgress.remove(path)
            }
        }.start()
    }

    private fun callCopilotForReview(code: String, fileName: String, lang: String): List<ReviewIssue> {
        val prompt = buildReviewPrompt(code, fileName, lang)

        log.info("[CopilotReview] callCopilotForReview: Sending chat request via Copilot plugin...")

        // Use the Copilot plugin's chat service — handles auth, proxy, and network
        val chatService = com.github.copilot.chat.CopilotChatService.getInstance(project)
        val messages = listOf(
            com.github.copilot.chat.ChatMessage(com.github.copilot.chat.ChatRole.SYSTEM, "You are a code reviewer. You respond only with valid JSON arrays."),
            com.github.copilot.chat.ChatMessage(com.github.copilot.chat.ChatRole.USER, prompt)
        )
        val response = chatService.chat(messages, "gpt-4o")

        log.info("[CopilotReview] callCopilotForReview: Got response, length=${response.length}")
        return parseResponse(response)
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
            log.warn("[CopilotReview] Failed to parse review response: ${e.message}")
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
