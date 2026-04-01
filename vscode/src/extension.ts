import * as vscode from 'vscode';

import * as fs from 'fs';
import * as path from 'path';

const DIAGNOSTIC_SOURCE = 'Copilot Review';
let diagnosticCollection: vscode.DiagnosticCollection;
let debounceTimers = new Map<string, NodeJS.Timeout>();
let enabled = true;
let statusBarItem: vscode.StatusBarItem;
let reviewInProgress = new Set<string>();
let outputChannel: vscode.OutputChannel;
let reviewPanel: vscode.WebviewPanel | undefined;

function isCopilotInstalled(): boolean {
    return vscode.extensions.getExtension('GitHub.copilot-chat') !== undefined
        || vscode.extensions.getExtension('GitHub.copilot') !== undefined;
}

function workspaceHasGitRepo(): boolean {
    const folders = vscode.workspace.workspaceFolders;
    if (!folders) return false;
    return folders.some(folder => fs.existsSync(path.join(folder.uri.fsPath, '.git')));
}

export async function activate(context: vscode.ExtensionContext) {
    console.log('[CopilotReview] Activating extension...');

    if (!isCopilotInstalled()) {
        console.log('[CopilotReview] GitHub Copilot not found, aborting activation.');
        vscode.window.showErrorMessage(
            'Copilot Code Review requires GitHub Copilot to be installed.',
            'Install Copilot'
        ).then(selection => {
            if (selection === 'Install Copilot') {
                vscode.commands.executeCommand('workbench.extensions.installExtension', 'GitHub.copilot');
            }
        });
        return;
    }

    if (!workspaceHasGitRepo()) {
        console.log('[CopilotReview] No Git repository found, aborting activation.');
        vscode.window.showWarningMessage('Copilot Code Review is disabled: no Git repository found in this workspace.');
        return;
    }

    console.log('[CopilotReview] Prerequisites met. Initializing...');

    diagnosticCollection = vscode.languages.createDiagnosticCollection('copilotCodeReview');
    context.subscriptions.push(diagnosticCollection);

    outputChannel = vscode.window.createOutputChannel('Copilot Code Review');
    context.subscriptions.push(outputChannel);

    // Status bar
    statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
    statusBarItem.command = 'copilotCodeReview.toggle';
    updateStatusBar();
    statusBarItem.show();
    context.subscriptions.push(statusBarItem);

    // Auto-review on save
    context.subscriptions.push(
        vscode.workspace.onDidSaveTextDocument((document) => {
            if (!enabled) {
                console.log('[CopilotReview] Save detected but review is disabled, skipping.');
                return;
            }
            const config = vscode.workspace.getConfiguration('copilotCodeReview');
            if (!config.get<boolean>('enabled', true)) {
                console.log('[CopilotReview] Save detected but copilotCodeReview.enabled is false, skipping.');
                return;
            }

            const excluded = config.get<string[]>('excludedLanguages', ['plaintext']);
            if (excluded.includes(document.languageId)) {
                console.log(`[CopilotReview] Save detected but language "${document.languageId}" is excluded, skipping.`);
                return;
            }

            const fileName = document.fileName.split('/').pop() ?? document.fileName;
            console.log(`[CopilotReview] Save triggered for "${fileName}" (language: ${document.languageId}).`);

            const debounceMs = config.get<number>('debounceMs', 2000);
            const scope = config.get<string>('scope', 'file');

            const debounceKey = scope === 'project' ? '__project__' : document.uri.toString();

            const existing = debounceTimers.get(debounceKey);
            if (existing) {
                console.log(`[CopilotReview] Resetting debounce timer (${debounceMs}ms) for scope="${scope}".`);
                clearTimeout(existing);
            }

            debounceTimers.set(debounceKey, setTimeout(() => {
                debounceTimers.delete(debounceKey);
                if (scope === 'project') {
                    reviewAllOpenFiles(excluded);
                } else {
                    reviewDocument(document);
                }
            }, debounceMs));
        })
    );

    // Commands
    context.subscriptions.push(
        vscode.commands.registerCommand('copilotCodeReview.reviewNow', () => {
            const editor = vscode.window.activeTextEditor;
            if (!editor) {
                vscode.window.showWarningMessage('No active file to review.');
                return;
            }
            reviewDocument(editor.document);
        }),
        vscode.commands.registerCommand('copilotCodeReview.toggle', () => {
            enabled = !enabled;
            console.log(`[CopilotReview] Toggled: ${enabled ? 'Enabled' : 'Disabled'}.`);
            updateStatusBar();
            vscode.window.showInformationMessage(`Copilot Code Review: ${enabled ? 'Enabled' : 'Disabled'}`);
            if (!enabled) {
                diagnosticCollection.clear();
            }
        }),
        vscode.commands.registerCommand('copilotCodeReview.clearDiagnostics', () => {
            diagnosticCollection.clear();
        }),
        vscode.commands.registerCommand('copilotCodeReview.showPanel', () => {
            showReviewPanel(context);
        })
    );

    // Clean up diagnostics when files close
    context.subscriptions.push(
        vscode.workspace.onDidCloseTextDocument((document) => {
            diagnosticCollection.delete(document.uri);
        })
    );

    console.log('[CopilotReview] Extension activated successfully.');
}

