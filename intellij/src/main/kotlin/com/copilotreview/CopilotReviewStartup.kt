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
        log.info("[CopilotReview] Startup: initializing for project '${project.name}'")
        val service = CopilotReviewService.getInstance(project)

        // Check Git repo
        if (!service.isGitProject()) {
            log.info("[CopilotReview] Startup: no Git repository found in project '${project.name}', disabling")
            ApplicationManager.getApplication().invokeLater {
                setStatusBarText(project, "Copilot Review: No Git repo")
            }
            return
        }
        log.info("[CopilotReview] Startup: Git repository detected for project '${project.name}'")

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
        log.info("[CopilotReview] Startup: registering file save listener for project '${project.name}'")
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

                        log.info("[CopilotReview] Startup: file save detected for '${file.name}'")

                        val settings = CopilotReviewSettings.getInstance(project).state
                        if (settings.scope == "project") {
                            log.info("[CopilotReview] Startup: scope is 'project', scheduling review for all ${openFiles.size} open file(s)")
                            for (openFile in openFiles) {
                                service.scheduleReview(openFile)
                            }
                            break
                        } else {
                            log.info("[CopilotReview] Startup: scope is 'file', scheduling review for '${file.name}' only")
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
