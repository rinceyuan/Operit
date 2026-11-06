# 设置页、登录对话框与就绪检查

## 现状

`ModelApiSettingsSection` 对 Codex 有一套专用处理：

- `isCodexProvider` 时隐藏 API Key 与端点编辑，进入页面即重置为固定端点
- `canRequestModelList` 以登录状态为条件，`fetchAvailableModels` 调用 Codex 目录
- `CodexAuthSettingsBlock` 展示账号、登录、登出与额度

`ChatConfigReadiness.evaluate` 接收 `codexAuthenticated` 参数，唯一调用方是 `ModelConfigScreen`。

## 设置页

在 `ModelApiSettingsSection` 中新增 `isGitHubCopilotProvider`，与 Codex 并列处理：

- 收集 `GitHubCopilotAuthManager.authState`
- 不显示 API 端点与 API Key 输入；切换到 Copilot 时端点清空，真实基址来自登录状态
- `canRequestModelList` 为已登录，`fetchAvailableModels` 调用 `GitHubCopilotModelListFetcher`
- 媒体开关只保留图片输入，音频与视频强制关闭
- `GitHubCopilotAuthSettingsBlock`：未登录时显示说明与登录按钮；已登录时显示账号与域名、订阅、额度胶囊、启用模型提示、“Copilot 设置”与“退出登录”按钮
- 进入区块时请求一次账号信息，右上角按钮手动刷新
- `AdvancedSettingsSection` 与 Codex 一样隐藏 API Key 池

Codex 与 Copilot 的专用分支已较多，抽出统一的账号类提供商描述对象属于重构，放在后续单独 PR。

## 登录对话框

`ui/features/githubcopilot/GitHubCopilotLoginDialog.kt`，三个步骤：

1. 输入 GitHub 域名，默认 `github.com`，只接受 `github.com` 与 `*.ghe.com`
2. 请求设备码，自动复制到剪贴板并打开 `verification_uri`
3. 显示大号等宽设备码、复制与“打开 GitHub”按钮，后台轮询直到授权完成；成功后刷新所有服务

对话框关闭时轮询协程随组合一起取消。

## 就绪检查

- 新增 `ChatConfigReadinessIssue.GITHUB_COPILOT_LOGIN_REQUIRED`
- `evaluate` 新增参数 `githubCopilotAuthenticated: Boolean = false`
- `GITHUB_COPILOT` 加入 `credentialOptionalProviders`
- 未登录返回新问题，已登录且模型非空时直接就绪
- 该判断放在 `isHttpEndpoint` 校验之前，因为 Copilot 配置的 `apiEndpoint` 为空
- `ModelConfigScreen` 传入登录状态，并提供“请先登录 GitHub Copilot”提示

## 区域提示

Copilot 不显示“检测到您在中国大陆，但选择了海外服务商”提示，实测国内网络可直接访问 Copilot 服务端点。

## 提供商名称显示

`SettingsSelectorRow` 右侧取值原为 `weight(0.5f, fill = false)`，最多只占约三分之一宽度，“GitHub Copilot”“OpenAI Responses通用”会被截断。改为与左侧标题列等权的 `weight(1f, fill = false)`，较短名称不受影响。

## 字符串

`values/strings.xml` 与 `values-en/strings.xml` 新增 `provider_github_copilot`、`onboarding_config_github_copilot_login_required` 与 `github_copilot_*` 系列。其余语言按 [TODO README](../README.md) 约定在最后用自动化 i18n 单独提交。

## 测试

`ChatConfigReadinessTest` 增加 Copilot 未登录与已登录用例。

## 完成

[DONE]