function updateStatusBar() {
    statusBarItem.text = enabled ? '$(eye) Review ON' : '$(eye-closed) Review OFF';
    statusBarItem.tooltip = `Copilot Code Review: ${enabled ? 'Enabled' : 'Disabled'} (click to toggle)`;
}

interface ParsedIssue {
    line: number;
    severity: string;
    message: string;
}

let lastReviewResults: { fileName: string; filePath: string; issues: ParsedIssue[]; timestamp: Date } | undefined;

async function reviewAllOpenFiles(excluded: string[]) {
    const documents = vscode.workspace.textDocuments.filter(doc =>
        doc.uri.scheme === 'file' && !excluded.includes(doc.languageId)
    );
    console.log(`[CopilotReview] Reviewing all open files: ${documents.length} file(s) eligible.`);
    for (const doc of documents) {
        try {
            await reviewDocument(doc);
        } catch (err: any) {
            const name = doc.fileName.split('/').pop() ?? doc.fileName;
            console.error(`[CopilotReview] Error reviewing "${name}" during project review, continuing with remaining files:`, err?.message ?? err);
        }
    }
}

async function reviewDocument(document: vscode.TextDocument) {
    const uri = document.uri.toString();
    const docFileName = document.fileName.split('/').pop() ?? document.fileName;

    if (reviewInProgress.has(uri)) {
        console.log(`[CopilotReview] Review already in progress for "${docFileName}", skipping.`);
        return;
    }
    reviewInProgress.add(uri);

    console.log(`[CopilotReview] Starting review for "${docFileName}" (language: ${document.languageId}, chars: ${document.getText().length}, lines: ${document.lineCount}).`);

    const originalStatus = statusBarItem.text;
    statusBarItem.text = '$(sync~spin) Reviewing...';

    try {
        const startTime = Date.now();
        const { diagnostics, issues } = await callCopilotForReview(document);
        const elapsedMs = Date.now() - startTime;
        console.log(`[CopilotReview] Review completed for "${docFileName}" in ${elapsedMs}ms. Found ${issues.length} issue(s).`);
        diagnosticCollection.set(document.uri, diagnostics);

        const fileName = document.fileName.split('/').pop() ?? document.fileName;

        lastReviewResults = {
            fileName,
            filePath: document.fileName,
            issues,
            timestamp: new Date()
        };

        // Write to output channel
        outputChannel.clear();
        outputChannel.appendLine(`━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━`);
        outputChannel.appendLine(`  COPILOT CODE REVIEW — ${fileName}`);
        outputChannel.appendLine(`  ${lastReviewResults.timestamp.toLocaleString()}`);
        outputChannel.appendLine(`━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━`);
        outputChannel.appendLine('');

        if (issues.length === 0) {
            outputChannel.appendLine('  ✅ No issues found.');
        } else {
            outputChannel.appendLine(`  Found ${issues.length} issue(s):`);
            outputChannel.appendLine('');

            for (const issue of issues) {
                const icon = issue.severity === 'error' ? '🔴' :
                             issue.severity === 'warning' ? '🟡' :
                             issue.severity === 'info' ? '🔵' : '⚪';
                outputChannel.appendLine(`  ${icon} Line ${issue.line} [${issue.severity.toUpperCase()}]`);
                outputChannel.appendLine(`     ${issue.message}`);
                outputChannel.appendLine('');
            }
        }

        outputChannel.appendLine(`━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━`);
        outputChannel.show(true);

        // Update webview panel if open
        if (reviewPanel) {
            reviewPanel.webview.html = buildReviewHtml(lastReviewResults);
        }

    } catch (err: any) {
        console.error(`[CopilotReview] Error reviewing "${docFileName}":`, err?.message ?? err);
        if (err?.message?.includes('model is not available')) {
            vscode.window.showErrorMessage(
                'Copilot Code Review: GitHub Copilot is not available. Make sure the GitHub Copilot Chat extension is installed and signed in.'
            );
        } else {
            vscode.window.showErrorMessage(`Copilot Code Review error: ${err?.message ?? err}`);
        }
    } finally {
        reviewInProgress.delete(uri);
        statusBarItem.text = originalStatus;
        updateStatusBar();
    }
}

