package com.copilotreview

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.github.copilot.api.CopilotChatService
import com.github.copilot.agent.conversation.listener.CopilotAgentConversationProgressListener
import com.github.copilot.agent.conversation.listener.CopilotAgentConversationProgressListener.Companion
import com.github.copilot.agent.messageBus.CopilotMessageBus
import com.github.copilot.chat.conversation.agent.rpc.ChatAnnotation
import com.github.copilot.chat.conversation.agent.rpc.ChatNotification
import com.github.copilot.chat.conversation.agent.rpc.ConfirmationRequest
import com.github.copilot.chat.conversation.agent.rpc.Reference
import com.github.copilot.chat.conversation.agent.rpc.UpdatedDocument
import com.github.copilot.chat.conversation.agent.rpc.message.AgentRound
import com.github.copilot.chat.conversation.agent.rpc.message.ContextSizeInfo
import com.github.copilot.chat.conversation.agent.rpc.message.Step
import com.github.copilot.chat.conversation.agent.rpc.message.Thinking
import com.github.copilot.chat.message.references.ChatReference
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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

        val content = ApplicationManager.getApplication().runReadAction<String> {
            String(file.contentsToByteArray())
        }
        val fileName = file.name
        val lang = file.fileType.name
        val lineCount = content.count { it == '\n' } + 1
        log.info("[CopilotReview] reviewFile: Read ${content.length} chars, $lineCount lines, lang=$lang")

        val prompt = buildReviewPrompt(content, fileName, lang)

        // Subscribe to Copilot's message bus to capture the reply
        val replyBuilder = StringBuilder()
        val chatService = project.getService(CopilotChatService::class.java)
        val dataContext = SimpleDataContext.getProjectContext(project)

        val listener = object : CopilotAgentConversationProgressListener {
            override fun onReply(reply: String, annotations: List<ChatAnnotation>, parentTurnId: String?) {
                log.info("[CopilotReview] onReply: received ${reply.length} chars")
                replyBuilder.append(reply)
            }

            override fun onCompleted() {
                log.info("[CopilotReview] onCompleted: total reply ${replyBuilder.length} chars")
                val fullReply = replyBuilder.toString()
                val issues = parseResponse(fullReply)
                log.info("[CopilotReview] onCompleted: parsed ${issues.size} issue(s)")

                reviewInProgress.remove(path)

                ApplicationManager.getApplication().invokeLater {
                    ReviewAnnotator.applyAnnotations(project, path, issues, lineCount)
                    statusCallback?.invoke(if (issues.isEmpty()) "No issues" else "${issues.size} issue(s)")

                    val result = ReviewResult(fileName, path, issues, Date())
                    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Copilot Review")
                    toolWindow?.show {
                        ReviewToolWindowPanel.update(project, result)
                    }
                }
            }

            override fun onError(message: String, code: Int?, type: String?, param: String?, details: String?) {
                log.warn("[CopilotReview] onError: ($code) $message")
                reviewInProgress.remove(path)
                ApplicationManager.getApplication().invokeLater {
                    statusCallback?.invoke("Review failed: $message")
                }
            }

            override fun onCancel() {
                log.info("[CopilotReview] onCancel")
                reviewInProgress.remove(path)
                ApplicationManager.getApplication().invokeLater {
                    statusCallback?.invoke("Review cancelled")
                }
            }

            // No-op for events we don't care about
            override fun onConversationIdReply(id: String, isNew: Boolean) {}
            override fun onTurnIdReply(turnId: String, parentTurnId: String?) {}
            override fun onModelInformationReply(modelName: String?, modelProviderName: String?, modelBillingMultiplier: String?) {}
            override fun onSteps(steps: List<Step>, parentTurnId: String?) {}
            override fun onConfirmationRequest(request: ConfirmationRequest) {}
            override fun onNotifications(notifications: List<ChatNotification>) {}
            override fun onUpdatedDocuments(documents: List<UpdatedDocument>) {}
            override fun onReferences(references: List<ChatReference>, parentTurnId: String?) {}
            override fun onEditAgentRound(editAgentRound: AgentRound, parentTurnId: String?) {}
            override fun onThinking(thinking: Thinking, parentTurnId: String?) {}
            override fun onContextSizeUpdated(info: ContextSizeInfo) {}
            override fun onThinkingComplete(parentTurnId: String?) {}
            override fun onFilter(filter: String) {}
            override fun onSuggestedTitle(title: String) {}
        }

        // Subscribe to progress events before sending the query
        val messageBus = project.getService(CopilotMessageBus::class.java)
        messageBus.subscribe(CopilotAgentConversationProgressListener.Companion.TOPIC, listener, project)

        log.info("[CopilotReview] reviewFile: Sending query via Copilot Chat...")
        chatService.query(dataContext) {
            withInput(prompt)
            withAskMode()
            withNewSession()
            withContextFiles(file)
        }
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
