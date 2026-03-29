package com.copilotreview

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import java.util.concurrent.ConcurrentHashMap

data class ReviewIssue(
    val line: Int,
    val severity: String,
    val message: String
)

object ReviewAnnotator {

    private const val LAYER = HighlighterLayer.WARNING + 1
    private val REVIEW_HIGHLIGHTER_KEY = Key.create<Boolean>("COPILOT_REVIEW_HIGHLIGHTER")
    private val projectIssues = ConcurrentHashMap<String, List<ReviewIssue>>()

    /**
     * @param requestLineCount the document line count at the time the review was requested.
     *        If the document has changed since, annotations are skipped to avoid misaligned markers.
     */
    fun applyAnnotations(project: Project, filePath: String, issues: List<ReviewIssue>, requestLineCount: Int) {
        projectIssues[filePath] = issues

        val editor = getEditorForFile(project, filePath) ?: return

        // If the document has been edited since the review was requested, skip applying
        // stale results — they would land on the wrong lines.
        if (editor.document.lineCount != requestLineCount) {
            return
        }

        clearAnnotations(editor)

        for (issue in issues) {
            val lineIndex = (issue.line - 1).coerceIn(0, editor.document.lineCount - 1)
            val startOffset = editor.document.getLineStartOffset(lineIndex)
            val endOffset = editor.document.getLineEndOffset(lineIndex)

            if (startOffset > editor.document.textLength || endOffset > editor.document.textLength) continue

            val color = when (issue.severity) {
                "error" -> JBColor.RED
                "warning" -> JBColor.ORANGE
                "info" -> JBColor.BLUE
                else -> JBColor.GRAY
            }

            val attributes = TextAttributes().apply {
                effectType = EffectType.WAVE_UNDERSCORE
                effectColor = color
                errorStripeColor = color
            }

            val highlighter = editor.markupModel.addRangeHighlighter(
                startOffset, endOffset, LAYER, attributes, HighlighterTargetArea.EXACT_RANGE
            )
            highlighter.isGreedyToLeft = false
            highlighter.isGreedyToRight = false
            highlighter.errorStripeTooltip = "[Copilot Review] ${issue.severity.uppercase()}: ${issue.message}"
            highlighter.putUserData(REVIEW_HIGHLIGHTER_KEY, true)
        }
    }

    fun clearAnnotations(project: Project, filePath: String) {
        projectIssues.remove(filePath)
        val editor = getEditorForFile(project, filePath) ?: return
        clearAnnotations(editor)
    }

    fun clearAllAnnotations(project: Project) {
        projectIssues.clear()
        val editors = FileEditorManager.getInstance(project).allEditors
        for (fileEditor in editors) {
            val textEditor = fileEditor as? com.intellij.openapi.fileEditor.TextEditor ?: continue
            clearAnnotations(textEditor.editor)
        }
    }

    private fun clearAnnotations(editor: Editor) {
        val toRemove = editor.markupModel.allHighlighters.filter {
            it.getUserData(REVIEW_HIGHLIGHTER_KEY) == true
        }
        for (h in toRemove) {
            editor.markupModel.removeHighlighter(h)
        }
    }

    private fun getEditorForFile(project: Project, filePath: String): Editor? {
        val editors = FileEditorManager.getInstance(project).allEditors
        for (fileEditor in editors) {
            val textEditor = fileEditor as? com.intellij.openapi.fileEditor.TextEditor ?: continue
            if (textEditor.file?.path == filePath) {
                return textEditor.editor
            }
        }
        return null
    }
}