async function callCopilotForReview(document: vscode.TextDocument): Promise<{ diagnostics: vscode.Diagnostic[]; issues: ParsedIssue[] }> {
    console.log('[CopilotReview] Selecting language model (family: gpt-4o)...');
    const models = await vscode.lm.selectChatModels({ family: 'gpt-4o' });
    if (models.length === 0) {
        console.error('[CopilotReview] No language models available.');
        throw new Error('Copilot language model is not available. Ensure GitHub Copilot Chat is installed.');
    }
    const model = models[0];
    console.log(`[CopilotReview] Model selected: "${model.name}" (id: ${model.id}, family: ${model.family}, vendor: ${model.vendor}).`);

    const code = document.getText();
    const fileName = document.fileName.split('/').pop() ?? document.fileName;
    const lang = document.languageId;

    console.log(`[CopilotReview] Sending review request for "${fileName}" (language: ${lang}, chars: ${code.length}).`);

    const prompt = `You are a code reviewer. Review the following ${lang} file "${fileName}" for bugs, security issues, performance problems, and code quality concerns.

For each issue found, respond with ONLY a JSON array. Each element must have:
- "line": the 1-based line number
- "severity": one of "error", "warning", "info", "hint"
- "message": a concise description of the issue

If no issues are found, return an empty array: []

Do NOT include any text outside the JSON array. No markdown fences.

Code:
${code}`;

    const messages = [vscode.LanguageModelChatMessage.User(prompt)];
    const cancellationTokenSource = new vscode.CancellationTokenSource();
    try {
        const response = await model.sendRequest(messages, {}, cancellationTokenSource.token);

        let fullResponse = '';
        for await (const chunk of response.text) {
            fullResponse += chunk;
        }

        console.log(`[CopilotReview] Received response (${fullResponse.length} chars). Parsing...`);
        return parseReviewResponse(fullResponse, document);
    } finally {
        cancellationTokenSource.dispose();
    }
}

