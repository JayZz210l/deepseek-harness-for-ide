# CHANGELOG

Deepseek Harness For IDE 版本历史。版本号自更名后重新起算（0.1.1 起）。

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
