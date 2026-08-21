# DeepSeek Harness for JetBrains — 架构说明

## 1. 目标

在 JetBrains IDE 侧边栏提供 DeepSeek Harness 的完整使用体验。选型结论：
**内嵌 DSH 官方 Web GUI（JCEF）+ 插件侧进程托管**，而不是自研整套前端。

理由：

- DSH 已经提供了生产级 Web 前端（React，随 `@deepseek-ai/dsh` 包分发），覆盖聊天、
  会话管理、工具审批、文件 Diff、目标/计划、子智能体、Cordis 工具面板等核心能力；
- 自研 UI 需要逆向并维护 `/api` 协议客户端，工程量数倍，且无法随 DSH 升级自动获得新功能；
- JCEF 是 JetBrains 平台自带的内嵌 Chromium，工具窗口可直接承载任意本地 Web 应用。

## 2. DeepSeek Harness 的外部集成面（调研结论）

来源：`@deepseek-ai/dsh@0.1.0-rc.6` 的 CLI 与各包的 README。

### CLI

```
dsh web [--host <host>] [--port <port>] [--trusted-host <authority>...]
```

- `--port 0` 让 OS 分配空闲端口；启动后 stdout 打印 `dsh web: http://127.0.0.1:<port>`；
- **内置 web profile 的默认端口是 3080**（不是 0）：插件在"自动端口"模式下也必须显式传
  `--port 0`，否则与已运行的 dsh web 实例冲突（已实测 EADDRINUSE）；
- `--host` 仅接受 `127.0.0.1`（默认）与显式地址，**拒绝 `0.0.0.0`**；
- 无 `--help` 之外的应用级子命令；`dsh --profile headless "<任务>"` 是一次性任务模式
  （无交互回环，不适合作为插件的常驻会话，留作路线图里的"单次任务"能力）。

### 服务端与协议（插件当前不直接使用，供路线图）

- `dsh-host-webserver`：`node:http` 服务器 + upgrade 路由，默认仅回环绑定；
- `/api`：浏览器侧 HTTP POST（unary/respond RPC）+ 两条下行 WebSocket
  （`/api/events.mux`、`/api/events.host`），浏览器不发应用数据；
- **browser-trust fence**：所有 `/api` 请求与 WebSocket 握手按 `Host`/`Origin` 头校验
  （回环默认放行）。JCEF 加载回环 URL 天然满足该策略；
- **h2c 升级陷阱（0.3.6 事故根因）**：JDK 的 `java.net.http.HttpClient` 默认 HTTP/2，
  向 `/api` 发送 `Upgrade: h2c` 明文升级，DSH 服务器按 upgrade 路由查不到 h2c 处理器，
  **直接断开连接且不返回任何字节**。插件直连 `/api` 的 `DshApiClient` 因此全部静默
  失败（工作区登记、凭据预检、选区直发），必须显式 `.version(HTTP_1_1)`；TCP 代理
  `DshApiProxy` 里已有同样的 h2c 降级逻辑，但原生网关模式下浏览器/插件都直连真实
  端口，绕过了它。node 的 fetch（undici）不发 h2c，所以命令行复现不出该问题；
- 特权 native 方法：`host.pickDirectory`、`host.openPath`（打开宿主桌面文件）——
  这是路线图"文件跳转到 IDE"的代理拦截点；
- `dsh-headless`：一次性任务，输出最后一条助手文本后退出（0/1 退出码）。

### 数据目录

- DSH home 解析优先级：显式配置 > `$DSH_HOME` > `~/.dsh`；
- 凭据、profiles、会话存储都在 DSH home 下。

**插件策略（0.3.3 起）**：默认**不共享**主 home，而是为每个项目使用
`%LOCALAPPDATA%\deepseek-harness-jetbrains\dsh-home\<项目名>-<hash>` 的隔离目录，
并在启动时从主 home（`$DSH_HOME` 或 `~/.dsh`）**单向复制** `.credentials.yaml` 与
`settings.yaml`（仅当缺失或内容不同；不回写、不传播删除）。设置里的 `DSH_HOME 覆盖`
留空即此模式；填 `default` 才恢复旧的"继承 IDE 环境"行为（与外部 dsh web 共用
`~/.dsh`，仅建议调试时使用）。

