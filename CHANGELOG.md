# CHANGELOG

Deepseek Harness For IDE 版本历史。版本号自更名后重新起算（0.1.1 起）。

## 0.1.15

- 修复编辑器右键动作按 API 最近会话猜测目标，导致代码误投到旧对话的问题；
- 选区或当前行现在附加到当前激活对话的输入框，不再立即发送，用户可以补充问题后自行提交；
- 新建空白会话也由当前网页状态接收代码，页面加载期间会暂存并在输入框就绪后写入；
- `@` 引用候选优先显示 JetBrains 已打开的编辑器标签，当前激活文件排在第一位；
- 文件代码引用行号修正为 IDE 中显示的 1 基行号。

## 0.1.14

- 内置 DeepSeek Harness 从 `0.1.1-rc.1` 升级到官方 `0.1.1-rc.2`；
- 获得新版 DSH 的图像请求管线：优先使用 Files API 上传并复用图片，
  按模型要求自动缩放和转换格式，Files API 解析失败时回退到内联图片；
- 保持现有 DSH 启动、工作区自动选择、空白会话复用与 `/api` 响应兼容逻辑。

## 0.1.13

- 修复：启动服务时自动弹出外部浏览器网页（新版 `dsh web` 默认拉起系统浏览器，
  插件未传 `--no-open`）。现在按运行时能力决定：内置运行时直接传
  `--no-open`，外部 dsh 用一次性的 `dsh web --help` 探测（带缓存，探测失败时
  保守不传，保证旧版 dsh 仍可启动）；
- 修复：新版 DSH 启动器把 `--patch` 之后出现在 `--host` 后的补丁误当作
  web 应用参数，导致组合层原生文件跳转每次先失败一次（`too many arguments`）。
  补丁参数现在紧跟 `web` 子命令，新旧版启动器均能正确收集；
- 修复：侧边栏无法选择工作区——隔离数据目录继承了主目录 `settings.yaml` 中
  引用的 Agent 预设（如锚定 persona），但预设本体未同步，`session.create`
  全部报 `agent-preset-not-found`。现在启动时自动单向同步
  `~/.dsh/.agent-presets`（幂等、失败仅记日志），手动「同步预设」按钮保留；
- 增强：工作区自动选中兼容两种 DSH 策略——旧版按列表首位、新版按最近活跃。
  除原有的「移到列表首位」外，必要时会在目标工作区预置一个空白会话
  （新版 UI 会直接复用该会话），保证 IDE 项目工作区每次启动都被选中；
- 增强：DSH `/api` 响应解析从形状敏感的 JSON 正则改为最小 JSON 解析树 +
  任意深度字段查找，响应嵌套/信封变化（如 `workspace.create` 增加
  `workspace` 包装、`session.list` 增加 `projections`）不再需要插件更新；
- 配套单元测试：JSON 解析器、命令行 token 布局、工作区最近活跃策略。

## 0.1.12

- 内置 DeepSeek Harness 从 `0.1.0-rc.6` 升级到官方 `0.1.1-rc.1`；
- 构建脚本固定并校验 DSH 运行时版本，拒绝把本机 npx 缓存中的旧版本误打进安装包；
- 保持 JetBrains IDE 兼容范围为 2024.3–2026.2（build 243–262.*）。

## 0.1.11

- 移除全部 `PluginManager` 内部 API 调用：插件版本统一从构建时生成的
  元数据读取，安装目录通过公开 `PluginAwareClassLoader` 接口获取；
- 为 `com.intellij.modules.jcef` 可选依赖补齐 `config-file` 及独立描述文件，
  保留 2026.2 拆分 JCEF 模块后的类加载器依赖，同时不影响没有该模块的
  2024.3–2026.1 IDE；
- 消除 JetBrains Marketplace 插件验证器报告的所有内部 API 用法和
  1 个插件配置缺陷。

## 0.1.10

- 修复 Node.js 已安装、终端中 `node -v` 正常，但插件因 IDE 启动时 PATH 过期而误报
  “未检测到 Node.js”的问题：Windows 下实时读取当前用户/系统环境 PATH；
- Node 检测改为实际执行 `node --version` 并校验 18+，找到的 Node 目录会注入 DSH
  子进程 PATH；版本过低时给出准确提示，不再误报为未安装；
