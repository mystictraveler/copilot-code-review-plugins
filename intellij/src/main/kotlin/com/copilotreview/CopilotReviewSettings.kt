package com.copilotreview

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(name = "CopilotCodeReviewSettings", storages = [Storage("copilotCodeReview.xml")])
class CopilotReviewSettings : PersistentStateComponent<CopilotReviewSettings.State> {

    data class State(
        var enabled: Boolean = true,
        var debounceMs: Long = 2000,
        var excludedExtensions: String = "txt",
        var scope: String = "file" // "file" or "project"
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(project: Project): CopilotReviewSettings {
            return project.getService(CopilotReviewSettings::class.java)
        }
    }
}
