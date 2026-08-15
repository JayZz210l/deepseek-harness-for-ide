<div align="center">

# Deepseek Harness For IDE

> 把完整的 DeepSeek Harness 搬进你的 JetBrains IDE

<img width="120" alt="Deepseek Harness For IDE 图标" src="./docs/images/plugin-icon.png" />

[**English**](./README.md) · **简体中文**

![][github-stars-shield] ![][github-forks-shield] ![][github-issues-shield] ![][github-mit]

</div>

> DeepSeek Harness 是 DeepSeek 的智能体编程工作台——智能体对话、工具审批、目标与计划、
> 子智能体、Workflow 与 Cordis 工具链。本插件把**完整**的 Harness 内嵌到 IDE 工具窗口：
> 装上插件、配置一次 API Key，即可直接在 IDE 里对话。

<img width="850" alt="Deepseek Harness For IDE 运行截图" src="./docs/images/DSH-FOR-IDE.png" />

---

## 安装

### 从本地 zip 安装（硬盘安装）

1. 从 [Releases](https://github.com/JayZz210l/deepseek-harness-for-ide/releases) 页面下载
   `deepseek-harness-jetbrains-<version>.zip`（或自行构建，见[本地构建](#本地构建)）；
2. 打开 IDE：**Settings → Plugins**（Ctrl+Alt+S，或 File → Settings）；
3. 点击 **Marketplace** 旁的 **⚙ 齿轮**，选择 **Install Plugin from Disk…**；
4. 选中下载的 zip，点 **OK**；
5. 按提示**重启 IDE**；
6. 打开任意项目——右侧出现 **Deepseek Harness For IDE** 工具窗口并自动启动本地服务。

**升级**：用同样方式安装新版 zip 即可覆盖升级，各项目的会话与设置保持不变。

### 从 JetBrains Marketplace 安装

在 **Settings → Plugins → Marketplace** 搜索 **Deepseek Harness For IDE** 安装即可，
版本号与 GitHub Releases 一致。

---

## 核心特性

### 完整 Harness，原生内嵌
- **内嵌完整 DSH Web 界面**——智能体对话、会话管理、工具审批、文件 Diff、目标与计划、
  子智能体、Workflow、Cordis 工具面板，全部跑在工具窗口的 JCEF 浏览器里；DSH 升级自动
  获得新界面能力；
- **免装 dsh**——运行时（整个 `node_modules` 依赖闭包）内置在插件里。仅要求 Node.js 18+，
  缺失时插件会检测并弹窗一键跳转 nodejs.org 下载。

### IDE 深度集成
- **工作区 = IDE 项目**——页面加载前就把项目目录登记为工作区并置顶；重开工程时确定性
  落在会话最多的那个工作区，历史对话不散落；
- **文件跳转到 IDE**——界面里的"打开文件"直接落到 IDE 编辑器（DSH 组合层原生网关实现，
  启动失败自动回退 TCP 代理）；
- **IDE 原生 Diff**——文件有 VCS 改动时，打开的是 IDE 并排 Diff（对比 VCS 基线），
  而不是普通编辑器；
- **编辑器选区直发**——右键把选中代码（或当前行）作为消息发给 DSH，自动启动服务、
  定位会话并唤起侧边栏。

### 数据安全与隔离
- **每项目隔离数据**——每个项目独立 DSH home，启动时从 `~/.dsh` 单向继承凭据与设置，
  绝不触碰外部浏览器中运行的 `dsh web`（DSH 当前版本多实例共用 home 不安全）；
- **启动自检**——API Key 预检（缺失弹窗引导）、Node.js 检测与下载引导、每版本一次的
  更新公告（含更新日期与内容）；
- **一键同步插件与预设**——在终端用 `dsh plugin --profile web add <包名>` 安装到
  `~/.dsh` 的插件，以及 `~/.dsh/.agent-presets` 下自建的 Agent 预设，可在内嵌界面
  设置 → **For IDE** → **同步插件 / 同步预设** 一键复制到当前 IDE 项目的隔离数据目录；
  插件同步会自动重启服务（失败自动回滚），预设同步无需重启；**恢复默认插件** 可一键
  清空之前同步的插件，回到出厂默认配置。

### 开发者体验
- **DSH 设置页内的「For IDE」栏目**——插件信息与反馈链接，经组合层客户端包注入
  （真正的 DSH 客户端插件机制）；
- **内置反馈入口**——侧边栏工具栏"反馈"按钮 + 设置页"插件信息"区（一键复制诊断信息）；
- **日志与统计**——工具窗口底部 Log / Statistics 标签页（启动次数、异常退出、运行时长）；
- **中英双语**——界面文案完整支持英文与简体中文。

---

## 环境要求

- JetBrains IDE **2024.3+**（`since 243 / until 261.*`，已实测 IntelliJ IDEA 2024.3 与
  JetBrains Rider 2026.1）；
- **Node.js 18+**（DSH 运行时已内置，Node 未内置——缺失时插件会引导下载）；
- **DeepSeek API Key**：首次在终端跑一次 `npx @deepseek-ai/dsh web`，在 Models 页面保存
  `DEEPSEEK_API_KEY`，插件启动时自动继承。

> **平台说明**：内置 DSH 运行时含原生模块（node-pty、sharp 等），当前构建面向
> **Windows x64**；其他平台上有 PATH 中的系统 `dsh` 时会优先使用它。

---

## 使用

| 操作 | 方式 |
| --- | --- |
| 对话 / 审批工具 / 管理会话 | 全部在内嵌的 Harness 界面中完成 |
| 打开智能体改动的文件 | 聊天中点文件 → IDE 编辑器；有 VCS 改动时打开 **IDE 原生 Diff** |
| 发送代码给 DSH | 选中代码 → 右键 **Send Selection to DeepSeek Harness** |
| 同步 `~/.dsh` 中的插件 / 预设 | 内嵌界面 设置 → **For IDE** → **同步插件 / 同步预设** |
| 恢复默认插件 | 内嵌界面 设置 → **For IDE** → **恢复默认插件** |
| 启动 / 停止 / 重启服务 | 工具窗口工具栏按钮 |
| 日志与统计 | 工具栏 **Show Details** → Log / Statistics 标签页 |
| 报告问题 | 工具栏 **反馈** 按钮，或 设置 → **Deepseek Harness For IDE** → 反馈 / 复制诊断信息 |

## 设置

`设置 → 工具 → Deepseek Harness For IDE`：

| 设置项 | 默认值 | 说明 |
| --- | --- | --- |
| dsh 命令 | `dsh` | 默认使用内置运行时；PATH 中的 dsh 优先；也可填完整路径（如 `C:\nodejs\node.exe C:\...\dsh\lib\bin.js`） |
| 绑定地址 | `127.0.0.1` | `dsh web --host`，DSH 不支持 `0.0.0.0` |
| 端口 | `0`（自动） | 0 = 系统自动分配空闲端口（推荐） |
| 文件跳转方式 | `auto` | `auto` = DSH 组合层原生网关 + TCP 代理回退；`proxy` = 仅 TCP 代理；`off` = 关闭 |
| 文件打开方式 | `auto` | `auto` = 文件有 VCS 改动时打开 IDE 原生 Diff，否则直接打开；`file` = 始终直接打开 |
| DSH_HOME 覆盖 | 空（隔离） | 空 = 按项目隔离（`%LOCALAPPDATA%\deepseek-harness-jetbrains\dsh-home\<项目>-<hash>`，推荐）；`default` = 继承 IDE 环境（与外部 `dsh web` 共用 `~/.dsh`，多实例不安全，不推荐）；绝对路径 = 指定目录 |
| 打开项目时自动启动 | ✅ | 每个项目一个独立实例 |
| 进程意外退出后自动重启 | ❌ | 开启后崩溃自动拉起 |

> ⚠️ 多个 `dsh web` 共用同一 DSH home 时并发写会话/配置可能互相破坏（DSH 当前版本
> 不保证多实例安全）。默认的按项目隔离让插件与外部实例完全独立。

---

## 项目状态

项目正在活跃开发中。版本历史与迭代进展见 [CHANGELOG.md](CHANGELOG.md)。

---

## 本地构建

前置：JDK 17+（推荐 21/22）。首次构建会下载 IntelliJ 平台依赖，并从本机 npx 缓存打包
DSH 运行时——请先跑一次 `npx @deepseek-ai/dsh` 保证有可用安装。

```powershell
.\gradlew.bat buildPlugin      # → build/distributions/deepseek-harness-jetbrains-0.1.9.zip
.\gradlew.bat runIde           # 带插件的沙箱 IDE 调试
.\gradlew.bat verifyPlugin     # 上架前的平台验证
```

构建选项：

| 参数 | 作用 |
| --- | --- |
| `-PdshRuntimePath=<目录>` | 指定要打包的 dsh 安装（含 `node_modules` 的目录） |
| `-PskipDshRuntime=true` | 跳过内置运行时，产出轻量包 |
| `-PskipNodeRuntime=false` | 重新启用内置 Node.js（`-PnodeRuntimePath=<目录>` 指定来源） |

## 架构

插件 = 进程托管层（Kotlin）+ 内嵌浏览器（JCEF）+ DSH 官方 Web 前端。
完整设计、组合层补丁逆向要点（网关重建、`ctx.provide` 谓词陷阱、h2c 升级陷阱、
「For IDE」栏目的客户端模块注入）、按项目数据目录方案见
[docs/architecture.md](docs/architecture.md)。

## 路线图

- [ ] 多项目共享一个 DSH 实例（可选模式）
- [ ] 目录选择器 IDE 化（接入 IDE 原生对话框）
- [ ] 基于 `dsh --profile headless` 的单次任务执行
- [ ] 上架 JetBrains Marketplace

---

## 反馈

- 工具窗口 → **反馈**（或 设置 → **Deepseek Harness For IDE** → **反馈 BUG / 问题**）；
- 反馈地址在
  [`DshFeedback.kt`](src/main/kotlin/com/deepseek/dsh/ide/ui/DshFeedback.kt) 的
  `FEEDBACK_URL` 常量中；
- 设置页的 **复制诊断信息** 会快照版本、构建日期、内置 DSH 版本、数据目录、IDE 与
  操作系统——反馈时直接粘贴。

---

## License

MIT —— 见 [LICENSE](LICENSE)。插件内置了
[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 运行时（MIT）；
Harness 及其商标归各自所有者。

<!-- LINK GROUP -->

[github-stars-shield]: https://img.shields.io/github/stars/JayZz210l/deepseek-harness-for-ide?color=4D6BFE&labelColor=black&style=flat-square
[github-forks-shield]: https://img.shields.io/github/forks/JayZz210l/deepseek-harness-for-ide?color=8ae8ff&labelColor=black&style=flat-square
[github-issues-shield]: https://img.shields.io/github/issues/JayZz210l/deepseek-harness-for-ide?color=ff80eb&labelColor=black&style=flat-square
[github-mit]: https://img.shields.io/badge/github-MIT-4D6BFE?logo=github