- 合入 PR #2：修复 Windows 用户名含单引号时生成的 `patch.yml` 无法解析，并增加
  PyCharm 2026.2（262）可选 JCEF 模块兼容；同步修正 Gradle 的 `untilBuild`，确保
  最终安装包不会被构建过程改回 261.*。

## 0.1.9

- 插件同步：DSH 设置页「For IDE」栏目新增「同步插件 / Sync plugins」按钮；
- 点击后把主 DSH 数据目录（`~/.dsh`）`profiles/web` 下的插件清单
  （`package.json`、`cordis.patch.yml`、`pnpm-workspace.yaml`、`pnpm-lock.yaml`）
  单向复制到当前项目的隔离数据目录，并运行 `pnpm install` 拉齐依赖；
- 安全：覆盖前备份，安装失败自动回滚；运行中的服务会先停后启；pnpm 缺失时
  弹窗一键引导安装（https://pnpm.io/installation）；
- 同步预设：「同步预设 / Sync presets」把 `~/.dsh/.agent-presets` 的本地预设
  复制到当前项目，预设发现是实时重读的，**无需重启**；
- 恢复默认插件：「恢复默认插件 / Reset plugins」清理之前同步的插件 profile
  （原子重命名备份），下次启动自动回到出厂默认；重启失败自动还原旧配置；
- 体验：同步/重置期间状态卡显示「正在同步插件/正在恢复默认插件」温馨提示，
  并禁用启停/重启按钮，避免用户误判为崩溃而手动干预。

## 0.1.8

- 全新插件图标：插件图标（`pluginIcon.svg`）与工具窗口图标（`dshToolWindow.svg`）更新为
  DeepSeek 风格新设计，README 图标（`plugin-icon.png`）同步更新。

## 0.1.7

- 兼容性修复：替换已废弃的 `ActionToolbar.updateActionsImmediately()`，改用平台推荐的
  `updateActionsAsync()`（自 2024.1 起废弃）；插件验证器（1.409）在 2024.3.5 / 2024.3.7.1 /
  2025.1.7.2 / 2025.2.6.3 全矩阵下全部判定 **Compatible**，不再报告废弃 API 使用。

## 0.1.6

- 「For IDE」栏目的反馈入口从文字链接升级为**真正的按钮**（DSH primitives Button），
  点击经 `host.openPath` 在**系统浏览器**中打开 GitHub Issues；
- 插件端新增 URL 处理：`http/https` 路径不再按文件处理，直接调用系统浏览器打开。

## 0.1.5

- DSH 设置页新增「For IDE」栏目：插件信息（版本/构建日期）与反馈链接，经组合层客户端包
  （`dsh-ide-settings`）注入 Web 界面设置页；
- 修复「反馈」按钮地址：此前版本构建早于地址修改，仍指向旧地址；现指向 GitHub Issues。

## 0.1.4

- 设置页新增「插件信息」区（插件版本、构建日期、内置 DSH 版本、数据目录）；
- 新增「反馈 BUG / 问题」按钮与「复制诊断信息」按钮；侧边栏工具栏新增「反馈」入口；
- 更新公告机制：环境检查完成后弹出，含版本号、构建日期与更新内容，每版本仅一次。

## 0.1.3

- 不再内置 Node.js（安装包体积回到 52MB；可选 `-PskipNodeRuntime=false` 重新内置）；
- 启动环境检查：未检测到 Node.js 时状态卡提示 + 弹窗一键跳转 nodejs.org 下载；
- 新增每版本一次的更新公告。

## 0.1.2

- 内置 Node.js 运行时：不再要求本机安装 Node.js（PATH 中的 node 优先，否则使用内置
  可执行文件；后于 0.1.3 默认关闭）。

## 0.1.1

- 更名后的首个版本（全新版本号起点）：插件更名 **Deepseek Harness For IDE**；
- 继承自更名前的能力：内置 DSH 运行时（免装 dsh）、按项目隔离数据目录并单向继承凭据、
  工作区默认锁定 IDE 项目（重开确定性落在会话最多的工作区）、文件跳转到 IDE
  （DSH 组合层原生网关 + TCP 代理回退）、IDE 原生 Diff（VCS 基线）、编辑器选区直发、
  启动即检测 API Key。

## 更名前（历史版本号 0.1.0 – 0.4.2）

旧名称下的开发历史（含组合层原生网关、TCP 代理、h2c/工作区/事件流等事故修复）不再单列，
详见仓库提交历史。
