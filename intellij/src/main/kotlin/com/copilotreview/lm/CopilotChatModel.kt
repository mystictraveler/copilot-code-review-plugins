package com.copilotreview.lm

import com.intellij.lm.*
import kotlinx.coroutines.flow.flow

class CopilotChatModel(
    override val id: String,
    override val name: String,
    override val family: String,
    override val maxInputTokens: Int
) : LmChatModel {

    override val vendor = "copilot"

    override suspend fun sendRequest(
        messages: List<LmChatMessage>,
        options: LmChatRequestOptions
    ): LmChatResponse {
        return StreamingLmChatResponse(flow {
            val response = CopilotHttpClient.getInstance().chatCompletion(id, messages, options)
            emit(response)
        })
    }
}
