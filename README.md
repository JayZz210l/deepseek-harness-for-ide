<div align="center">

# Deepseek Harness For IDE

> 在 JetBrains IDE 中使用完整的 DeepSeek Harness

<img width="120" alt="Deepseek Harness For IDE 图标" src="./docs/images/plugin-icon.png" />

[**English**](./README.en.md) · **简体中文**

[![JetBrains Marketplace](https://img.shields.io/jetbrains/plugin/v/33555-deepseek-harness-for-ide?label=JetBrains%20Marketplace&logo=jetbrains)](https://plugins.jetbrains.com/plugin/33555-deepseek-harness-for-ide)
![][github-stars-shield] ![][github-issues-shield] ![][github-mit]

</div>

Deepseek Harness For IDE 将 DeepSeek Harness 的对话、会话、工具审批、目标与计划、
子智能体、Workflow 和 Cordis 工具面板嵌入 JetBrains IDE，并把文件打开、代码 Diff、
编辑器选区与项目工作区交给 IDE 原生能力处理。

<img width="850" alt="Deepseek Harness For IDE 运行截图" src="./docs/images/DSH-FOR-IDE.png" />

> 当前版本：**插件 0.1.20** · **内置 DeepSeek Harness v0.1.5-rc.2** · Windows x64

---

## 安装

### JetBrains Marketplace（推荐）

在 IDE 的 **Settings → Plugins → Marketplace** 中搜索 **Deepseek Harness For IDE**，
或打开[插件商店页面](https://plugins.jetbrains.com/plugin/33555-deepseek-harness-for-ide)安装。
安装完成后按提示重启 IDE。

### 从本地 ZIP 安装

1. 从 [GitHub Releases](https://github.com/JayZz210l/deepseek-harness-for-ide/releases)
   下载 `deepseek-harness-jetbrains-<version>.zip`，或按[本地构建](#本地构建)生成安装包；
2. 打开 **Settings → Plugins**，点击齿轮菜单并选择 **Install Plugin from Disk…**；
3. 直接选择 ZIP（无需解压），按提示重启 IDE。

打开项目后，右侧的 **Deepseek Harness For IDE** 工具窗口会启动项目专属服务。
升级插件不会清除项目隔离目录中的会话与设置。

## 环境要求

- JetBrains IDE **2024.3–2026.2**（`since-build 243` / `until-build 262.*`）；
- **Node.js 22.19.x 或 24+**；插件内置 DSH，但不默认内置 Node.js；
- DeepSeek API Key：可直接在内嵌界面的 **设置 → 模型** 中配置；已有 `~/.dsh`
  配置时，项目隔离环境会单向继承凭据与基础设置。

内置 DSH 包含 `node-pty`、`sharp` 等原生依赖，当前安装包面向 **Windows x64**。
默认命令 `dsh` 优先解析插件内置、经过兼容验证的固定版本；只有在 IDE 设置中明确填写
外部路径或命令时，才会使用外部 DSH。

## 已实现功能

### 完整 DSH 界面

- 在 JCEF 工具窗口中运行 DSH Web，支持对话、会话管理、工具审批、目标、计划、
  子智能体、Workflow、插件与 Agent 预设；
- 固定内置 **DSH v0.1.5-rc.2**，构建时校验实际运行时版本，避免误打包旧缓存；
- 按运行时能力使用 `--no-open`，启动服务时不会额外弹出系统浏览器；
- 工具栏提供启动、停止、重启、恢复默认插件、安装 DSH 插件、反馈和详情入口。

### IDE 原生文件体验

- DSH 的 Read、Write、Edit 文件名，变更文件、交付卡片、回答内文件引用和文件侧栏树，
  均优先在 IDE 中打开，不使用 DSH 0.1.5 的右侧文档预览器；
- Read 等带行号入口会定位到 IDE 中对应行；目录会定位到 Project 视图；
- Edit 工具行直接使用 DSH 携带的修改前后内容打开 IDE 原生 Diff，不依赖 Git 时序；
- Diff 两侧继承目标文件的语言类型，支持 C#、Java、Kotlin 等 IDE 已识别语言的语法高亮；
- 普通文件在 `auto` 模式下优先显示 VCS 基线 Diff，无改动时直接进入编辑器；
- AI 从进程外修改已打开文件后，插件会刷新磁盘内容，同时保护未保存的用户编辑。

### 编辑器与会话联动

- 选中代码或当前行后，通过编辑器右键菜单附加到当前可见 DSH 对话，补充问题后再发送；
- `@` 菜单优先展示当前文件和 IDE 已打开标签，并维护可跨页面重连的编辑器快照；
- IDE 项目自动注册为 DSH 工作区，重开项目时恢复对应工作区与历史会话。

### 项目隔离、插件与预设

- 每个项目默认使用独立 DSH home：
  `%LOCALAPPDATA%\deepseek-harness-jetbrains\dsh-home\<项目>-<hash>`；
- 从主 `~/.dsh` 单向继承凭据和基础设置，不与外部 `dsh web` 并发写同一目录；
- **设置 → For IDE** 提供同步 Agent 预设、同步 DSH 插件和恢复默认插件；插件同步经过
  兼容过滤、启动验证与失败回滚，完成后自动重启项目服务；
- 工具栏可直接安装 npm 包、Git 地址或受限的
  `dsh plugin --profile web add <包名>` 命令，无需全局安装 DSH CLI；
- 重新设计的 For IDE 页面显示插件版本、内置 DSH 版本和构建日期。

### 运行诊断

- 启动前检查 API Key、Node.js 路径及版本；Windows 下会重新读取用户/系统 PATH；
- 记录服务日志、启动次数、异常退出和运行时长；
- 每个版本显示一次更新公告；反馈入口支持复制插件、DSH、IDE、系统和数据目录诊断信息。

## 常用操作

| 操作 | 位置 |
| --- | --- |
| 对话、审批工具、管理会话 | 内嵌 DSH 界面 |
| 打开 Read / Write 文件 | 点击工具行文件名 → IDE 编辑器并定位行号 |
| 查看 Edit 修改 | 点击 Edit 文件名 → 带语言高亮的 IDE 原生 Diff |
| 附加编辑器代码 | 选中代码 → 右键 **附加选区到 DeepSeek Harness 对话** |
| 安装 DSH 插件 | 工具窗口顶部的插件安装按钮 |
| 同步插件或 Agent 预设 | DSH **设置 → For IDE** |
| 恢复项目默认插件 | 工具栏恢复按钮，或 **设置 → For IDE → 恢复默认** |
| 查看日志和统计 | 工具栏 **Show Details** |
| 反馈问题 | 工具栏反馈按钮，或 **设置 → For IDE → 反馈问题** |

## IDE 设置

路径：**Settings → Tools → Deepseek Harness For IDE**。

| 设置项 | 默认值 | 说明 |
| --- | --- | --- |
| dsh 命令 | `dsh` | 默认优先内置运行时；填写完整路径或明确命令可改用外部 DSH |
| 绑定地址 | `127.0.0.1` | 传给 `dsh web --host`，保持回环地址最安全 |
| 端口 | `0` | 自动分配空闲端口，避免多项目冲突 |
| 文件跳转方式 | `auto` | 原生组合层网关优先，TCP 代理回退；也可选 `proxy` 或 `off` |
| 文件打开方式 | `auto` | 修改文件显示 IDE Diff，否则打开编辑器；`file` 始终打开编辑器 |
| DSH_HOME 覆盖 | 空 | 空表示按项目隔离；`default` 共用 `~/.dsh`；也可填写绝对路径 |
| 打开项目时自动启动 | 开 | 每个项目启动独立实例 |
| 意外退出后自动重启 | 关 | DSH 进程异常退出后自动重新启动 |

> 不建议多个 `dsh web` 实例共用一个 DSH home。默认隔离模式可以避免会话和配置被并发写坏。

## 当前限制

- DSH v0.1.5-rc.2 新增的 Session 日志导出使用浏览器下载管理器；当前插件尚未为 JCEF
  注册 ZIP 下载处理器，因此界面可能显示“已开始下载”但不弹出保存位置；
- 当前内置运行时安装包仅面向 Windows x64。

项目正在活跃开发。已完成改动和兼容性修复见 [CHANGELOG.md](CHANGELOG.md)。

## 本地构建

需要 JDK 21+。首次构建前先准备固定版本的 DSH npm 缓存：

```powershell
npx --yes @deepseek-ai/dsh@0.1.5-rc.2 --version
.\gradlew.bat buildPlugin      # build/distributions/deepseek-harness-jetbrains-0.1.20.zip
.\gradlew.bat runIde           # 启动带插件的沙箱 IDE
.\gradlew.bat verifyPlugin     # 验证支持的 IntelliJ Platform 版本
```

| 构建参数 | 作用 |
| --- | --- |
| `-PdshRuntimePath=<目录>` | 指定包含 `node_modules` 的 DSH 安装目录 |
| `-PskipDshRuntime=true` | 构建不带内置 DSH 的轻量安装包 |
| `-PskipNodeRuntime=false` | 额外打包 Node.js，可用 `-PnodeRuntimePath=<目录>` 指定来源 |

## 架构

插件由 Kotlin 进程托管层、JCEF 内嵌界面、项目级 HTTP/TCP 代理、JavaScript→IDE 原生桥接
以及固定版本的 DSH Web 运行时组成。详细设计与兼容性说明见
[docs/architecture.md](docs/architecture.md)。

## 反馈

- 在工具窗口点击反馈按钮，或前往
  [GitHub Issues](https://github.com/JayZz210l/deepseek-harness-for-ide/issues)；
- IDE 设置页的 **复制诊断信息** 会复制插件版本、构建日期、内置 DSH 版本、数据目录、
  IDE 与操作系统信息，提交问题时可直接粘贴。

## License

MIT，见 [LICENSE](LICENSE)。插件内置
[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 运行时（MIT）；
Harness 及其商标归各自所有者。

<!-- LINK GROUP -->

[github-stars-shield]: https://img.shields.io/github/stars/JayZz210l/deepseek-harness-for-ide?color=4D6BFE&labelColor=black&style=flat-square
[github-issues-shield]: https://img.shields.io/github/issues/JayZz210l/deepseek-harness-for-ide?color=ff80eb&labelColor=black&style=flat-square
[github-mit]: https://img.shields.io/badge/github-MIT-4D6BFE?logo=github
