# CHANGELOG

Deepseek Harness For IDE 版本历史。版本号自更名后重新起算（0.1.1 起）。

## 0.1.22

- 内置 DeepSeek Harness 从 `0.1.7-rc.2` 升级到官方 [`0.2.0-rc.2`](https://github.com/deepseek-ai/deepseek-harness/releases/tag/dsh-v0.2.0-rc.2)，并更新插件对新版运行时的集成。新版 DSH 提供模型选择器搜索与键盘选择，并修复切换会话后计划审阅无法打开等问题。
- 保持 IDE 原生文件打开与 Diff、编辑器选区附加、项目隔离 profile、For IDE 设置操作及插件管理入口可用。

## 0.1.21

- 内置 DeepSeek Harness 从 `0.1.5-rc.2` 升级到官方 `0.1.7-rc.2`，并适配新版前端接缝：工具行文件按钮改为 `react.useMemo` 工厂（同时新增 `settledWithCue` 守卫）；原生文件打开、聊天区打开器与「文件」侧栏接缝保持有效。构建期仍逐个接缝校验，接缝变化会让构建直接失败，而不是静默产出坏包。
- DSH 0.1.7 把用户设置从 `settings.yaml` 迁移到 profile 补丁文档（`profiles/web/cordis.patch.yml`；`settings.yaml` 只在启动时导入一次，随后改名为 `settings.yaml.imported`）。插件现在按运行时真正使用的文档写入设置，并且不再向已迁移的主目录重复投递旧版 `settings.yaml`——重复导入会把旧值写回活动配置。
- 修复「重启 IDE 后语言设置失效、每次都要重新设置语言」：
  - 设置页新增「界面语言」（自动 / 中文 / 英文）。「自动」按系统语言决定首次语言：中文系统用中文，其他系统用英文。DSH 自身只提供 `zh` / `en` 两种界面语言，没有独立的语言开关，页面语言由设置项 `locale.preference` 决定，因此插件直接维护该设置项。
  - 写入 `locale.preference` 后，内嵌页面首次打开即为正确语言，无需再手动选择；你在 DeepSeek Harness 设置里手动选择的语言会被保留，重启 IDE 后不会被覆盖（「自动」只决定首次值）。需要固定语言时，在设置页选择中文或英文，每次启动都会强制生效。
  - 修复根因：主目录设置不再覆盖隔离目录设置。`.credentials.yaml` 仍以主目录为准（API Key 集中管理），`settings.yaml` 改为「只补充缺失的段与键、绝不覆盖已有值」的合并，主目录无法再悄悄回滚你在内嵌界面里的任何选择。
- 修复「For IDE」页面操作失败提示没有颜色：`--dsw-alias-label-error` 在 0.1.5 / 0.1.7 主题中都未定义，改用已定义的 `--dsw-alias-state-error-primary` 并保留回退。
- 新增 26 个单元测试，覆盖语言设置文档读写、语言决策与 home 设置合并逻辑。
- 修复「恢复默认插件」后内嵌页面起不来、并弹出 `插件重置失败：java.nio.file.NoSuchFileException: ...\.dsh-module-fallback\node_modules\@antfu\install-pkg`：重置把 `profiles/web` 整体改名挪走，却没有先清掉旧版 link 后端写进 profile 的 `.dsh-module-fallback` 投影，其中每个 junction 都指向 `web/node_modules/<包名>`，改名后全部变成悬空链接。Node 侧 `removeLinkProjections` 遍历到悬空 junction 即抛异常、profiles 启动失败；同一个悬空 junction 也让备份目录删不掉，每次失败都会在磁盘上留下一个约 380 MB 的 profile 副本。现在重置在改名之前先清掉链接投影，删除时也不再跟随任何链接：junction 只作为单个条目删除，绝不递归进它指向的目标（目标可能是多个 profile 共用的 `profiles/node_modules/.pnpm` 存储，跟随进去会把别的 profile 一起删空），无法读取内容的条目经由 `visitFileFailed` 当叶子删掉，而不是中断整次遍历。「插件不兼容」隔离（`web.dsh-ide-incompatible-bak`）走同一套逻辑——磁盘上遗留的 4 个备份里有 3 个正是它留下的。
- 新增 5 个单元测试覆盖上述场景（含「删除投影不得删掉其指向的真实包」与「遗留悬空 junction 的备份必须能删干净」），并用变异测试确认这些用例在旧实现下确实会失败。

## 0.1.20

- 重新设计 DSH 设置中的「For IDE」页面，使用真实彩色插件图标、产品信息卡、版本徽章、分组操作卡片和明确的执行状态提示；优化窄窗口下的自适应布局。
- 新增内置 DeepSeek Harness 版本号展示，当前构建明确显示 `v0.1.5-rc.2`，并与 IDE 插件版本、构建日期并列呈现。
- 修复点击 Edit 下划线文件进入 IDE 原生 Diff 后缺少语法高亮：前后内容均继承目标文件的语言类型。

## 0.1.19

- 越过 DSH 0.1.5 新增的右侧文档预览器：对话区 Read/Write/Edit 文件名、变更文件、交付卡片预览、回答内文件引用，以及“文件”侧栏树的文件点击，均优先交给 JetBrains IDE 的编辑器/Diff/目录操作；保留 Read 起始行定位与桥接失败时的 DSH 预览回退。
- 内置 DeepSeek Harness 从 `0.1.2-rc.1` 升级到官方 `0.1.5-rc.2`，并适配新版原生文件打开与工具行 Diff 接缝；保留文件行号跳转、IDE 原生 Diff、`--no-open` 和项目隔离 profile 行为。按新版依赖要求将 Node.js 支持范围更新为 `^22.19.0 || >=24.0.0`。
- 将当前活动编辑器和已打开标签维护为 IDE 侧持久快照；`@` 菜单不再依赖打开瞬间的一次性查询，IDE 启动恢复、切换标签和页面重连时也能稳定显示当前文件。

## 0.1.18

- 默认裸命令 `dsh` 现在始终优先使用插件内置、经过兼容验证的固定版本，不再被 PATH 中偶然存在的全局 DSH 覆盖；用户仍可通过设置完整路径或明确命令主动选择外部版本。
- 修复部分 JetBrains 版本点击 Edit 时从 EDT 读取 Git revision 并报告 `GitContentRevision.getContentAsBytes() should not be called from EDT`；Git/VFS 内容改为后台加载，Diff UI 仍在 EDT 展示。
- 修复部分 DSH 前端在 Edit 完成后只回传文件路径、导致再次点击只能打开文件的问题；保留 Edit 进行时收到的精确 hunk，已完成行再次点击时继续打开对应 Diff。
- IDE 已打开文件的 `@` 候选设置明确的负数优先级，不再依赖客户端模块注册顺序。
- 移除实时事件代理的五分钟双向转发硬截止，合法的「询问用户」卡片会一直等待到用户回答、取消或连接真正结束。

## 0.1.17

- 修复 AI 从进程外写入文件后 IDE 编辑器内容不刷新的问题：不再依赖可能滞后的 VFS 时间戳，而是对已打开文件直接校验磁盘内容并强制刷新；未保存的用户编辑仍不会被覆盖。
- 完整接管 DSH 的文件跳转：内置运行时的标准桌面打开接口统一桥接到 IDE，并为外部 DSH 运行时覆盖工具行文件名、产物、回答内文件引用、Markdown 本地路径和配置文件等页面入口；“在文件夹中显示”对项目目录会定位到 IDE Project 视图。启用默认“自动”策略时，Git 文件显示 VCS Diff，无 Git 项目中已打开的文件也会显示编辑前后 Diff。
- Edit 工具行点击时直接把 DSH 已记录的 applied diff hunks 交给 IDE 原生 Diff，不再依赖 Git 或文件刷新轮询时序；轮询快照仅作为旧版/外部运行时的兼容回退。
- 修复「询问用户」选择框会在数秒空闲后自动消失：IDE 本地代理现在会保持实时事件 WebSocket/SSE 连接，直到浏览器、服务或用户主动结束；不会再因 15 秒 socket 空闲超时取消等待中的工具调用。
- 修复首次触发 `@` 引用时未立即加载当前 IDE 编辑器文件，以及外部 DSH 运行时未挂载 IDE 文件引用扩展的问题。

## 0.1.16

- 顶部栏插件安装入口改为直接执行受限的 `dsh plugin --profile web add` 命令；
- 无需用户预先安装主 DSH，插件会使用内置或已解析的 DSH 运行时在项目隔离 profile 中安装；
- 安装完成后自动启动/重启服务，保留失败输出与原有 profile。

- 顶部工具栏动作补齐 IntelliJ 语义图标；
- 新增“通过 DSH 命令安装插件”入口，输入 npm 包或 Git 地址即可在当前项目隔离 profile 中执行；
- 工具窗口顶部栏新增「恢复默认插件」紧急恢复按钮；
- 服务处于启动失败或停止状态、内嵌页面无法打开时，仍可直接恢复项目级默认 profile 并自动重启；
- 增加确认提示，恢复操作不会修改主 DSH 目录。

- 修复 For IDE 的「同步预设」「同步插件」「恢复默认插件」仍返回 404 且未执行的问题；
- 三个操作改走 JCEF 原生 JavaScript→IDE 通道，不再依赖页面 origin 或 DSH 的 HTTP 请求包装；
- 保留同源 HTTP 桥接作为旧页面兼容回退，并补充三个动作的覆盖测试。

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