function parseReviewResponse(response: string, document: vscode.TextDocument): { diagnostics: vscode.Diagnostic[]; issues: ParsedIssue[] } {
    // Extract JSON array from the response, handling markdown fences
    let jsonStr = response.trim();
    const fenceMatch = jsonStr.match(/```(?:json)?\s*([\s\S]*?)```/);
    if (fenceMatch) {
        console.log('[CopilotReview] Stripped markdown fences from response.');
        jsonStr = fenceMatch[1].trim();
    }

    // Try to find a JSON array in the response
    const arrayMatch = jsonStr.match(/\[[\s\S]*\]/);
    if (!arrayMatch) {
        console.warn('[CopilotReview] No JSON array found in response. Raw response (first 500 chars):', response.substring(0, 500));
        return { diagnostics: [], issues: [] };
    }

    let rawIssues: Array<{ line: number; severity: string; message: string }>;
    try {
        rawIssues = JSON.parse(arrayMatch[0]);
    } catch (parseErr: any) {
        console.error('[CopilotReview] Failed to parse JSON from response:', parseErr?.message ?? parseErr);
        console.error('[CopilotReview] Attempted to parse (first 500 chars):', arrayMatch[0].substring(0, 500));
        return { diagnostics: [], issues: [] };
    }

    if (!Array.isArray(rawIssues)) {
        console.warn('[CopilotReview] Parsed JSON is not an array. Type:', typeof rawIssues);
        return { diagnostics: [], issues: [] };
    }

    const validSeverities = new Set(['error', 'warning', 'info', 'hint']);
    const issues: ParsedIssue[] = rawIssues
        .filter(issue => {
            if (!issue.line || !issue.message) {
                console.warn('[CopilotReview] Skipping malformed issue (missing line or message):', JSON.stringify(issue).substring(0, 200));
                return false;
            }
            if (typeof issue.line !== 'number' || issue.line < 1) {
                console.warn(`[CopilotReview] Skipping issue with invalid line number: ${issue.line}`);
                return false;
            }
            return true;
        })
        .map(issue => ({
            line: issue.line,
            severity: validSeverities.has(issue.severity) ? issue.severity : 'warning',
            message: issue.message,
        }));

    console.log(`[CopilotReview] Parsed ${issues.length} valid issue(s) from ${rawIssues.length} raw item(s).`);

    const diagnostics = issues.map(issue => {
        const line = Math.max(0, Math.min((issue.line ?? 1) - 1, document.lineCount - 1));
        const range = document.lineAt(line).range;

        const severity = ({
            'error': vscode.DiagnosticSeverity.Error,
            'warning': vscode.DiagnosticSeverity.Warning,
            'info': vscode.DiagnosticSeverity.Information,
            'hint': vscode.DiagnosticSeverity.Hint,
        } as Record<string, vscode.DiagnosticSeverity>)[issue.severity] ?? vscode.DiagnosticSeverity.Warning;

        const diag = new vscode.Diagnostic(range, issue.message, severity);
        diag.source = DIAGNOSTIC_SOURCE;
        return diag;
    });

    return { diagnostics, issues };
}

function showReviewPanel(context: vscode.ExtensionContext) {
    if (reviewPanel) {
        reviewPanel.reveal(vscode.ViewColumn.Beside);
    } else {
        reviewPanel = vscode.window.createWebviewPanel(
            'copilotReview',
            'Copilot Code Review',
            vscode.ViewColumn.Beside,
            { enableScripts: false }
        );
        reviewPanel.onDidDispose(() => { reviewPanel = undefined; }, null, context.subscriptions);
    }

    if (lastReviewResults) {
        reviewPanel.webview.html = buildReviewHtml(lastReviewResults);
    } else {
        reviewPanel.webview.html = buildReviewHtml(undefined);
    }
}

