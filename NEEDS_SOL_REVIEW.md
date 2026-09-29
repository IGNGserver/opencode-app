# 需要更强模型决策的问题（AUDIT.md 整改遗留）

本文件记录本轮系统性整改 AUDIT.md 时，**无法高置信确定正确方案**、涉及核心安全模型 / 权限体系 / 数据一致性 / 重大架构重设计的问题。这些不是"没做"，而是"做了会冒较大风险或需要产品决策"，留给更强模型或用户处理。

基线：`db7289d`。整改分支：`fix/audit-remediation`。验证见提交信息与下方"已完成验证"。

## 1. 服务端权限域与 "always" 的真实语义（A16）

- 现状：App 把协议值 `always` 的按钮标为"始终允许"，`replyPermission` 只上报决策，客户端没有在运行时探测服务端把授权保存到什么范围。
- 本轮新证据：核对 OpenCode 当前发布的 V2 OpenAPI（`https://opencode.ai/v2/openapi.json`）后确认：
  - `Permission.Request` 字段为 `action`/`resources`/`save`/`metadata`/`source{type,messageID,id}`；`Permission.Reply` 枚举为 `once|always|reject`。
  - `always` 会生成"已保存权限" `PermissionSaved.Info`，其字段含 `projectID`/`action`/`resource`/`time`，即**绑定到项目、持久、可撤销**，而不是"当前会话"。
  - 服务器提供 `GET /api/permission/saved` 与 `DELETE /api/permission/saved/{id}`，可用于列出和撤销已保存规则。
- 本轮已做：把 UI 与通知的按钮文案统一为范围中立的"始终允许"，并在界面展示"选择始终允许将在服务器保存规则：<action/resource>"；不再声称"当前会话记住"。
- 仍待更强模型/用户决定：是否在 App 内提供"已保存权限"查看与撤销入口；以及 `always` 在 V1 协议下的真实范围（V1 契约未在本轮核对）。注意发布规范明确 V2 为当前版本，V1 仅作迁移输入。

## 2. 多服务器并行任务监控 vs 单连接的产品形态（A09 结构性部分）

- 现状：`TaskMonitorService` 保存多个 `(serverId, sessionId)`，但只观察单例 Controller 的当前连接；切到 B 后 A 的任务在前台服务中不再更新，也不存在独立的 A 后台连接。
- 为什么没做：要真正支持并行监控需要"以 serverId 为键的连接管理器"（多 SSE、多 API、独立生命周期），属于重大架构重设计，会牵动 Controller 全局状态模型（A02/A05/A10 也共享这份状态）。本轮只做了低成本修正（通知开关、前台通知生命周期、不跨服务器伪造状态），没有引入新的连接模型。
- 建议：先明确产品是"只监控当前连接"还是"并行多服务器"，再决定是显式中止/转移监控还是引入连接管理器。

## 3. Controller 与事件模型的整体拆分（审计第 5 节的结构性技术债）

- 现状：`MobileController` 同时承担连接、catalog、会话、命令、缓存和通知；两套 reducer（`TaskReducer` 与 companion `mapEvent`）与 V1/V2 投影分散，导致 A02/A05/A09/A10/A13 共用全局可变状态。
- 为什么没做：审计本身建议"先锁定行为测试再移动代码"，而当前仓库没有 Controller/Compose/通知生命周期的仪器测试，也没有真实 OpenCode 实例的契约测试。在缺少这些测试的情况下重构共享状态模型，风险高于收益。
- 本轮已做：把异步操作收敛到不可变 `OperationContext`、把降级状态显式化（`degraded`）、统一了两端工具投影与契约用例，为后续拆分打下可测试的边界。
- 建议：先补 Controller 层（A/B 服务器与多项目切换、延迟响应、局部端点失败）与仪器测试，再拆出"每服务器连接/快照仓库 + 不可变命令执行器 + 归一化事件模型 + UI 状态层"。

## 4. SSE 可靠性与事件游标语义（A04）

- 本轮已做：
  - 队列溢出不再静默丢弃，而是让流失败以触发重连与全量对账。
  - 新增常驻"控制面对账"：流正常时每 45 秒重读 catalog/status/permissions/questions，即使服务端在没有可见失败的情况下丢事件，权限与终态也会收敛。
- 仍待验证：`Last-Event-ID` 的服务端重放语义。当前发布的 V2 OpenAPI 未在 `/api/event` 描述重放行为，仅 SSE 帧带 `id`；客户端已不强依赖重放（靠周期对账兜底）。
- 建议：在目标实例上做事件 ID 重放/乱序/正常关闭验收后，再决定是否依赖服务端重放。

