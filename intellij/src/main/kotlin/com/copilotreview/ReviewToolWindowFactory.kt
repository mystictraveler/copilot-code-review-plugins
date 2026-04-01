package com.copilotreview

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefBrowser
import javax.swing.*
import java.awt.BorderLayout
import java.text.SimpleDateFormat
import java.util.Date

data class ReviewResult(
    val fileName: String,
    val filePath: String,
    val issues: List<ReviewIssue>,
    val timestamp: Date
)

class ReviewToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ReviewToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, "Review", false)
        toolWindow.contentManager.addContent(content)
        ReviewToolWindowPanel.instances[project] = panel
    }
}

class ReviewToolWindowPanel(private val project: Project) {

    val component: JComponent
    private var browser: JBCefBrowser? = null
    private var fallbackPane: JEditorPane? = null

    init {
        val wrapper = JPanel(BorderLayout())

        // Try JCEF (embedded Chromium), fall back to JEditorPane
        try {
            val jcef = JBCefBrowser()
            jcef.loadHTML(buildHtml(null))
            browser = jcef
            wrapper.add(jcef.component, BorderLayout.CENTER)
        } catch (_: Exception) {
            val pane = JEditorPane().apply {
                contentType = "text/html"
                isEditable = false
                text = buildHtml(null)
            }
            fallbackPane = pane
            wrapper.add(JScrollPane(pane), BorderLayout.CENTER)
        }

        component = wrapper
    }

    fun updateResults(result: ReviewResult) {
        val html = buildHtml(result)
        if (browser != null) {
            browser!!.loadHTML(html)
        } else {
            fallbackPane?.text = html
            fallbackPane?.caretPosition = 0
        }
    }

    private fun buildHtml(result: ReviewResult?): String {
        if (result == null) {
            return """
            <html><head><style>${css()}</style></head><body>
            <div class="empty">
                <h2>No review results yet</h2>
                <p>Save a file or run "Copilot: Review Current File" to see results here.</p>
            </div>
            </body></html>
            """.trimIndent()
        }

        val (fileName, _, issues, timestamp) = result
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(timestamp)

        val errorCount = issues.count { it.severity == "error" }
        val warningCount = issues.count { it.severity == "warning" }
        val infoCount = issues.count { it.severity == "info" }
        val hintCount = issues.count { it.severity == "hint" }

        val badges = buildList {
            if (errorCount > 0) add("""<span class="badge error">$errorCount error${if (errorCount > 1) "s" else ""}</span>""")
            if (warningCount > 0) add("""<span class="badge warning">$warningCount warning${if (warningCount > 1) "s" else ""}</span>""")
            if (infoCount > 0) add("""<span class="badge info">$infoCount info</span>""")
            if (hintCount > 0) add("""<span class="badge hint">$hintCount hint${if (hintCount > 1) "s" else ""}</span>""")
        }

        val issuesHtml = if (issues.isEmpty()) {
            """<div class="success-box">&#10003; No issues found. Code looks good!</div>"""
        } else {
            issues.joinToString("\n") { issue ->
                """
                <div class="issue-card ${issue.severity}">
                    <div class="issue-header">
                        <span class="severity-dot ${issue.severity}"></span>
                        <span class="severity-label">${issue.severity.uppercase()}</span>
                        <span class="line-label">Line ${issue.line}</span>
                    </div>
                    <div class="issue-message">${escHtml(issue.message)}</div>
                </div>
                """.trimIndent()
            }
        }

        return """
        <html><head><style>${css()}</style></head><body>
            <div class="header">
                <h1>${escHtml(fileName)}</h1>
                <div class="meta">Reviewed $dateStr</div>
            </div>
            ${if (badges.isNotEmpty()) """<div class="summary">${badges.joinToString("")}</div>""" else ""}
            <div class="issues">
                $issuesHtml
            </div>
        </body></html>
        """.trimIndent()
    }

    private fun escHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun css(): String = """
        :root {
            --bg: #1e1e1e; --fg: #d4d4d4; --border: #333;
            --card-bg: #252526; --header-bg: #2d2d2d;
            --error: #f44747; --error-bg: #3a1d1d;
            --warning: #cca700; --warning-bg: #3a3518;
            --info: #3794ff; --info-bg: #1a2a3a;
            --hint: #888; --hint-bg: #2a2a2a;
            --success: #4ec9b0;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            background: var(--bg); color: var(--fg);
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif;
            font-size: 13px; padding: 0;
        }
        .header {
            background: var(--header-bg); padding: 16px 20px;
            border-bottom: 1px solid var(--border);
        }
        .header h1 { font-size: 16px; font-weight: 600; margin-bottom: 4px; }
        .header .meta { color: var(--hint); font-size: 12px; }
        .summary {
            display: flex; gap: 8px; padding: 12px 20px;
            border-bottom: 1px solid var(--border); flex-wrap: wrap;
        }
        .badge {
            padding: 3px 10px; border-radius: 12px;
            font-size: 12px; font-weight: 500;
        }
        .badge.error { background: var(--error-bg); color: var(--error); }
        .badge.warning { background: var(--warning-bg); color: var(--warning); }
        .badge.info { background: var(--info-bg); color: var(--info); }
        .badge.hint { background: var(--hint-bg); color: var(--hint); }
        .issues { padding: 12px 20px; }
        .issue-card {
            background: var(--card-bg); border-radius: 6px;
            padding: 12px 16px; margin-bottom: 10px;
            border-left: 3px solid var(--hint);
        }
        .issue-card.error { border-left-color: var(--error); }
        .issue-card.warning { border-left-color: var(--warning); }
        .issue-card.info { border-left-color: var(--info); }
        .issue-card.hint { border-left-color: var(--hint); }
        .issue-header {
            display: flex; align-items: center; gap: 8px; margin-bottom: 6px;
        }
        .severity-dot {
            width: 8px; height: 8px; border-radius: 50%; display: inline-block;
        }
        .severity-dot.error { background: var(--error); }
        .severity-dot.warning { background: var(--warning); }
        .severity-dot.info { background: var(--info); }
        .severity-dot.hint { background: var(--hint); }
        .severity-label {
            font-size: 11px; font-weight: 600; text-transform: uppercase;
            letter-spacing: 0.5px;
        }
        .issue-card.error .severity-label { color: var(--error); }
        .issue-card.warning .severity-label { color: var(--warning); }
        .issue-card.info .severity-label { color: var(--info); }
        .issue-card.hint .severity-label { color: var(--hint); }
        .line-label {
            margin-left: auto; font-size: 12px; color: var(--hint);
            background: var(--bg); padding: 2px 8px; border-radius: 4px;
            font-family: 'JetBrains Mono', 'SF Mono', 'Fira Code', 'Consolas', monospace;
        }
        .issue-message { color: var(--fg); line-height: 1.5; }
        .success-box {
            background: #1a2e1a; border: 1px solid #2d4a2d; border-radius: 6px;
            padding: 20px; text-align: center; color: var(--success);
            font-size: 14px; margin-top: 8px;
        }
        .empty {
            text-align: center; margin-top: 60px; color: var(--hint);
        }
        .empty h2 { font-weight: 400; margin-bottom: 8px; }
    """.trimIndent()

    companion object {
        val instances = mutableMapOf<Project, ReviewToolWindowPanel>()

        fun update(project: Project, result: ReviewResult) {
            instances[project]?.updateResults(result)
        }
    }
}
