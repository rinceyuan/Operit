# 额度、测试与文档

## 额度展示

自 2026-06-01 起 Copilot 按 GitHub AI Credits 计费。额度数据有两个来源：

- `GET copilot_internal/user` 的 `quota_snapshots.{chat, premium_interactions}`，字段 `entitlement`、`remaining`、`percent_remaining`、`unlimited`、`overage_permitted`，以及顶层 `quota_reset_date`
- 每次对话响应的 `x-quota-snapshot-premium_models` 或 `x-quota-snapshot-premium_interactions` 头，URL 编码的 `ent`、`rem`、`rst`

首版只做第一种，沿用 Codex 的交互：

- 设置页额度胶囊只在用户点击刷新时请求
- 展示剩余百分比、进度条与重置日期；`unlimited` 显示为不限
- 每个账号保存最近一次快照，独立 DataStore，模型配置变更不清除
- 不记录请求头与响应体

响应头额度属于增量优化，记录在本文档末尾作为后续项。

## 单元测试汇总

- `GitHubCopilotProtocolTest`
- `GitHubCopilotDeviceFlowClientTest`
- `GitHubCopilotUserResponseTest`
- `GitHubCopilotRoutingTest`
- `GitHubCopilotInitiatorTest`
- `GitHubCopilotModelCatalogTest`
- `ChatConfigReadinessTest` 与 `ProviderRegionWarningTypeTest` 增量用例
- `ClaudeProvider` 认证钩子回归

执行范围：`./gradlew :app:testDebugUnitTest --tests "*GitHubCopilot*" --tests "*ChatConfigReadiness*" --tests "*ProviderRegionWarningType*"`

## 手动验证

使用一个 Copilot Free 账号与一个 Copilot Pro 账号分别验证：

- 登录、登出、重新登录
- 目录中至少各选一个 Chat、Responses、Messages 协议模型完成流式对话
- 工具调用多轮，确认续轮请求 `X-Initiator: agent`
- 图片输入，确认 `Copilot-Vision-Request: true`
- 在 GitHub 设置页撤销授权后发消息，确认提示重新登录
- 额度刷新

## 文档

- 在 `docs/doc-src` 的模型提供商文档中新增 GitHub Copilot 章节：支持的订阅、登录步骤、模型启用说明、计费说明、企业域名填写
- 在开发者文档中记录协议常量位置与 `X-GitHub-Api-Version` 的升级方式
- 合并完成后用单独 PR 把本目录移入 `docs/.Meta/Legacy/TODO`

## 后续项

- 读取响应头额度，实时更新胶囊
- `POST /models/{id}/policy` 一键启用模型
- Codex 与 Copilot 账号类提供商的设置页重构

## 完成