## 5. V2 契约与当前发布 OpenAPI 的差异（已做安全回退，其余需目标实例确认）

核对 `https://opencode.ai/v2/openapi.json`（OpenCode V2 的权威规范）后发现应用内 V2 适配与当前发布规范存在多处不一致。对**无歧义的路径级差异**加了向后兼容回退；对会改变请求体语义、可能被服务端"忽略未知字段"而静默失败的差异**没有贸然改动**，因为无法确认设备实际连接的是哪个 V2 修订。

已加兼容回退（新增性，不改变旧修订行为）：

| 位置 | 处理 |
|---|---|
| V2 health | 先试旧 `GET api/health`，404 时回退当前 `GET /api/info`。此前 `api/health` 404 会让连接直接失败 |
| V2 unrevert | 先试旧 `POST .../revert/clear`，404 时回退当前 `DELETE /api/session/{id}/revert` |

仍待目标实例确认（未改）：

| 位置 | 应用当前 | 当前发布规范 | 风险 |
|---|---|---|---|
| V2 发送消息体 | `POST .../prompt` body `{prompt:{text}}` | body `{text, files, agents, ...}` | 若为当前 V2，发送可能 400 或静默失败 |
| V2 权限回复体 | body `{reply}` | body `{decision: once\|always\|reject}`，`additionalProperties:false` | 当前 V2 下回复可能 400 |
| V2 问题 | `api/question/request`、`.../question/{id}/reply\|reject` | 规范无 question，使用 `session/{id}/form`/`form/{id}/reply` | 问题交互在 V2 可能不可用 |
| 权限 source | 读 `source.callID` | 字段为 `source.id` | `toolCallId` 恒为空 |
| 权限回复作用域 | 无 | `always` 生成项目级 `PermissionSaved.Info`，可 `GET/DELETE /api/permission/saved` | 见第 1 节 |

- 本轮已做：权限文案范围中立化 + 保存规则预览；health 与 unrevert 的兼容回退；其余保持原状并记录。
- 建议：以目标实例 `/openapi.json` 快照为准逐项核对；发送体与权限回复体建议采用"规范形状优先、失败回退旧形状"的显式策略并加目标实例契约测试；form/question 差异需要先确认语义再实现。

## 6. 无法在本机验收的边界（证据缺口，非代码缺陷）

- 真实 OpenCode Server 的 V1/V2 契约、真实 Android 设备 UI 与低内存回收、FCM 实际投递、Android 16 Live Updates 提升、HyperOS 超级岛、历史 APK 覆盖安装。
- 本轮已做静态与 JVM/MockWebServer 层面的验证；上述真机/真服务端验收需在目标环境完成，不能用本机结果宣称已通过。

## 7. 其他低优先级加固

- GitHub Actions 已固定到 commit SHA（`actions/checkout` v4.4.0、`actions/setup-java` v4.9.1、`actions/setup-node` v4.4.0）。
- Gradle wrapper 已加入 `distributionSha256Sum`。
- Android 依赖尚未启用 Gradle 依赖验证元数据（`verification-metadata.xml`）；生成与维护完整校验清单属独立供应链改动，建议单独 PR。
- UI 仍一次性加载整个会话历史做展示（`loadSession`）；网络层已限制单响应与分页总量。大历史的分页/懒加载属于界面层改动，建议在补了 Compose 仪器测试后再做。


## 已完成验证（本轮）

- `ANDROID_HOME=/home/lvziw/Android/Sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug`：通过（21 项单元测试）。
- `:app:assembleRelease`：通过，产物 `app-release.apk` 非 debuggable（未配置生产 keystore 时回退 debug 签名，正式发布由工作流阻断）。
- `:app:lintDebug`：BUILD SUCCESSFUL，0 Error。
- `cd companion && node --test`：8/8 通过。
- 新增回归测试覆盖：跨源重定向零请求（REST 与配对）、请求取消即时生效、v2 推送签名绑定目录/设备/新鲜度、companion 落盘失败拒绝、失败投递重试、设备注销、插件 toolKind 投影、契约 V2 tool 用例、离线/降级状态标志、V2 经 `api/info` 检测、V2 unrevert 回退。
- Gradle 依赖验证元数据已用全新依赖缓存（`GRADLE_USER_HOME` 隔离 + `--rerun-tasks`）验证 debug/test/lint/release 全部通过，确认清单完整。
