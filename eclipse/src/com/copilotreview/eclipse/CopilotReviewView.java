package com.copilotreview.eclipse;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.part.ViewPart;

public class CopilotReviewView extends ViewPart {

    public static final String ID = "com.copilotreview.eclipse.reviewView";
    private static CopilotReviewView instance;
    private Browser browser;

    @Override
    public void createPartControl(Composite parent) {
        browser = new Browser(parent, SWT.NONE);
        browser.setText(buildHtml(null, null, null));
        instance = this;
    }

    @Override
    public void setFocus() {
        if (browser != null) browser.setFocus();
    }

    @Override
    public void dispose() {
        instance = null;
        super.dispose();
    }

    public static void updateResults(String fileName, String filePath, List<ReviewIssue> issues) {
        if (instance != null && instance.browser != null && !instance.browser.isDisposed()) {
            instance.browser.setText(buildHtml(fileName, filePath, issues));
        }
    }

    private static String buildHtml(String fileName, String filePath, List<ReviewIssue> issues) {
        String css = """
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
                font-size: 13px;
            }
            .header {
                background: var(--header-bg); padding: 16px 20px;
                border-bottom: 1px solid var(--border);
            }
            .header h1 { font-size: 16px; font-weight: 600; margin-bottom: 4px; }
            .header .meta { color: var(--hint); font-size: 12px; }
            .summary { display: flex; gap: 8px; padding: 12px 20px; border-bottom: 1px solid var(--border); flex-wrap: wrap; }
            .badge { padding: 3px 10px; border-radius: 12px; font-size: 12px; font-weight: 500; }
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
            .issue-header { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
            .severity-dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
            .severity-dot.error { background: var(--error); }
            .severity-dot.warning { background: var(--warning); }
            .severity-dot.info { background: var(--info); }
            .severity-dot.hint { background: var(--hint); }
            .severity-label { font-size: 11px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.5px; }
            .issue-card.error .severity-label { color: var(--error); }
            .issue-card.warning .severity-label { color: var(--warning); }
            .issue-card.info .severity-label { color: var(--info); }
            .issue-card.hint .severity-label { color: var(--hint); }
            .line-label {
                margin-left: auto; font-size: 12px; color: var(--hint);
                background: var(--bg); padding: 2px 8px; border-radius: 4px;
                font-family: 'JetBrains Mono', 'SF Mono', 'Consolas', monospace;
            }
            .issue-message { color: var(--fg); line-height: 1.5; }
            .success-box {
                background: #1a2e1a; border: 1px solid #2d4a2d; border-radius: 6px;
                padding: 20px; text-align: center; color: var(--success); font-size: 14px; margin-top: 8px;
            }
            .empty { text-align: center; margin-top: 60px; color: var(--hint); }
            .empty h2 { font-weight: 400; margin-bottom: 8px; }
            """;

        if (fileName == null || issues == null) {
            return "<!DOCTYPE html><html><head><style>" + css + "</style></head><body>"
                    + "<div class='empty'><h2>No review results yet</h2>"
                    + "<p>Save a file to see results here.</p></div></body></html>";
        }

        String dateStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        long errors = issues.stream().filter(i -> "error".equals(i.severity)).count();
        long warnings = issues.stream().filter(i -> "warning".equals(i.severity)).count();
        long infos = issues.stream().filter(i -> "info".equals(i.severity)).count();

        StringBuilder badges = new StringBuilder();
        if (errors > 0) badges.append("<span class='badge error'>").append(errors).append(" error").append(errors > 1 ? "s" : "").append("</span>");
        if (warnings > 0) badges.append("<span class='badge warning'>").append(warnings).append(" warning").append(warnings > 1 ? "s" : "").append("</span>");
        if (infos > 0) badges.append("<span class='badge info'>").append(infos).append(" info</span>");

        StringBuilder issuesHtml = new StringBuilder();
        if (issues.isEmpty()) {
            issuesHtml.append("<div class='success-box'>&#10003; No issues found. Code looks good!</div>");
        } else {
            for (ReviewIssue issue : issues) {
                issuesHtml.append("<div class='issue-card ").append(issue.severity).append("'>")
                        .append("<div class='issue-header'>")
                        .append("<span class='severity-dot ").append(issue.severity).append("'></span>")
                        .append("<span class='severity-label'>").append(issue.severity.toUpperCase()).append("</span>")
                        .append("<span class='line-label'>Line ").append(issue.line).append("</span>")
                        .append("</div>")
                        .append("<div class='issue-message'>").append(escHtml(issue.message)).append("</div>")
                        .append("</div>");
            }
        }

        return "<!DOCTYPE html><html><head><style>" + css + "</style></head><body>"
                + "<div class='header'><h1>" + escHtml(fileName) + "</h1>"
                + "<div class='meta'>Reviewed " + dateStr + "</div></div>"
                + (badges.length() > 0 ? "<div class='summary'>" + badges + "</div>" : "")
                + "<div class='issues'>" + issuesHtml + "</div>"
                + "</body></html>";
    }

    private static String escHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
