package com.copilotreview

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.lm.LmChatMessage
import com.intellij.lm.LmChatRequestOptions
import com.intellij.lm.LmModelSelector
import com.intellij.lm.LmService
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.runBlocking
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
            if (File(dir, ".git").exists()) return true
            dir = dir.parentFile
        }
        return false
    }

    fun scheduleReview(file: VirtualFile) {
        val settings = CopilotReviewSettings.getInstance(project).state
        if (!settings.enabled) {
            log.info("[CopilotReview] scheduleReview skipped: plugin disabled")
            return
        }

        val excluded = settings.excludedExtensions.split(",").map { it.trim().lowercase() }
        val ext = file.extension?.lowercase() ?: ""
        if (ext in excluded) {
            log.info("[CopilotReview] scheduleReview skipped: extension '$ext' is excluded for file '${file.name}'")
            return
        }

        val path = file.path
        log.info("[CopilotReview] scheduleReview: scheduling review for '${file.name}' with ${settings.debounceMs}ms debounce")
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
        if (reviewInProgress.contains(path)) {
            log.info("[CopilotReview] reviewFile: review already in progress for '${file.name}', skipping")
            return
        }
        reviewInProgress.add(path)

        log.info("[CopilotReview] reviewFile: starting review for '${file.name}' (path=$path)")
        statusCallback?.invoke("Reviewing...")

        Thread {
            val reviewStartTime = System.currentTimeMillis()
            try {
                val (content, lineCount) = ApplicationManager.getApplication().runReadAction<Pair<String, Int>> {
                    val text = String(file.contentsToByteArray())
                    Pair(text, text.count { it == '\n' } + 1)
                }

                log.info("[CopilotReview] reviewFile: file='${file.name}', language=${file.fileType.name}, chars=${content.length}, lines=$lineCount")

                val issues = callLmForReview(content, file.name, file.fileType.name)

                val totalElapsed = System.currentTimeMillis() - reviewStartTime
                log.info("[CopilotReview] reviewFile: completed for '${file.name}' in ${totalElapsed}ms, found ${issues.size} issue(s)")

                ApplicationManager.getApplication().invokeLater {
                    ReviewAnnotator.applyAnnotations(project, path, issues, lineCount)
                    statusCallback?.invoke(if (issues.isEmpty()) "No issues" else "${issues.size} issue(s)")

                    val result = ReviewResult(file.name, path, issues, Date())
                    ReviewToolWindowPanel.update(project, result)

                    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Copilot Review")
                    toolWindow?.show()
                }
            } catch (e: Exception) {
                val totalElapsed = System.currentTimeMillis() - reviewStartTime
                log.warn("[CopilotReview] reviewFile: review failed for '${file.name}' after ${totalElapsed}ms: ${e.message}", e)
                ApplicationManager.getApplication().invokeLater {
                    statusCallback?.invoke("Review failed: ${e.message}")
                }
            } finally {
                reviewInProgress.remove(path)
            }
        }.start()
    }

    private fun callLmForReview(code: String, fileName: String, lang: String): List<ReviewIssue> {
        val settings = CopilotReviewSettings.getInstance(project).state
        val modelId = settings.model

        log.info("[CopilotReview] callLmForReview: requested model id='$modelId' for file='$fileName'")

        val lm = LmService.getInstance()
        val models = runBlocking { lm.selectChatModels(LmModelSelector(id = modelId)) }
        if (models.isEmpty()) {
            log.warn("[CopilotReview] callLmForReview: configured model '$modelId' not found, falling back to any available model")
        }
        val resolvedModels = models.ifEmpty { runBlocking { lm.selectChatModels() } }

        if (resolvedModels.isEmpty()) {
            log.warn("[CopilotReview] callLmForReview: no language model available at all")
            throw IllegalStateException("No language model available. Install an LM provider plugin (e.g. LM Copilot Bridge).")
        }

        val model = resolvedModels.first()
        log.info("[CopilotReview] callLmForReview: using model name='${model.name}', id='${model.id}', vendor='${model.vendor}'")

        val prompt = buildReviewPrompt(code, fileName, lang)
        val messages = listOf(
            LmChatMessage.system("You are a code reviewer. You respond only with valid JSON arrays."),
            LmChatMessage.user(prompt)
        )

        val apiStartTime = System.currentTimeMillis()
        val response = runBlocking {
            model.sendRequest(messages, LmChatRequestOptions(temperature = 0.1))
        }

        val responseText = runBlocking { response.text() }
        val apiElapsed = System.currentTimeMillis() - apiStartTime
        log.info("[CopilotReview] callLmForReview: LM API call completed in ${apiElapsed}ms, response length=${responseText.length} chars")

        return parseResponse(responseText)
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

        val fenceMatch = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(jsonStr)
        if (fenceMatch != null) {
            jsonStr = fenceMatch.groupValues[1].trim()
        }

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
            log.warn("[CopilotReview] parseResponse: failed to parse review response: ${e.message}", e)
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
