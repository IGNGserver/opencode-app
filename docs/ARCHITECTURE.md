# 实现结构与验收

- `core/OpenCodeApi.kt`: OpenCode REST、Basic Auth、目录上下文、全局 SSE；同一套接口适配 V1/V2 两种协议（能力差异通过 `ServerProtocol` 的 `supports*` 属性暴露）。
- `core/MobileController.kt`: 所有页面共享的服务端状态、任务归一化和操作入口。
- `core/Models.kt`: 数据模型、JSON 投影（含 `TaskReducer` 任务阶段归约）。
- `core/ServerStore.kt`: 服务器资料、推送校验密钥与 Keystore AES-GCM 凭据。
- `core/OfflineCache.kt`: 加密离线缓存（catalog 与消息）。
- `core/KeystoreCipher.kt`: `ServerStore`/`OfflineCache` 共用的 AndroidKeyStore AES-GCM 加解密。
- `core/PairLink.kt`: 官方 `opencode pair` 链接解析（强制 HTTPS）。
- `core/Http.kt` / `core/Diagnostics.kt`: 进程级 OkHttp 连接池/调度器；轻量日志。
- `ui/`: Fluent 2 风格页面和不同消息 Part 的渲染；`MainActivity` 负责根导航与深链。
- `system/`: 通知 Channel、Android Live Updates 请求、小米岛参数、任务前台服务与通知操作。
- `push/`: 可选 FCM 客户端、设备注册，以及推送消息 HMAC 校验。
- `companion/`: OpenCode 插件与只传递任务元数据的 FCM 伴随服务。
- `docs/task-event-contract.json`: Android `TaskReducer` 与 companion `mapEvent` 共同遵守的任务阶段契约（两侧各有测试断言）。

## 必须在真实环境检查

1. 设置 OpenCode Server Basic Auth，使用 HTTPS URL 添加服务器；确认认证失败不会保存资料。
2. 从手机发送任务，检查消息、工具折叠、停止、SSE 重连后状态一致。
3. 触发 permission 和 question；分别从 App 与系统通知操作，检查服务器继续执行。
4. 检查 Todo、子会话、Diff、文件浏览；测试新建、继续、Fork、删除等会话操作。
5. 在 Android 16 设备检查 Live Updates；在已获焦点通知权限的小米 HyperOS 3 设备检查超级岛。
6. 配置 Firebase 和伴随服务后强制结束 App 进程，触发任务完成与待处理事件，检查 FCM 数据消息通知与 Deep Link。

源 API 文档：https://opencode.ai/docs/server/ 。服务端版本可能变化；接入目标实例时应核对该实例 `/doc` 暴露的 OpenAPI 规范。
