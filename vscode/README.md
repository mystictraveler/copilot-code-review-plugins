# Copilot Code Review on Save — VS Code Extension

Automatically reviews your code using GitHub Copilot every time you save a file. Issues appear as inline diagnostics (squiggly underlines), in the Output channel, and in a dedicated review panel.

## Requirements

- **VS Code** 1.93+
- **GitHub Copilot** extension installed and signed in
- Project must be a **Git repository**

## Features

- **Auto-review on save** — saves trigger a Copilot-powered code review after a configurable debounce delay
- **Inline diagnostics** — issues appear as colored squiggly underlines with hover tooltips
- **Output channel** — formatted review summary with severity icons and line numbers
- **Review panel** — side panel with color-coded badges, card-style issue list, and file metadata (Cmd+Shift+P → "Copilot: Show Review Panel")
- **Severity levels** — errors (red), warnings (orange), info (blue), hints (gray)
- **Git-aware** — only activates in workspaces with a Git repository
- **Copilot check** — prompts to install Copilot if not found

## Commands

| Command | Description |
|---|---|
| `Copilot: Review Current File` | Manually trigger a review |
| `Copilot: Toggle Auto-Review on Save` | Enable/disable auto-review |
| `Copilot: Clear Review Diagnostics` | Remove all review markers |
| `Copilot: Show Review Panel` | Open the review results panel |

## Settings

| Setting | Default | Description |
|---|---|---|
| `copilotCodeReview.enabled` | `true` | Enable auto-review on save |
| `copilotCodeReview.debounceMs` | `2000` | Delay (ms) after save before review triggers |
| `copilotCodeReview.excludedLanguages` | `["plaintext"]` | Language IDs to skip |

## Install

### From VSIX

```bash
cd vscode
npm install
npm run compile
npx @vscode/vsce package --allow-missing-repository
code --install-extension copilot-code-review-0.0.1.vsix
```

### Development

```bash
cd vscode
npm install
npm run watch
```

Press **F5** in VS Code to launch the Extension Development Host.

## How It Works

```
┌──────────────┐
│  File Saved  │
└──────┬───────┘
       │
       ▼
┌──────────────────┐    no     ┌─────────┐
│ Copilot installed?├─────────►│  Show   │
│ Git repo exists? │           │  Error  │
│ Review enabled?  │           └─────────┘
│ Language allowed?│
└──────┬───────────┘
       │ yes
       ▼
┌──────────────────┐
│  Debounce Wait   │
│  (2000ms default)│
└──────┬───────────┘
       │
       ▼
┌──────────────────┐
│  Capture file    │
│  content & state │
└──────┬───────────┘
       │
       ▼
┌──────────────────┐
│  vscode.lm API   │
│                  │
│  Select gpt-4o  │
│  model from     │
│  Copilot Chat   │
│        │        │
│        ▼        │
│  Send review    │
│  prompt with    │
│  file content   │
│        │        │
│        ▼        │
│  Stream and     │
│  collect        │
│  response       │
└──────┬───────────┘
       │
       ▼
┌──────────────────┐
│  Parse JSON      │
│  response into   │
│  issue list      │
└──────┬───────────┘
       │
       ├──────────────────┬──────────────────┐
       ▼                  ▼                  ▼
┌─────────────┐  ┌──────────────┐  ┌──────────────┐
│   Inline    │  │   Output     │  │   Webview    │
│ Diagnostics │  │   Channel    │  │   Panel      │
│             │  │              │  │              │
│ Squiggly    │  │ Formatted    │  │ HTML view    │
│ underlines  │  │ summary with │  │ with badges, │
│ + tooltips  │  │ severity     │  │ cards, and   │
│ in editor   │  │ icons        │  │ colors       │
└─────────────┘  └──────────────┘  └──────────────┘
```
