# 设置页、登录对话框与就绪检查

## 现状

`ModelApiSettingsSection` 对 Codex 有一套专用处理：

- `isCodexProvider` 时隐藏 API Key 与端点编辑，进入页面即重置为固定端点
- `canRequestModelList` 以登录状态为条件，`fetchAvailableModels` 调用 Codex 目录
- `CodexAuthSettingsBlock` 展示账号、登录、登出与额度
- 切换到 Codex 时强制开启图片与 ToolCall

`ChatConfigReadiness.evaluate` 接收 `codexAuthenticated` 参数，唯一调用方是 `ModelConfigScreen`。

## 设置页改动

在 `ModelApiSettingsSection` 中新增 `isCopilotProvider`，与 Codex 并列处理：

- 收集 `GitHubCopilotAuthManager.authState`
- API Key 与端点输入隐藏，已登录时端点显示为只读的 `authState.apiBase`，未登录时不显示端点
- `canRequestModelList` 为 `copilotAuthState != null`
- `fetchAvailableModels` 调用 `GitHubCopilotModelCatalog`
- 选中模型后按 [3_model_catalog.md](3_model_catalog.md) 的能力映射更新开关
- 新增 `GitHubCopilotAuthSettingsBlock`：未登录时显示登录按钮与 GitHub 域名输入，默认 `github.com`；已登录时显示 `login`、`copilot_plan`、额度胶囊与登出

Codex 的专用分支较多，若新增 Copilot 后条件分支继续膨胀，可抽出 `AccountProviderUi` 描述对象统一两者，但这属于重构，放在本计划完成后单独提 PR。

## 登录对话框

新增 `ui/features/githubcopilot/GitHubCopilotLoginDialog.kt` 与 `GitHubCopilotLoginCoordinator.kt`：

1. 请求设备码后显示 `user_code`，大号等宽字体，提供复制按钮
2. 提供“打开 GitHub”按钮，用 Custom Tabs 打开 `verification_uri`
3. 后台协程轮询，显示剩余有效时间
4. 成功后调用 `completeLogin`，关闭对话框并刷新模型列表
5. 对话框关闭或页面销毁时取消轮询协程

## 就绪检查

`ChatConfigReadiness` 改动：

- 新增 `ChatConfigReadinessIssue.GITHUB_COPILOT_LOGIN_REQUIRED`
- `evaluate` 新增参数 `githubCopilotAuthenticated: Boolean = false`
- `GITHUB_COPILOT` 加入 `credentialOptionalProviders`
- 未登录返回新问题；已登录且模型非空时直接就绪，跳过 API Key 校验
- 该判断必须放在 `isHttpEndpoint` 校验之前，因为 Copilot 配置的 `apiEndpoint` 为空，真实基址来自登录状态
- `ModelConfigScreen` 传入登录状态，并为新问题提供提示文案与跳转

现有参数名 `codexAuthenticated` 保持不变，避免无关改动。

## 字符串

在 `values/strings.xml` 与 `values-en/strings.xml` 新增：

- `provider_github_copilot`
- `github_copilot_login`、`github_copilot_logout`、`github_copilot_domain`
- `github_copilot_device_code_hint`、`github_copilot_open_github`、`github_copilot_polling`
- `github_copilot_not_entitled`、`github_copilot_signup_free`
- `github_copilot_login_required`、`github_copilot_enable_models_hint`
- `github_copilot_quota_*`

其余语言按 [TODO README](../README.md) 的约定在最后用自动化 i18n 单独提交。

## 测试

- `ChatConfigReadinessTest` 增加 Copilot 未登录、已登录无模型、已登录有模型三例
- `ProviderRegionWarningTypeTest` 增加 `GITHUB_COPILOT -> OVERSEAS`

## 完成