理由（事故记录）：早期版本默认继承 IDE 环境，插件实例与用户外部运行的
`dsh web` 共用 `~/.dsh`。两个实例并发共享 home 时发生互扰——workspace 注册表混入
两个实例的会话、插件进程被停止后留下孤儿会话由另一实例代写 end-seed，外部实例
重启后像是"配置被全部清空"；而插件实例因 IDE 环境无 `DEEPSEEK_API_KEY` 且 home
中尚无 `.credentials.yaml`，首次提问直接报
`llm-deepseek: no API key for provider route "deepseek-official"`，内嵌界面停留在
"session-<id>" 标题且无任何回答与提示。隔离 home + 单向继承 + 启动后凭据预检
（`credentials.describe`，缺失即弹窗）修复这一类问题。

### 工作区

- 工作区是 Web 应用内的持久化实体（`ctx.workspaceRegistry`），由用户在界面中选择；
- Web 前端未发现 URL 深链参数（`searchParams` 无匹配）；
- 插件将子进程工作目录设为项目根目录，作为宿主侧默认值；
- **0.3.4 起**：服务就绪后插件通过 `/api` 调用 `workspace.list` / `workspace.create` /
  `workspace.insertBefore`，把 IDE 项目目录幂等登记为工作区并置顶——侧边栏打开时
  工作区默认就是当前 IDE 项目，无需在工作区选择器中再选一次（失败只记日志，不影响
  手动选择）。
- **0.1.13 起**：工作区自动选中同时覆盖新旧两种 DSH 策略——置顶（旧版按列表首位）
  与「预置空白会话」（新版按最近活跃：目标工作区最近活跃度落后时创建一个空白会话，
  Web 前端选中该工作区时直接复用这个会话）。同版本起启动时自动把
  `~/.dsh/.agent-presets` 单向同步进隔离 home：`settings.yaml` 引用的预设缺失会让
  `session.create` 全部失败（`agent-preset-not-found`），表现为侧边栏完全无法选择
  工作区。细节见 §7。

## 3. 插件分层

```
┌────────────────────────────────────────────────────────────┐
│ JetBrains IDE                                                │
│  ┌──────────────────────────┐  ┌──────────────────────────┐ │
│  │ ToolWindow (右侧)         │  │ Settings                 │ │
│  │  DshToolWindowPanel       │  │  DshSettingsConfigurable │ │
│  │  ├─ 工具栏 Actions        │  │  DshSettingsState (xml)  │ │
│  │  ├─ CardLayout:           │  └──────────────────────────┘ │
│  │  │   ├─ StatusPanel       │                               │
│  │  │   ├─ JcefBrowserPanel  │  ← JBCefBrowser 加载          │
│  │  │   └─ FallbackPanel     │    http://127.0.0.1:<port>    │
│  │  └─ 日志面板              │                               │
│  └───────────┬──────────────┘                               │
│              │ 消息总线 DshServerTopics.SERVER_STATUS        │
│  ┌───────────▼──────────────┐                               │
│  │ DshProcessManager        │  ProjectService, 单线程调度    │
│  │  启动/停止/重启/看门狗     │  stdout 解析 URL + HTTP 健康检查 │
│  └───────────┬──────────────┘                               │
└──────────────┼───────────────────────────────────────────────┘
               │ spawn: dsh web --host 127.0.0.1 --port <auto>
               ▼
        ┌─────────────────┐
        │ dsh (Node.js)    │  本地进程, cwd = 项目根目录
        │  HTTP /api + WS  │
        │  React Web 前端  │
        └─────────────────┘
```

## 4. 进程生命周期

状态机：`STOPPED → STARTING → RUNNING ⇄ STOPPING`，异常 → `FAILED`。

