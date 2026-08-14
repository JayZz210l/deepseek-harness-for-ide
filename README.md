# Deepseek Harness For IDE

[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) inside your JetBrains IDE.

The plugin hosts a local `dsh web` server and embeds the full Harness Web UI — agent chat,
session management, tool approvals, file diffs, goals & plans, subagents, workflows, and the
Cordis tool panels — in the IDE tool window. Install, configure the API key once, chat.

[中文说明](README.zh-CN.md) · License: [MIT](LICENSE)

## Features

- **Full DeepSeek Harness embedded** — the official React frontend in a JCEF browser, so DSH
  upgrades automatically bring new UI features.
- **No dsh install required** — the plugin ships the complete DSH runtime (its whole
  `node_modules` closure). Only Node.js 18+ is required; when it is missing the plugin
  detects it and pops a one-click link to nodejs.org.
- **Per-project isolated data** — every IDE project gets its own DSH home; credentials and
  settings are inherited one-way from `~/.dsh` at startup. An externally running `dsh web`
  is never touched (multi-instance sharing of one home is unsafe in DSH).
- **Workspace = IDE project** — the project directory is adopted as a workspace and moved to
  the top before the page loads; reopening deterministically lands on the workspace with the
  most conversations.
- **Open files in the IDE** — `host.openPath` is routed into the IDE via a DSH composition
  patch (`dsh web --patch`, native gateway) with an automatic fallback to a TCP proxy.
- **Native IDE diff** — when a file has VCS changes, opening it shows the IDE's side-by-side
  diff against the VCS baseline instead of the plain editor.
- **Send selection to DSH** — editor context-menu action that starts the service if needed,
  locates the session, and queues the selected text (or current line) as a message.
- **Startup checks & announcements** — API-key preflight (warns before the first dead turn),
  Node.js detection with download guidance, and a once-per-version update notice with the
  build date and what changed.
- **Feedback built in** — a feedback button in the tool-window toolbar, plus a plugin-info
  section with one-click diagnostics copy on the settings page.
- **Logs & statistics** — start/crash counters, uptime, and the captured `dsh web` log in the
  tool window. Fully bilingual (English / 简体中文).

## Requirements

- JetBrains IDE **2024.3+** (`since 243 / until 261.*`) — IntelliJ IDEA, PyCharm, WebStorm,
  GoLand, Rider, and other platform-based IDEs.
- **Node.js 18+** — the DSH runtime is bundled; Node is not. The plugin prompts you to
  download it when missing.
- A **DeepSeek API key** — run `npx @deepseek-ai/dsh web` once in a terminal, save
  `DEEPSEEK_API_KEY` on the Models page; the plugin inherits it automatically.

> **Platform note:** the bundled DSH runtime contains native modules (node-pty, sharp, …) and
> is currently built for **Windows x64**. On other platforms a system-installed `dsh` on PATH
> is used instead (see the settings table below).

## Installation

