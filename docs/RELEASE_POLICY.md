# 发布规范

## 默认规则

- 用户没有明确指定 release 类型时，一律使用 pre-release。
- 正式 release 必须由用户明确指定 `release` 或“正式版”。
- 发布入口统一使用 `.github/workflows/release.yml`，不直接在本地创建 GitHub Release。
- 每一个 tag 都必须新增 `docs/releases/<tag>.md`，内容用中文说明本次更新、验证结果和已知限制。工作流会在缺少文件时直接失败。
- 版本号使用 `vMAJOR.MINOR.PATCH`；预发布版本在末尾追加 `-alpha.N`、`-beta.N` 或 `-rc.N`，例如 `v0.1.0-rc.1`。

## 稳定签名与产物

- 正式发布必须使用受保护的稳定签名，否则无法覆盖升级已安装的版本。
- 在仓库 Secrets 配置 `OPENCODE_MOBILE_KEYSTORE_FILE`（keystore 的绝对路径，通常由工作流先行写入 runner 临时目录）、`OPENCODE_MOBILE_KEYSTORE_PASSWORD`、`OPENCODE_MOBILE_KEY_ALIAS`、`OPENCODE_MOBILE_KEY_PASSWORD`。
- 配齐后工作流构建 `:app:assembleRelease`（`isDebuggable=false`）并校验签名；未配齐时正式发布会直接失败。
- 预发布在缺少上述 secrets 时回退到 debug 签名，并在 Release 说明中标注。
- keystore 与密钥只存放在受保护的 Secrets 或密钥管理器中，不得进入仓库。

## 发布流程

1. 完成功能和验证，更新 `docs/releases/<tag>.md`。
2. 提交变更并推送分支。
3. 创建并推送版本 tag，或手动运行 `发布 OpenCode Mobile Release` 工作流。
4. 工作流重新执行 Android 单元测试、Debug APK 构建和 companion 测试。
5. 工作流把中文说明和 APK 一起发布到 GitHub Release。tag 触发默认是 pre-release；手动运行时只有选择 `release` 才会发布正式版。

## 说明模板

```markdown
## 本次更新

- 用中文列出用户可感知的功能或修复。

## 验证

- 列出实际执行的验证命令和结果。

## 已知限制

- 列出尚未在真实设备、真实 OpenCode Server 或推送服务上验证的部分。
```