- 所有生命周期操作在单线程 `ScheduledExecutorService` 上串行执行，线程安全；
- `start`：校验命令 → 解析 dsh 命令（显式路径 > PATH > **插件内置运行时** > npx 缓存；
  Node.js 会实际执行 `--version` 校验 18+，Windows 下依次检查 IDE 进程 PATH、当前用户/系统
  环境 PATH 与常见安装位置，避免 Node 安装晚于 IDE 启动时误报；最终把 Node 目录注入子进程 PATH）→
  组装 `cmd.exe /c`（Windows）或 `/bin/sh -c`（其他平台）→
  spawn → 双线程泵取 stdout/stderr → 正则捕获 URL（90s 超时）→ HTTP 健康轮询
  （45s 超时）→ 发布 `RUNNING(url)` → 注册 `process.onExit` 看门狗；
- 发布 `RUNNING` 前**同步**执行工作区登记：先单向同步 `~/.dsh/.agent-presets`
  （0.1.13 起，settings 引用的预设缺失会让 session.create 全挂），再
  `workspace.list` / `workspace.create` / `workspace.insertBefore` 把 IDE 项目目录
  幂等登记为工作区并置顶，必要时预置空白会话（覆盖新版「最近活跃」选中策略）。
  必须在浏览器加载之前完成——web 前端只对首次基线做一次性初始工作区选择
  （`startInitialSelection`，基线时无 workspace 即永久放弃），晚到的 workspace 不会被
  自动选中，界面会停留在空的"选择工作区"；失败只记日志，不影响手动选择。发布
  `RUNNING` 后异步执行 `credentials.describe` 预检 DeepSeek API Key（缺失弹窗）；
  服务重启/停止时把内嵌浏览器页面标记为脏，下一次 RUNNING 即使 URL 相同也强制重载；
- 看门狗：非主动停止的退出 → `FAILED`；开启自动重启时 2s 后自动拉起；
- `stop`：Windows 用 `taskkill /T /F` 杀进程树，其余平台 destroy/destroyForcibly；
- 项目关闭：`dispose()` 停止进程（每个项目一个实例）；
- 端口策略：默认 `--port 0` 自动分配（读取实际 URL），固定端口时支持多个项目复用。

## 5. UI 说明

- 工具窗口 `id="DeepSeek Harness"`，右侧、secondary、不自动激活；
- 中心卡片：`status`（启动中/失败/停止，含重试）→ `browser`（JCEF）或
  `fallback`（JCEF 不可用时的外链面板，保证插件在特殊环境仍可用）；
- 底部日志面板：滚动缓冲（默认 1000 行），1s 定时刷新；
- 打开工具窗口即触发 `startAsync()`（幂等）；`postStartupActivity` 按设置自动启动。

## 6. 构建

- Gradle 8.14 + Kotlin 2.1.20 + IntelliJ Platform Gradle Plugin 2.2.1；
- 平台基线：IntelliJ IDEA Community 2024.3.5（`since 243 / until 253.*`），
  显式依赖 bundled plugin `com.intellij.jcef`；
- `buildSearchableOptions` 已禁用（本地构建提速）；
- **内置 DSH 运行时（0.3.4 起）**：`bundleDshRuntime` 任务把本机 dsh 安装的
  `node_modules`（完整依赖闭包，profile 的 `profiles/node_modules` 符号链接指向安装根，
  缺一个包都会导致启动失败）复制进插件分发包的 `dsh-runtime/` 并写入 `version.txt`；
  构建时依次查找 `-PdshRuntimePath`、npx 缓存中最新的 `@deepseek-ai/dsh`，
  `-PskipDshRuntime=true` 可跳过（产出轻量包）。IDE 解压插件 zip 后运行时就位，
  无需首次运行再解压。注意原生模块（node-pty、sharp 等）按平台分发，跨平台发布
  需要分别在各平台构建；
- **内置 Node.js 运行时（0.1.2 引入、0.1.3 起默认关闭）**：`bundleNodeRuntime` 任务把
  `node.exe`（+LICENSE）复制进 `node-runtime/`，来源 `-PnodeRuntimePath` → PATH →
  `C:\Program Files\nodejs`。**默认不打包**（`skipNodeRuntime` 默认 true，`-PskipNodeRuntime=false`
  重新启用）：改为启动时未检测到 node 则 FAILED + 带"下载 Node.js"按钮的弹窗引导到
  nodejs.org。运行时解析顺序：进程 PATH → Windows 当前用户/系统 PATH → 常见安装位置 →
  内置 node（若启用），并区分“未安装”与“版本低于 18”。同样按平台分发；
  上架 Marketplace 前应把 Node.js 的 LICENSE 放进构建来源目录或写进商店描述；

