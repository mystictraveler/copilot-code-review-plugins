# Copilot Code Review Plugins

VS Code extension and IntelliJ plugin that automatically review your code using GitHub Copilot on every file save. Issues are surfaced as editor diagnostics/annotations and in a dedicated review panel with severity badges.

Both plugins share the same core workflow: save a file, wait for a configurable debounce delay, send the file contents to Copilot for review, parse the structured JSON response, and display the results inline in the editor and in a formatted panel.

## Features

| Feature | VS Code | IntelliJ |
|---|:---:|:---:|
| Auto-review on save with debounce | Yes | Yes |
| Review scope: single file or all open files (project) | Yes | Yes |
| Excluded file types / languages | Yes | Yes |
| Dedicated review panel with severity badges | Webview panel | JCEF / JEditorPane tool window |
| Editor diagnostics / annotations | VS Code Diagnostics API | Wave underline highlighters with tooltips |
| Toggle enable / disable | Yes | Yes |
| Manual "Review Now" command | Yes | Yes |
| Clear diagnostics command | Yes | Yes |
| Git repository detection | Yes | Yes |
| Stale result detection | N/A | Yes (skips if line count changed) |
| Status bar indicator | Yes | Yes |
| Copilot installation check | Yes | Yes |

### Severity Levels

Both plugins categorize issues into four severity levels with distinct visual indicators:

- **Error** (red) -- bugs, crashes, security vulnerabilities
- **Warning** (orange) -- potential problems, bad practices
- **Info** (blue) -- suggestions, improvements
- **Hint** (gray) -- minor style or convention notes

### Git Repository Detection

Both plugins require the project to be inside a Git repository. The VS Code extension checks for a `.git` folder in each workspace folder root. The IntelliJ plugin walks up the directory tree from the project base path, checking each parent directory for a `.git` folder until one is found or the filesystem root is reached.

## VS Code Extension

### How It Works

The extension uses the `vscode.lm` Language Model API to communicate with GitHub Copilot. It calls `vscode.lm.selectChatModels({ family: 'gpt-4o' })` to get a Copilot-provided chat model, then sends a review prompt with the file contents using `model.sendRequest()`. The response is streamed, parsed as JSON, and mapped to VS Code `Diagnostic` objects.

Results are displayed in three places:

1. **Problems panel** -- standard VS Code diagnostics with severity icons
2. **Output channel** -- "Copilot Code Review" output channel with formatted text and emoji severity indicators
3. **Webview panel** -- HTML panel (opened via "Copilot: Show Review Panel") with color-coded severity badges and card-style issue list

### Prerequisites

- VS Code 1.93.0 or later
- GitHub Copilot extension (`GitHub.copilot`) or GitHub Copilot Chat extension (`GitHub.copilot-chat`) installed and signed in
- Project must contain a Git repository (`.git` folder in a workspace folder)

### Commands

| Command | Title |
|---|---|
| `copilotCodeReview.reviewNow` | Copilot: Review Current File |
| `copilotCodeReview.toggle` | Copilot: Toggle Auto-Review on Save |
| `copilotCodeReview.clearDiagnostics` | Copilot: Clear Review Diagnostics |
| `copilotCodeReview.showPanel` | Copilot: Show Review Panel |

### Settings

All settings are under the `copilotCodeReview` namespace in `settings.json`:

| Setting | Type | Default | Description |
|---|---|---|---|
| `copilotCodeReview.enabled` | `boolean` | `true` | Enable automatic code review on save |
| `copilotCodeReview.debounceMs` | `number` | `2000` | Debounce delay in milliseconds before triggering review after save |
| `copilotCodeReview.excludedLanguages` | `string[]` | `["plaintext"]` | Language IDs to exclude from auto-review (e.g., `plaintext`, `markdown`, `json`) |
| `copilotCodeReview.scope` | `"file" \| "project"` | `"file"` | `file` reviews only the saved file; `project` reviews all open files |

### Build

```bash
cd vscode
npm install
npm run compile
```

To package as a `.vsix`:

```bash
cd vscode
npx @vscode/vsce package --allow-missing-repository
```

## IntelliJ Plugin

### How It Works

The plugin uses the IntelliJ Language Model API (`com.intellij.lm`) to access LLM capabilities. It includes a built-in Copilot LM provider (`CopilotLmProvider`) that registers with the LM API via the `com.intellij.lm.provider` extension point. This provider bridges GitHub Copilot's chat completions API into the IntelliJ LM framework, making Copilot models (GPT-4o, GPT-4o Mini, o3-mini, Claude Sonnet 4.5) available through the standard `LmService.selectChatModels()` interface.

The review service calls `LmService.getInstance().selectChatModels(LmModelSelector(family = "gpt-4o"))` to get a model, sends the review prompt, and parses the JSON response into `ReviewIssue` objects.

#### Architecture