function buildReviewHtml(results: { fileName: string; filePath: string; issues: ParsedIssue[]; timestamp: Date } | undefined): string {
    const cssVars = `
        --bg: #1e1e1e;
        --fg: #d4d4d4;
        --border: #333;
        --card-bg: #252526;
        --error: #f44747;
        --error-bg: #3a1d1d;
        --warning: #cca700;
        --warning-bg: #3a3518;
        --info: #3794ff;
        --info-bg: #1a2a3a;
        --hint: #888;
        --hint-bg: #2a2a2a;
        --success: #4ec9b0;
        --header-bg: #2d2d2d;
    `;

    if (!results) {
        return `<!DOCTYPE html>
<html><head><style>
    :root { ${cssVars} }
    body { background: var(--bg); color: var(--fg); font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; padding: 24px; }
    .empty { text-align: center; margin-top: 60px; color: var(--hint); }
    .empty h2 { font-weight: 400; }
</style></head><body>
    <div class="empty">
        <h2>No review results yet</h2>
        <p>Save a file or run "Copilot: Review Current File" to see results here.</p>
    </div>
</body></html>`;
    }

    const { fileName, issues, timestamp } = results;

    const errorCount = issues.filter(i => i.severity === 'error').length;
    const warningCount = issues.filter(i => i.severity === 'warning').length;
    const infoCount = issues.filter(i => i.severity === 'info').length;
    const hintCount = issues.filter(i => i.severity === 'hint').length;

    const summaryParts: string[] = [];
    if (errorCount) summaryParts.push(`<span class="badge error">${errorCount} error${errorCount > 1 ? 's' : ''}</span>`);
    if (warningCount) summaryParts.push(`<span class="badge warning">${warningCount} warning${warningCount > 1 ? 's' : ''}</span>`);
    if (infoCount) summaryParts.push(`<span class="badge info">${infoCount} info</span>`);
    if (hintCount) summaryParts.push(`<span class="badge hint">${hintCount} hint${hintCount > 1 ? 's' : ''}</span>`);

    const escHtml = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

    let issuesHtml: string;
    if (issues.length === 0) {
        issuesHtml = `<div class="success-box"><span class="success-icon">&#10003;</span> No issues found. Code looks good!</div>`;
    } else {
        issuesHtml = issues.map(issue => {
            const sev = issue.severity;
            return `
            <div class="issue-card ${sev}">
                <div class="issue-header">
                    <span class="severity-dot ${sev}"></span>
                    <span class="severity-label">${sev.toUpperCase()}</span>
                    <span class="line-label">Line ${issue.line}</span>
                </div>
                <div class="issue-message">${escHtml(issue.message)}</div>
            </div>`;
        }).join('\n');
    }

    return `<!DOCTYPE html>
<html><head><style>
    :root { ${cssVars} }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
        background: var(--bg); color: var(--fg);
        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
        font-size: 13px; padding: 0;
    }
    .header {
        background: var(--header-bg); padding: 16px 20px;
        border-bottom: 1px solid var(--border);
    }
    .header h1 { font-size: 16px; font-weight: 600; margin-bottom: 4px; }
    .header .meta { color: var(--hint); font-size: 12px; }
    .summary { display: flex; gap: 8px; padding: 12px 20px; border-bottom: 1px solid var(--border); flex-wrap: wrap; }
    .badge {
        padding: 3px 10px; border-radius: 12px; font-size: 12px; font-weight: 500;
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
        font-size: 11px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.5px;
    }
    .issue-card.error .severity-label { color: var(--error); }
    .issue-card.warning .severity-label { color: var(--warning); }
    .issue-card.info .severity-label { color: var(--info); }
    .issue-card.hint .severity-label { color: var(--hint); }
    .line-label {
        margin-left: auto; font-size: 12px; color: var(--hint);
        background: var(--bg); padding: 2px 8px; border-radius: 4px;
        font-family: 'SF Mono', 'Fira Code', 'Consolas', monospace;
    }
    .issue-message { color: var(--fg); line-height: 1.5; }
    .success-box {
        background: #1a2e1a; border: 1px solid #2d4a2d; border-radius: 6px;
        padding: 20px; text-align: center; color: var(--success);
        font-size: 14px; margin-top: 8px;
    }
    .success-icon { font-size: 20px; margin-right: 8px; }
</style></head><body>
    <div class="header">
        <h1>${escHtml(fileName)}</h1>
        <div class="meta">Reviewed ${timestamp.toLocaleString()}</div>
    </div>
    ${summaryParts.length > 0 ? `<div class="summary">${summaryParts.join('')}</div>` : ''}
    <div class="issues">
        ${issuesHtml}
    </div>
</body></html>`;
}

export function deactivate() {
    console.log('[CopilotReview] Deactivating extension...');
    for (const timer of debounceTimers.values()) {
        clearTimeout(timer);
    }
    debounceTimers.clear();
    console.log('[CopilotReview] Extension deactivated.');
}
