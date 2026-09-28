# 需要更强模型决策的问题（AUDIT.md 整改遗留）

本文件记录本轮系统性整改 AUDIT.md 时，**无法高置信确定正确方案**、涉及核心安全模型 / 权限体系 / 数据一致性 / 重大架构重设计的问题。这些不是"没做"，而是"做了会冒较大风险或需要产品决策"，留给更强模型或用户处理。

基线：`db7289d`。整改分支：`fix/audit-remediation`。验证见提交信息与下方"已完成验证"。

## 1. 服务端权限域与 "always" 的真实语义（A16 核心）

- 现状：App 把协议值 `always` 的按钮标为"当前会话记住"，`replyPermission` 只上报 `reply`/`remember`，客户端没有任何证据能保证服务端把授权限制在"当前会话"。
- 为什么没做：需要目标 OpenCode 实例的真实契约（V1 `permission/:id/reply`、V2 `api/session/:id/permission/:id/reply` 的 `remember`/`always` 作用域、是否跨会话持久、能否撤销）才能确定正确的 UI 文案与请求字段。没有 `/doc` 或服务端实现快照，贸然改文案或增加"记住范围"参数可能制造新的错误承诺。
- 本轮已做：系统通知不再在缺少上下文时直接给长期授权（只展示 action+patterns+保存规则），把"始终允许"文案统一为"当前会话记住"。**作用域本身仍未验证。**
- 建议：拿到目标版本 OpenAPI 后再决定 UI 文案与请求参数，必要时对 always 增加"将保存的规则"预览和撤销入口。

## 2. 多服务器并行任务监控 vs 单连接的产品形态（A09 结构性部分）

- 现状：`TaskMonitorService` 保存多个 `(serverId, sessionId)`，但只观察单例 Controller 的当前连接；切到 B 后 A 的任务在前台服务中不再更新，也不存在独立的 A 后台连接。
- 为什么没做：要真正支持并行监控需要"以 serverId 为键的连接管理器"（多 SSE、多 API、独立生命周期），属于重大架构重设计，会牵动 Controller 全局状态模型（A02/A05/A10 也共享这份状态）。本轮只做了低成本修正（通知开关、前台通知生命周期、不跨服务器伪造状态），没有引入新的连接模型。
- 建议：先明确产品是"只监控当前连接"还是"并行多服务器"，再决定是显式中止/转移监控还是引入连接管理器。

## 3. Controller 与事件模型的整体拆分（审计第 5 节的结构性技术债）

- 现状：`MobileController` 同时承担连接、catalog、会话、命令、缓存和通知；两套 reducer（`TaskReducer` 与 companion `mapEvent`）与 V1/V2 投影分散，导致 A02/A05/A09/A10/A13 共用全局可变状态。
- 为什么没做：审计本身建议"先锁定行为测试再移动代码"，而当前仓库没有 Controller/Compose/通知生命周期的仪器测试，也没有真实 OpenCode 实例的契约测试。在缺少这些测试的情况下重构共享状态模型，风险高于收益。
- 本轮已做：把异步操作收敛到不可变 `OperationContext`、把降级状态显式化（`degraded`）、统一了两端工具投影与契约用例，为后续拆分打下可测试的边界。
- 建议：先补 Controller 层（A/B 服务器与多项目切换、延迟响应、局部端点失败）与仪器测试，再拆出"每服务器连接/快照仓库 + 不可变命令执行器 + 归一化事件模型 + UI 状态层"。

## 4. SSE 可靠性与事件游标语义（A04 的完整解）

- 现状：本轮把"队列溢出静默丢弃"改成"关闭流并触发重连 + 全量对账"，权限/终态不再无声消失。但 `Last-Event-ID` 游标的服务端重放语义、以及"只可合并能证明可覆盖的文本增量"仍未实现。
- 为什么没做：需要服务端支持事件 ID 重放的具体行为验证；客户端单方面假设重放可能补不齐或重复。属于跨端一致性设计。
- 建议：在目标实例上做事件 ID 重放/乱序/正常关闭验收后，再决定是依赖服务端重放还是客户端定期全量对账。

## 5. 无法在本机验收的边界（证据缺口，非代码缺陷）

- 真实 OpenCode Server 的 V1/V2 契约、真实 Android 设备 UI 与低内存回收、FCM 实际投递、Android 16 Live Updates 提升、HyperOS 超级岛、历史 APK 覆盖安装。
- 本轮已做静态与 JVM/MockWebServer 层面的验证；上述真机/真服务端验收需在目标环境完成，不能用本机结果宣称已通过。

## 6. 其他低优先级加固（未做，属可选项）

- GitHub Actions 目前用主版本 tag（`actions/checkout@v4` 等）。审计建议固定到 commit SHA；改动涉及核对每个 action 的 SHA，建议在独立 PR 中统一处理，避免与本轮功能修复混在一起。
- Android 依赖尚未启用 Gradle 依赖验证元数据（`dependencyVerification`）。启用需要为全部依赖生成/维护校验清单，属于独立的供应链改动。
- UI 仍然一次性加载整个会话历史做展示（`loadSession`）；网络层已限制单响应与分页总量。大历史的分页/懒加载属于界面层改动，建议在补了 Compose 仪器测试后再做。

## 已完成验证（本轮）

- `ANDROID_HOME=/home/lvziw/Android/Sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug`：通过（21 项单元测试）。
- `:app:assembleRelease`：通过，产物 `app-release.apk` 非 debuggable（未配置生产 keystore 时回退 debug 签名，正式发布由工作流阻断）。
- `:app:lintDebug`：BUILD SUCCESSFUL，0 Error。
- `cd companion && node --test`：8/8 通过。
- 新增回归测试覆盖：跨源重定向零请求（REST 与配对）、请求取消即时生效、v2 推送签名绑定目录/设备/新鲜度、companion 落盘失败拒绝、失败投递重试、设备注销、插件 toolKind 投影、契约 V2 tool 用例、离线/降级状态标志。