```
plugin.xml
  |
  +-- registers CopilotLmProvider as com.intellij.lm.provider
  |
  +-- CopilotLmProvider : LmProvider
  |     |
  |     +-- getAvailableModels() -> [CopilotChatModel, ...]
  |
  +-- CopilotChatModel : LmChatModel
  |     |
  |     +-- sendRequest() delegates to CopilotHttpClient
  |
  +-- CopilotHttpClient
  |     |
  |     +-- Authentication: AuthHelper.getAccounts() from com.github.copilot
  |     +-- Token exchange: GET api.github.com/copilot_internal/v2/token
  |     +-- Chat completions: POST api.githubcopilot.com/chat/completions
  |     +-- Proxy support: HttpConfigurable.getInstance().openHttpConnection()
  |     +-- Token caching with expiry-based refresh
  |
  +-- CopilotReviewService (project service)
  |     |
  |     +-- scheduleReview() -- debounced file review
  |     +-- reviewFile() -- sends to LM, applies results
  |     +-- isGitProject() -- walks directory tree for .git
  |
  +-- CopilotReviewStartup (ProjectActivity)
  |     |
  |     +-- Listens to VFS_CHANGES for file saves
  |     +-- Routes to scheduleReview() based on scope setting
  |
  +-- ReviewAnnotator
  |     |
  |     +-- applyAnnotations() -- wave underlines with severity colors
  |     +-- clearAnnotations() / clearAllAnnotations()
  |     +-- Stale detection: skips if line count changed since request
  |
  +-- ReviewToolWindowPanel
        |
        +-- JCEF browser (primary) or JEditorPane (fallback)
        +-- HTML panel with severity badges and card-style issues
```

#### Authentication and Proxy

The `CopilotHttpClient` handles authentication by reading the OAuth token from the installed GitHub Copilot plugin via `com.github.copilot.AuthHelper.getAccounts()`. It exchanges this for a short-lived session token from `api.github.com/copilot_internal/v2/token`, which is cached until near expiry (refreshed 5 minutes before expiration).

All HTTP connections go through `HttpConfigurable.getInstance().openHttpConnection()`, which automatically respects the IDE's proxy settings (Settings > Appearance & Behavior > System Settings > HTTP Proxy).

### Prerequisites

- IntelliJ IDEA 2024.2+ (Community or Ultimate edition)
- GitHub Copilot plugin installed and signed in
- The [intellij-lm-api](https://github.com/mystictraveler/intellij-lm-api) plugin installed (provides the `com.intellij.lm` extension point)
- Project must be a Git repository

### Actions

Available via Find Action (Cmd+Shift+A / Ctrl+Shift+A) or the editor right-click context menu:

| Action | Description |
|---|---|
| Copilot: Review Current File | Manually trigger a review on the current file |
| Copilot: Toggle Auto-Review on Save | Enable or disable automatic reviews |
| Copilot: Clear Review Diagnostics | Remove all review annotations from all editors |

### Settings

Available under **Settings > Tools > Copilot Code Review**:

| Setting | Default | Description |
|---|---|---|
| Enable auto-review on save | `true` | Toggle automatic reviews on file save |
| Debounce delay (ms) | `2000` | Delay after save before review triggers |
| Excluded extensions | `txt` | Comma-separated file extensions to skip (e.g., `txt,md,json`) |
| Review scope on save | `file` | `file` reviews only the saved file; `project` reviews all open files |

Settings are persisted per-project in `.idea/copilotCodeReview.xml`.

### Build

Requires Java 17+. The IntelliJ plugin depends on [intellij-lm-api](https://github.com/mystictraveler/intellij-lm-api), which must be built first:

```bash
# Build the LM API dependency
git clone https://github.com/mystictraveler/intellij-lm-api.git ../intellij-lm-api
cd ../intellij-lm-api
./gradlew buildPlugin

# Build the plugin
cd intellij
./gradlew buildPlugin
```

The plugin zip will be at `intellij/build/distributions/copilot-code-review-0.0.1.zip`.

To install from the zip: **Settings > Plugins > gear icon > Install Plugin from Disk...** and select the zip file.

#### Development

```bash
cd intellij
./gradlew runIde
```

This launches a sandboxed IntelliJ instance with the plugin loaded.

## CI / CD

Both plugins have GitHub Actions workflows that build on push and pull request:

- **`.github/workflows/build-vscode.yml`** -- installs Node.js 20, runs `npm ci`, compiles TypeScript, packages a `.vsix`, and uploads it as an artifact
- **`.github/workflows/build-intellij.yml`** -- sets up Java 17 (Temurin), clones and builds the `intellij-lm-api` dependency, runs `./gradlew buildPlugin`, and uploads the plugin zip as an artifact

Both workflows are scoped to only trigger when files in their respective directories change.

## Related Projects

- **[intellij-lm-api](https://github.com/mystictraveler/intellij-lm-api)** -- provides the `com.intellij.lm` Language Model API extension point for IntelliJ. This is a required dependency for the IntelliJ plugin.
- **[intellij-lm-copilot](https://github.com/mystictraveler/intellij-lm-copilot)** -- a standalone Copilot LM provider plugin for IntelliJ. The code review plugin includes its own built-in provider (`CopilotLmProvider`), but this standalone version can be used independently by other plugins that need Copilot access through the LM API.

## License

See individual plugin directories for license information.