1. Download `deepseek-harness-jetbrains-<version>.zip` from
   [Releases](https://github.com/JayZz210l/deepseek-harness-for-ide/releases)
   (or install from the JetBrains Marketplace).
2. In the IDE: **Settings → Plugins → ⚙ → Install Plugin from Disk…** and pick the zip.
3. Open a project — the plugin starts the local service automatically and opens the
   **Deepseek Harness For IDE** tool window on the right.
4. The workspace defaults to your project directory. Just chat.

## Usage

| Action | How |
| --- | --- |
| Chat / approve tools / manage sessions | Everything happens inside the embedded Harness UI |
| Open a file changed by the agent | Click the file in the chat → opens in the IDE editor, or the **IDE native diff** when it has VCS changes |
| Send code to DSH | Select code → right-click → **Send Selection to DeepSeek Harness** |
| Start / stop / restart the service | Toolbar buttons in the tool window |
| Logs & statistics | Toolbar **Show Details** → Log / Statistics tabs |
| Report a problem | Toolbar **Feedback** button, or Settings → **Deepseek Harness For IDE** → **Report a problem / Copy diagnostics** |

## Settings

**Settings → Tools → Deepseek Harness For IDE**

| Setting | Default | Description |
| --- | --- | --- |
| dsh command | `dsh` | The bundled runtime is used automatically; a `dsh` found on PATH wins. A full path (e.g. `C:\nodejs\node.exe C:\...\dsh\lib\bin.js`) is also accepted |
| Bind address | `127.0.0.1` | Passed to `dsh web --host` (DSH rejects `0.0.0.0`) |
| Port | `0` (auto) | `0` = let the OS pick a free port (recommended) |
| File jump | `auto` | How "open file" resolves: `auto` = DSH composition-native gateway with TCP-proxy fallback; `proxy` = TCP proxy only; `off` = disabled |
| File open mode | `auto` | How the file lands: `auto` = IDE native diff (VCS baseline) when modified, else the editor; `file` = always the editor |
| DSH_HOME override | blank (isolated) | Blank = per-project isolated directory under `%LOCALAPPDATA%\deepseek-harness-jetbrains\dsh-home\<project>-<hash>` (recommended; credentials are inherited one-way from `~/.dsh`); `default` = inherit the IDE environment (shares `~/.dsh` with an external `dsh web` — not multi-instance safe); an absolute path uses that directory |
| Auto start on project open | ✅ | One DSH instance per project |
| Auto restart on unexpected exit | ❌ | Re-spawns the service when it crashes |

> ⚠️ Sharing one DSH home between several `dsh web` instances is not safe in the current DSH
> version. The isolated default (since 0.1.1) keeps the plugin and any external `dsh web`
> fully independent.

## Building from source

Prerequisites: JDK 17+ (21/22 recommended). First build downloads the IntelliJ Platform
dependencies and bundles the DSH runtime from the local npx cache — run
`npx @deepseek-ai/dsh` once so a fresh DSH installation exists.

```powershell
.\gradlew.bat buildPlugin      # → build/distributions/deepseek-harness-jetbrains-<version>.zip
.\gradlew.bat runIde           # run a sandbox IDE with the plugin
.\gradlew.bat verifyPlugin     # platform verification before publishing
```

Build options:

| Property | Effect |
| --- | --- |
| `-PdshRuntimePath=<dir>` | Use a specific dsh installation (dir containing `node_modules`) for bundling |
| `-PskipDshRuntime=true` | Build a lightweight plugin without the bundled DSH runtime |
| `-PskipNodeRuntime=false` | Opt back into bundling a Node.js executable (`-PnodeRuntimePath=<dir>` points at the install) |

## Architecture

Plugin = process host (Kotlin) + embedded browser (JCEF) + the official DSH web frontend.
See [docs/architecture.md](docs/architecture.md) for the full write-up, including the
composition-patch reverse-engineering notes (gateway rebuild, `ctx.provide` check-predicate
pitfall, the h2c upgrade trap) and the per-project data-home design.

## Roadmap

- [ ] Multi-project sharing of one DSH instance (optional mode)
- [ ] Native IDE directory picker for the UI's directory-selection seam
- [ ] One-shot tasks via `dsh --profile headless`
- [ ] Publish to the JetBrains Marketplace

## Feedback

- Tool window → **Feedback** (or Settings → **Deepseek Harness For IDE** → **Report a problem**).
- The feedback URL lives in
  [`DshFeedback.kt`](src/main/kotlin/com/deepseek/dsh/ide/ui/DshFeedback.kt) (`FEEDBACK_URL`).
- **Copy diagnostics** on the settings page snapshots version, build date, bundled DSH
  version, data directory, IDE and OS — paste it into your bug report.

## License

MIT — see [LICENSE](LICENSE). This plugin bundles the
[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) runtime (MIT); the
harness and its trademarks belong to their respective owners.
