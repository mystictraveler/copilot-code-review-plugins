package com.copilotreview

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class ToggleReviewAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = CopilotReviewSettings.getInstance(project)
        val newState = !settings.state.enabled
        settings.loadState(settings.state.copy(enabled = newState))

        val status = if (newState) "Enabled" else "Disabled"
        com.intellij.openapi.ui.Messages.showInfoMessage(
            project,
            "Copilot Code Review: $status",
            "Copilot Code Review"
        )

        if (!newState) {
            ReviewAnnotator.clearAllAnnotations(project)
        }
    }
}
