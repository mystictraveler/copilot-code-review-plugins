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
        val service = CopilotReviewService.getInstance(project)

        // Check Copilot is installed
        if (!service.isCopilotInstalled()) {
            log.warn("Copilot Code Review: GitHub Copilot plugin is not installed or disabled.")
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
        if (!service.isGitProject()) {
            log.info("Copilot Code Review: No Git repository found in project, disabling.")
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
                        if (file !in openFiles) continue

                        val settings = CopilotReviewSettings.getInstance(project).state
                        if (settings.scope == "project") {
                            for (openFile in openFiles) {
                                service.scheduleReview(openFile)
                            }
                            break
                        } else {
                            service.scheduleReview(file)
                        }
                    }
                }
            }
        )
    }

    private fun setStatusBarText(project: Project, text: String) {
        val statusBar = WindowManager.getInstance().getStatusBar(project) ?: return
        statusBar.info = text
    }
}