## 7. DSH 频繁更新的兼容策略（0.1.13 起）

DSH 更新很快，且插件需要同时支持内置（版本钉死）与外部（任意版本）运行时。
0.1.13 修复的两个事故（启动弹外部浏览器、工作区无法选择）都源于对 DSH 行为的
**隐式假设**：假设 web 子命令不打开浏览器、假设 `--patch` 可以插在 `--host` 之后、
假设 Web 前端按列表顺序选择初始工作区、假设隔离 home 只需继承凭据与 settings。
本节把每一条假设替换为可跨版本存活的做法：

1. **CLI 契约：launcher 旗标先于一切应用参数**。DSH launcher 在自己的旗标
   （`--patch`、`--profile` 等）结束后把剩余参数原样交给应用，`--host`/`--port`
   对 launcher 是未知参数。因此 `--patch <file>` 必须**紧跟 `web` 子命令**；
   插在 `--host` 之后会被新版 launcher 当作应用参数 → `too many arguments` 启动失败。
   该布局由纯函数 `DshWebLaunch.buildTokens` 统一生成，并有单元测试锁定。
2. **能力探测优先于版本判断**。`--no-open` 是否存在不查版本号，而是跑一次
   `dsh web --help`（只解析帮助文本、不启动服务，且对隔离 DSH_HOME 执行、绝不触碰
   主 home），按输出是否含 `--no-open` 决定是否传参；结果按项目会话缓存。内置运行时
   版本由插件钉死，直接跳过探测。探测失败/超时一律**保守不传**：旧版 dsh 拒绝未知
   旗标，而旧版本来就不会自动开浏览器，保守方向不会制造回归。
3. **Wire 解析对嵌套不敏感**。`DshApiClient` 不再用形状相关的正则解析响应，而是用
   最小 JSON 解析树（`DshJson`，纯 JDK）在**任意深度**查找字段（`workspaceId`、
   `sessionId`、`configured`…）。DSH 曾把 `workspace.create` 的返回值包进 `workspace`
   对象、给 `session.list` 条目加入 `projections` 嵌套对象；树遍历对这些变化天然免疫。
4. **工作区选中覆盖两种策略**。旧版 Web 前端按列表首位自动选工作区，新版按「最近
   活跃」（成员会话 `updatedAt` 最大，空工作区回退 `createdAt`）。插件对目标工作区
   同时满足两者：置顶（旧策略）+ 必要时预置一个空白会话（新策略——空白会话的
   `updatedAt` 即当下，Web 前端打开该工作区时还会**复用**这个空白会话，不产生垃圾）。
   最近活跃规则镜像在纯函数 `DshWorkspacePolicy` 中，有测试锁定，DSH 再改策略时只需
   扩展该策略对象。
5. **隔离 home 的继承面补齐**。继承 `settings.yaml` 时其中引用的 Agent 预设
   （如锚定 persona）必须同时存在，否则每次 `session.create` 都报
   `agent-preset-not-found`、界面无法选择任何工作区（0.1.13 事故根因）。现在启动时
   自动把 `~/.dsh/.agent-presets` **单向**同步进隔离 home（幂等、失败仅记日志），
   手动「同步预设」按钮保留用于运行中再同步。
6. **启动失败保留回退阶梯**。组合层补丁启动失败 → 自动以无补丁 + TCP 代理重试一次；
   再失败 → FAILED 状态卡 + 完整进程日志。任一阶梯失败都不弹外部浏览器、不丢日志。

原则总结：**凡是 DSH 可能改变的行为（旗标、响应形状、选择策略、home 内容），插件
要么探测、要么同时覆盖新旧两种语义、要么把解析做成结构不敏感**；并在改动 DSH
运行时版本时按「内置 0.1.1-rc.1 / 外部 npx 0.1.0-rc.6 / 最新源码树」三档分别做
启动 + workspace/session API 冒烟验证。

## 8. 路线图（第二阶段）

