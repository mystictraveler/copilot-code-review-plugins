package com.copilotreview

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages

class ReviewNowAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = CopilotReviewService.getInstance(project)

        if (!service.isCopilotInstalled()) {
            Messages.showWarningDialog(project, "GitHub Copilot plugin is not installed.", "Copilot Code Review")
            return
        }
        if (!service.isGitProject()) {
            Messages.showWarningDialog(project, "Copilot Code Review only works in Git repositories.", "Copilot Code Review")
            return
        }

        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (file == null || file.isDirectory) {
            Messages.showWarningDialog(project, "No file selected to review.", "Copilot Code Review")
            return
        }

        service.reviewFile(file)
    }

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = file != null && !file.isDirectory
    }
}
