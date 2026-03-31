package com.copilotreview

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.WindowManager

class CopilotReviewStartup : ProjectActivity {

    private val log = Logger.getInstance(CopilotReviewStartup::class.java)

    override suspend fun execute(project: Project) {
        log.info("[CopilotReview] Startup: initializing for project '${project.name}' at ${project.basePath}")
        val service = CopilotReviewService.getInstance(project)

        // Check Copilot is installed
        val copilotInstalled = service.isCopilotInstalled()
        log.info("[CopilotReview] Startup: Copilot installed = $copilotInstalled")
        if (!copilotInstalled) {
            log.warn("[CopilotReview] Startup: GitHub Copilot plugin is not installed or disabled — aborting.")
            ApplicationManager.getApplication().invokeLater {
                com.intellij.openapi.ui.Messages.showWarningDialog(
                    project,
                    "Copilot Code Review requires the GitHub Copilot plugin to be installed and enabled.",
                    "Copilot Code Review"
                )
            }
            return
        }

        // Check Git repo
        val isGit = service.isGitProject()
        log.info("[CopilotReview] Startup: Git project = $isGit (basePath=${project.basePath})")
        if (!isGit) {
            log.info("[CopilotReview] Startup: No .git folder found walking up from ${project.basePath} — disabling.")
            ApplicationManager.getApplication().invokeLater {
                setStatusBarText(project, "Copilot Review: No Git repo")
            }
            return
        }

        // Set up status bar callback
        service.statusCallback = { text ->
            ApplicationManager.getApplication().invokeLater {
                setStatusBarText(project, "Copilot Review: $text")
            }
        }

        ApplicationManager.getApplication().invokeLater {
            setStatusBarText(project, "Copilot Review: ON")
        }

        log.info("[CopilotReview] Startup: Registering file save listener")

        // Listen for file saves
        project.messageBus.connect().subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    for (event in events) {
                        val file = event.file ?: continue
                        if (!event.isFromSave) continue

                        // Only review files open in the editor
                        val openFiles = FileEditorManager.getInstance(project).openFiles
                        if (file !in openFiles) {
                            log.info("[CopilotReview] Save: Skipping ${file.name} — not open in editor")
                            continue
                        }

                        log.info("[CopilotReview] Save: Scheduling review for ${file.name} (${file.path})")
                        service.scheduleReview(file)
                    }
                }
            }
        )

        log.info("[CopilotReview] Startup: Complete — plugin is active")
    }

    private fun setStatusBarText(project: Project, text: String) {
        val statusBar = WindowManager.getInstance().getStatusBar(project) ?: return
        statusBar.info = text
    }
}
