# Copilot Code Review on Save — IntelliJ Plugin

Automatically reviews your code using GitHub Copilot every time you save a file. Issues appear as wave underline annotations in the editor and in a dedicated "Copilot Review" tool window with a formatted results panel.

## Requirements

- **IntelliJ IDEA** 2024.2+ (Community or Ultimate)
- **GitHub Copilot** plugin installed and signed in
- Project must be a **Git repository**

## Features

- **Auto-review on save** — saves trigger a Copilot-powered code review after a configurable debounce delay
- **Editor annotations** — issues appear as colored wave underlines with error stripe markers and hover tooltips
- **Copilot Review tool window** — bottom panel with color-coded severity badges, card-style issue list, file name, and timestamp
- **Severity levels** — errors (red), warnings (orange), info (blue), hints (gray)
- **Git-aware** — only activates in projects with a Git repository
- **Copilot check** — shows a warning if the GitHub Copilot plugin is not installed or disabled
- **Stale result detection** — skips applying annotations if the document has changed since the review was requested

## Actions

Available via **Find Action** (Cmd+Shift+A / Ctrl+Shift+A) or the editor right-click menu:

| Action | Description |
|---|---|
| `Copilot: Review Current File` | Manually trigger a review |
| `Copilot: Toggle Auto-Review on Save` | Enable/disable auto-review |
| `Copilot: Clear Review Diagnostics` | Remove all review annotations |

## Settings

Available under **Settings → Tools → Copilot Code Review**:

| Setting | Default | Description |
|---|---|---|
| Enable auto-review on save | `true` | Toggle automatic reviews |
| Debounce delay (ms) | `2000` | Delay after save before review triggers |
| Excluded extensions | `txt` | Comma-separated file extensions to skip |

## Install

### From Zip

1. Open IntelliJ IDEA
2. Go to **Settings → Plugins → gear icon → Install Plugin from Disk...**
3. Select `build/distributions/copilot-code-review-0.0.1.zip`
4. Restart IntelliJ

### Build from Source

Requires Java 17+:

```bash
cd intellij
./gradlew buildPlugin
```

The plugin zip will be at `build/distributions/copilot-code-review-0.0.1.zip`.

### Development

```bash
cd intellij
./gradlew runIde
```

This launches a sandboxed IntelliJ instance with the plugin loaded.

## How It Works

```
┌──────────────┐
│  File Saved  │
└──────┬───────┘
       │
       ▼
┌──────────────────┐    no     ┌─────────┐
│ Copilot installed?├─────────►│  Show   │
│ Git repo exists? │           │ Warning │
│ Review enabled?  │           └─────────┘
│ Extension allowed│
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
│  content & line  │
│  count snapshot  │
└──────┬───────────┘
       │
       ▼
┌──────────────────────────────────────┐
│          Authentication              │
│                                      │
│  ~/.config/github-copilot/apps.json  │
│              │                       │
│              ▼                       │
│  Read OAuth token                    │
│              │                       │
│              ▼                       │
│  GET api.github.com/                 │
│      copilot_internal/v2/token       │
│              │                       │
│              ▼                       │
│  Receive session token               │
│  (cached until expiry)               │
└──────────────┬───────────────────────┘
               │
               ▼
┌──────────────────────────────────────┐
│          Copilot API Call             │
│                                      │
│  POST api.githubcopilot.com/         │
│       chat/completions               │
│                                      │
│  • model: gpt-4o                     │
│  • system: "code reviewer"           │
│  • user: review prompt + code        │
└──────────────┬───────────────────────┘
               │
               ▼
        ┌──────────────┐
        │ Parse JSON   │
        │ response     │
        └──────┬───────┘
               │
               ▼
      ┌──────────────────┐
      │ Stale check:     │    yes
      │ line count       ├──────────► (discard)
      │ changed?         │
      └──────┬───────────┘
             │ no
             │
             ├────────────────────┐
             ▼                    ▼
    ┌──────────────┐    ┌──────────────────┐
    │   Editor     │    │  Copilot Review  │
    │ Annotations  │    │  Tool Window     │
    │              │    │                  │
    │ Wave under-  │    │ HTML panel with  │
    │ lines with   │    │ severity badges, │
    │ severity     │    │ card-style issue │
    │ colors +     │    │ list, file name, │
    │ tooltips     │    │ and timestamp    │
    └──────────────┘    └──────────────────┘
```