已实现（v0.2.0 → v0.4.0）：

1. ✅ **编辑器选区直发提问**（`session.prompt` 直连 /api，自动定位会话）；
1.5 ✅ **插件 / 预设同步与恢复默认**（0.1.9）：「For IDE」栏目按钮经 `host.openPath`
   发送 `dsh-ide://sync-plugins` / `dsh-ide://sync-agent-presets` /
   `dsh-ide://reset-plugins` 标记路径。`DshPluginSync` 把主 home `profiles/web` 的
   清单文件单向复制进项目隔离 home 并运行 `pnpm install`（覆盖前备份、失败回滚）；
   `DshPresetSync` 复制 `~/.dsh/.agent-presets` 预设目录（DSH 预设发现实时重读，无需
   重启）；`DshPluginReset` 用「先改名备份、重启成功再删除、重启失败还原」的原子方式
   清空已同步插件并回到出厂默认。同步/重置期间新增 `SYNCING` / `RESETTING` 状态卡
   （温馨提示 + 禁用启停/重启按钮），避免用户误判为崩溃；
2. ✅ **使用统计面板** 与 **中英双语 i18n**；
3. ✅ **IDE 内文件跳转 — 双实现**：
   - **传输层 TCP 代理**（`DshApiProxy`，v0.2.0）：逐请求解析转发循环，拦截
     `POST /api/host.openPath`；流式/WebSocket 升级走原始字节泵。已通过独立冒烟测试
     （含 keep-alive 复用拦截、h2c 升级请求降级、chunked 流式转发）。
   - **DSH 组合层原生网关**（v0.3.0，默认「自动」模式）：`dsh web --patch <patch.yml>`
     叠加层**禁用** `api-gateway` 行并 **insert** 我们的 Cordis 网关（`ide-bridge.mjs`），
     以 `createApiProxy(ctx, { openPath → IDE 桥 })` 重建整个 ApiProxy 契约；
     IDE 侧 `DshIdeBridge`（127.0.0.1 + 每次启动随机 token）接收并打开文件。
     原生模式启动失败自动回退代理；设置页可选 auto/proxy/off。
4. ✅ **Diff 进化**（v0.4.0）：`host.openPath` 命中文件后，按设置打开 IDE 原生
   Diff 查看器（`DiffManager` + `ChangeListManager`，对比 VCS 基线），而非总是落入
   普通编辑器；DSH 侧无原生 openDiff 接口，且其 Web 前端的 Diff 数据
   （`{path, oldText, newText}`）只在客户端渲染，故用 IDE 自己的 VCS 基线作为"改前"
   来源，纯 IntelliJ API、不碰桥接、三种文件跳转模式下均生效（非 VCS 项目无基线时
   退化为直接打开）。

    组合层逆向要点（对后续维护关键）：
    - patch 行的 `name` 是**校验守卫不是覆盖值**：与原行不符则整条 patch 被跳过
      （`dsh-app-boot` `applyEntryPatches`），换模块必须 `disabled: true` + `insert`；
    - 行 `name` 直接交给动态 `import()`：Windows 绝对路径必须写成 `file://` URL
      （裸盘符路径报 `ERR_UNSUPPORTED_ESM_URL_SCHEME: protocol 'c:'`）；
    - `ApiProxyService` 公开配置只有 `nativeOpen`（能力开关），`openPath` 只能从代码注入；
    - **`ctx.provide` 的第三个参数是可用性谓词 `check`（`impl.check.call(...)`），不是
      immediate 标志**：误传 `true` 会让所有 `inject: ['apiProxy']` 的依赖分支永远不
      激活——最致命的是 `client-connection` 插件据此注册 `/api/events.mux` 的
      WebSocket 下行路由，事件流全断，内嵌界面无任何实时更新（0.3.7 事故根因）；
    - 桥接模块用顶层 await 动态导入 dsh 安装根下的 `dsh-host-apiproxy/lib/index.js`，
      运行参数全部走环境变量（DSH_IDE_BRIDGE_IMPL/URL/TOKEN），文件零改写。

待办：目录选择器 IDE 化（`directoryPicker` 服务是公开接缝）、使用统计细化、发布到 Marketplace。
