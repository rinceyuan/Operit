# 额度、测试与文档

## 额度展示

自 2026-06-01 起 Copilot 按 GitHub AI Credits 计费。额度数据有两个来源：

- `GET copilot_internal/user` 的 `quota_snapshots.{chat, premium_interactions}`，字段 `entitlement`、`remaining`、`percent_remaining`、`unlimited`，以及顶层 `quota_reset_date`
- 每次对话响应的 `x-quota-snapshot-premium_models` 或 `x-quota-snapshot-premium_interactions` 头

首版只做第一种：

- 进入 Copilot 设置区块时请求一次，之后由用户点击刷新
- 优先展示 `premium_interactions`，没有时展示 `chat`；显示剩余百分比、剩余与总量、进度条与重置日期；`unlimited` 显示为不限量
- 不记录请求头与响应体

## 单元测试

- `GitHubCopilotAuthClientTest`：域名规范化、`*.ghe.com` URL、模型目录过滤与排序、账号与额度解析
- `GitHubCopilotProviderTest`：协议路由、端点拼接、`X-Initiator` 判定、图片检测
- `ChatConfigReadinessTest`：Copilot 未登录与已登录

执行范围：`./gradlew :app:testDebugUnitTest --tests "*GitHubCopilot*" --tests "*ChatConfigReadinessTest*"`

## 手动验证

2026-11-06 在小米真机上使用 Copilot Enterprise 账号验证：

- 设备码登录，GitHub 授权页显示 GitHub Copilot Plugin by GitHub
- 账号、订阅与高级请求额度正确展示
- 基址自动切换为 `https://api.enterprise.githubcopilot.com`
- Chat Completions 与 Responses 两种协议的聊天、Tool Call 连接测试通过
- 聊天页流式对话返回 HTTP 200

未覆盖：Copilot Free 与 Pro 个人账号、`*.ghe.com` 数据驻留账号、撤销授权后的重新登录提示。

## 文档

- 在 `docs/doc-src` 的模型提供商文档中新增 GitHub Copilot 章节：支持的订阅、登录步骤、模型启用说明、计费与风控说明、企业域名填写
- 其余语言的字符串按 [TODO README](../README.md) 约定用自动化 i18n 单独提交
- 合并完成后用单独 PR 把本目录移入 `docs/.Meta/Legacy/TODO`

## 后续项

- 读取响应头额度，实时更新胶囊
- `POST /models/{id}/policy` 一键启用模型
- `/v1/messages` 与 Claude 思考块回传
- Codex 与 Copilot 账号类提供商的设置页重构

## 完成

额度与测试已完成，用户文档与 i18n 待办。
