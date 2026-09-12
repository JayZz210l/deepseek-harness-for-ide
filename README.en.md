<div align="center">

# Deepseek Harness For IDE

> DeepSeek Harness inside your JetBrains IDE

<img width="120" alt="Deepseek Harness For IDE" src="./docs/images/plugin-icon.png" />

**English** · [简体中文](./README.md)

[![JetBrains Marketplace](https://img.shields.io/jetbrains/plugin/v/33555-deepseek-harness-for-ide?label=JetBrains%20Marketplace&logo=jetbrains)](https://plugins.jetbrains.com/plugin/33555-deepseek-harness-for-ide)
![][github-stars-shield] ![][github-issues-shield] ![][github-mit]

</div>

Deepseek Harness For IDE embeds DeepSeek Harness chat, sessions, approvals, goals and plans,
subagents, workflows, and Cordis panels in JetBrains IDEs. File navigation, code diffs,
editor selections, and project workspaces are integrated with native IDE capabilities.

<img width="850" alt="Deepseek Harness For IDE running inside the IDE" src="./docs/images/DSH-FOR-IDE.png" />

> Current release: **plugin 0.1.20** · **bundled DeepSeek Harness v0.1.5-rc.2** · Windows x64

---

## Installation

### JetBrains Marketplace (recommended)

Search for **Deepseek Harness For IDE** under **Settings → Plugins → Marketplace**, or install
it from the [plugin page](https://plugins.jetbrains.com/plugin/33555-deepseek-harness-for-ide).
Restart the IDE when prompted.

### Install a local ZIP

1. Download `deepseek-harness-jetbrains-<version>.zip` from
   [GitHub Releases](https://github.com/JayZz210l/deepseek-harness-for-ide/releases),
   or create it using [Building from source](#building-from-source).
2. Open **Settings → Plugins**, open the gear menu, and choose **Install Plugin from Disk…**.
3. Select the ZIP without extracting it, then restart the IDE.

The **Deepseek Harness For IDE** tool window starts a project-specific service when a project
opens. Upgrading the plugin retains sessions and settings stored in project-isolated homes.

## Requirements

- JetBrains IDE **2024.3–2026.2** (`since-build 243` / `until-build 262.*`).
- **Node.js 22.19.x or 24+**. DSH is bundled; Node.js is not bundled by default.
- A DeepSeek API key. Configure it under **Settings → Models** in the embedded UI. Existing
  credentials and base settings can also be inherited one-way from `~/.dsh`.

The bundled DSH contains native dependencies such as `node-pty` and `sharp`; the current
distribution targets **Windows x64**. The default `dsh` command resolves the bundled,
compatibility-tested version first. An external DSH is used only when an explicit path or
command is configured in the IDE settings.

## Implemented features

### Complete DSH interface

- DSH Web runs inside a JCEF tool window with chat, session management, approvals, goals,
  plans, subagents, workflows, plugins, and agent presets.
- The plugin pins **DSH v0.1.5-rc.2** and verifies the runtime version while building so an
  older npm cache cannot be packaged accidentally.
- `--no-open` is used when supported, preventing DSH startup from opening a separate browser.
- The toolbar provides start, stop, restart, reset plugins, install DSH plugin, feedback,
  and details actions.

### Native IDE file experience

- Read, Write, and Edit filenames, changed files, delivery cards, answer file references,
  and the Files sidebar tree open in the IDE instead of DSH 0.1.5's right document preview.
- Read links carrying a line number navigate to that IDE line; project directories are
  revealed in the Project view.
- Edit rows send DSH's authoritative before/after fragments directly to the native IDE diff,
  without depending on Git refresh timing.
- Both diff sides retain the target file type, enabling syntax highlighting for C#, Java,
  Kotlin, and other languages recognized by the IDE.
- In `auto` mode, ordinary modified files open against the VCS baseline; unchanged files open
  in the editor.
- Files changed by the agent outside the IDE are refreshed while unsaved user edits remain protected.

### Editor and session integration

- An editor context-menu action appends the selection or current line to the visible DSH
  composer, where the user can add a question before sending.
- The `@` menu prioritizes the active file and other open IDE tabs using a snapshot that
  survives page reconnects.
- The IDE project is registered as the DSH workspace and its history is restored when reopened.

### Isolation, plugins, and presets

- Every project gets an isolated DSH home under
  `%LOCALAPPDATA%\deepseek-harness-jetbrains\dsh-home\<project>-<hash>` by default.
- Credentials and base settings are inherited one-way from `~/.dsh`; the plugin does not
  concurrently write the home used by an external `dsh web`.
- **Settings → For IDE** provides agent-preset sync, DSH-plugin sync, and reset-to-default.
  Plugin sync includes compatibility filtering, startup validation, rollback, and automatic restart.
- The toolbar accepts an npm package, Git spec, or restricted
  `dsh plugin --profile web add <package>` command without requiring a global DSH CLI.
- The redesigned For IDE page displays the plugin version, bundled DSH version, and build date.

### Diagnostics

- Startup checks cover the API key and the Node.js executable/version. On Windows, user and
  system PATH values are refreshed before resolution.
- The details panel records service logs, starts, unexpected exits, and uptime.
- A once-per-version update notice and copyable plugin/DSH/IDE/OS diagnostics simplify bug reports.

## Common tasks

| Action | Location |
| --- | --- |
| Chat, approve tools, manage sessions | Embedded DSH interface |
| Open a Read / Write target | Click its filename → IDE editor at the requested line |
| Inspect an Edit | Click its filename → syntax-highlighted native IDE diff |
| Add editor code to chat | Select code → right-click **Add Selection to DeepSeek Harness Chat** |
| Install a DSH plugin | Plugin-install action in the tool-window toolbar |
| Sync plugins or agent presets | DSH **Settings → For IDE** |
| Restore project defaults | Toolbar reset action, or **Settings → For IDE → Reset** |
| View logs and statistics | Toolbar **Show Details** |
| Report a problem | Toolbar feedback action, or **Settings → For IDE → Report a problem** |

## IDE settings

Open **Settings → Tools → Deepseek Harness For IDE**.

| Setting | Default | Description |
| --- | --- | --- |
| dsh command | `dsh` | Prefers the bundled runtime; enter a full path or explicit command to use an external DSH |
| Bind address | `127.0.0.1` | Passed to `dsh web --host`; loopback is the safest choice |
| Port | `0` | Lets the OS choose a free port and avoids multi-project conflicts |
| File jump | `auto` | Composition-native gateway with TCP-proxy fallback; `proxy` and `off` are also available |
| File open mode | `auto` | Modified files open as IDE diffs, others in the editor; `file` always opens the editor |
| DSH_HOME override | blank | Blank isolates by project; `default` shares `~/.dsh`; an absolute path selects another home |
| Auto start on project open | on | Starts one independent instance for each project |
| Auto restart after unexpected exit | off | Restarts DSH when the process crashes |

> Sharing one DSH home between multiple `dsh web` processes is not recommended. The isolated
> default prevents concurrent writes from damaging sessions or configuration.

## Current limitations

- Session-log export added in DSH v0.1.5-rc.2 relies on a browser download manager. The plugin
  has not yet registered a ZIP download handler for JCEF, so the UI may report that a download
  started without prompting for a destination.
- The bundled runtime distribution currently targets Windows x64 only.

The project is under active development. Completed changes and compatibility fixes are tracked in
[CHANGELOG.md](CHANGELOG.md).

## Building from source

JDK 21+ is required. Populate the fixed DSH npm cache before the first build:

```powershell
npx --yes @deepseek-ai/dsh@0.1.5-rc.2 --version
.\gradlew.bat buildPlugin      # build/distributions/deepseek-harness-jetbrains-0.1.20.zip
.\gradlew.bat runIde           # launch a sandbox IDE with the plugin
.\gradlew.bat verifyPlugin     # verify supported IntelliJ Platform releases
```

| Property | Effect |
| --- | --- |
| `-PdshRuntimePath=<dir>` | Use a DSH installation whose directory contains `node_modules` |
| `-PskipDshRuntime=true` | Build a lightweight distribution without bundled DSH |
| `-PskipNodeRuntime=false` | Bundle Node.js too; `-PnodeRuntimePath=<dir>` selects its source |

## Architecture

The plugin combines a Kotlin process host, JCEF UI, project-local HTTP/TCP proxy,
JavaScript-to-IDE bridge, and a pinned DSH Web runtime. See
[docs/architecture.md](docs/architecture.md) for the design and compatibility details.

## Feedback

- Use the feedback action in the tool window or open
  [GitHub Issues](https://github.com/JayZz210l/deepseek-harness-for-ide/issues).
- **Copy diagnostics** in the IDE settings copies the plugin version, build date, bundled DSH
  version, data directory, IDE, and OS for pasting into a report.

## License

MIT — see [LICENSE](LICENSE). The plugin bundles the MIT-licensed
[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) runtime;
the harness and its trademarks belong to their respective owners.

<!-- LINK GROUP -->

[github-stars-shield]: https://img.shields.io/github/stars/JayZz210l/deepseek-harness-for-ide?color=4D6BFE&labelColor=black&style=flat-square
[github-issues-shield]: https://img.shields.io/github/issues/JayZz210l/deepseek-harness-for-ide?color=ff80eb&labelColor=black&style=flat-square
[github-mit]: https://img.shields.io/badge/github-MIT-4D6BFE?logo=github
