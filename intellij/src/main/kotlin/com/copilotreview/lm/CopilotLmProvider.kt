package com.copilotreview.lm

import com.intellij.lm.LmChatModel
import com.intellij.lm.LmProvider

class CopilotLmProvider : LmProvider {
    override val id = "copilot"
    override val displayName = "GitHub Copilot"

    override suspend fun getAvailableModels(): List<LmChatModel> {
        return listOf(
            CopilotChatModel(id = "gpt-4o", name = "GPT-4o", family = "gpt-4o", maxInputTokens = 128000),
            CopilotChatModel(id = "gpt-4o-mini", name = "GPT-4o Mini", family = "gpt-4o-mini", maxInputTokens = 128000),
            CopilotChatModel(id = "o3-mini", name = "o3-mini", family = "o3-mini", maxInputTokens = 200000),
            CopilotChatModel(id = "claude-sonnet-4-5-20250514", name = "Claude Sonnet 4.5", family = "claude", maxInputTokens = 200000)
        )
    }
}
