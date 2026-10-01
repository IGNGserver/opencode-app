# OpenCode Server 双协议契约对照

> 本文件是数据层"照抄官方"的可核查基线。`V1Contract.kt` / `V2Contract.kt` 中每一处字段读取
> 都应能在此表中找到对应 schema 字段。服务端升级导致字段变化时，以此表为比对起点。
>
> 权威来源：
> - V2 OpenAPI：`https://opencode.ai/v2/openapi.json`（OpenAPI 3.1）
> - V1 schema：`anomalyco/opencode@dev` `packages/schema/src/{project,v1/session,v1/permission,v1/question}.ts`
> - 端点表：`packages/sdk/js/src/gen/sdk.gen.ts`（V1）、`packages/sdk/js/src/v2/gen/sdk.gen.ts`（V2）

---

## 1. 端点对照（operationId → HTTP）

| 功能 | V1 | V2 |
|---|---|---|
| 健康 | `GET /global/health` | `GET /api/health` |
| 项目列表 | `GET /project` | `GET /api/project` |
| 当前 location | `GET /project/current` | `GET /api/location` |
| 会话列表 | `GET /session` | `GET /api/session` |
| 会话状态 | `GET /session/status` | `GET /api/session/active` |
| 会话详情 | `GET /session/{id}` | `GET /api/session/{id}` |
| 消息列表 | `GET /session/{id}/message` | `GET /api/session/{id}/message` |
| 建会话 | `POST /session` | `POST /api/session` |
| 改标题 | `PATCH /session/{id}` | `PATCH /api/session/{id}` |
| 删会话 | `DELETE /session/{id}` | `DELETE /api/session/{id}` |
| Fork | `POST /session/{id}/fork` | `POST /api/session/{id}/fork` |
| 中止 | `POST /session/{id}/abort` | `POST /api/session/{id}/interrupt` |
| 发消息 | `POST /session/{id}/prompt_async` | `POST /api/session/{id}/prompt` |
| 压缩上下文 | `POST /session/{id}/summarize` | `POST /api/session/{id}/compact` |
| Revert | `POST /session/{id}/revert` | `POST /api/session/{id}/revert/stage` |
| 权限回复 | `POST /session/{id}/permissions/{permissionID}` | `POST /api/session/{id}/permission/{requestID}/reply` |
| 事件流 | `GET /global/event` | `GET /api/event` |

**V1 无 REST 列表端点**：权限/问题无列表接口（仅 `permission.asked`/`question.asked` 事件）。
**V2 问答用 `form`**：`/api/session/{id}/form/{formID}/reply`，非 `question`。

---

## 2. 项目（Project）

| 字段 | V1 `Project.Info` | V2 `Project` | 统一模型 |
|---|---|---|---|
| id | `id` | `id` | `Project.id` |
| **路径标识** | **`worktree`** | **`canonical`** | `Project.directory` |
| 名称 | `name?` | `name?` | `Project.name` |
| 更新时间 | `time.updated` | `time.updated` | `Project.timeUpdated` |

> 历史 bug：V2 无 `worktree`/`directory` 字段，旧代码读两者得空串，再被
> `.filter { directory.isNotBlank() }` 全过滤 → 项目列表为空。

## 3. 会话（Session）

| 字段 | V1 `SessionInfo` | V2 `Session.Info` | 统一模型 |
|---|---|---|---|
| id | `id` | `id` | `Session.id` |
| **归属** | `projectID` | `projectID` | `Session.projectId` |
| 目录 | `directory` | `location.directory` | `Session.directory` |
| **标题** | `title`（必填） | `title`（**可选**） | `Session.title` |
| 父会话 | `parentID?` | `parentID?` | `Session.parentId` |
| 更新时间 | `time.updated` | `time.updated` | `Session.updated` |

> 归属统一用 `projectID`（两代都有），**禁止**用 `directory` 字符串匹配反推项目。
> 标题默认形如 `New session - <ISO>`，由 title agent 后台生成、经 `session.updated` 事件推送。

## 4. 消息（Message）

- **V1**：`{ info: Message, parts: Part[] }`；`Message = User | Assistant`（判别 `role`）。
  `Part` 12 种（判别 `type`）：text / reasoning / tool / file / patch / step-start / step-finish /
  snapshot / agent / subtask / retry / compaction。
  工具状态 `ToolState`（判别 `status`）：pending / running / **completed{output,title}** / error。
- **V2**：11 种 tagged union（判别顶层 `type`）：user / assistant / shell / compaction / system /
  synthetic / skill / agent-switched / model-selected / location-switched / idle。
  `assistant.content[]` = Text{type,text} | Reasoning | Tool{type,id,name,state,time}。
  工具状态 `ToolState`（判别 `status`）：streaming{input:string} / running / **completed{content:Tool.Content[]}** / error。
  `Tool.Content` = TextContent{type,text} | FileContent{type,uri,mime,name?}。

> 历史 bug：V2 工具输出在 `state.content[]`，旧代码读 `state.output`/`state.result`
> （V2 不存在该字段）→ 工具结果全空；且只处理 3/11 种消息类型，其余静默丢弃。

## 5. 写操作请求体（字段名错即 400，schema `additionalProperties:false`）

| 操作 | V1 body | V2 body |
|---|---|---|
| 发消息 | `{parts:[{type:"text",text}], agent?, model?}` | `{text}`（**顶层**，非 `{"prompt":{"text"}}`） |
| 权限回复 | `{reply:"once"\|"always"\|"reject", message?}` | `{decision:"once"\|"always"\|"reject"}` |
| 建会话 | `{title?}` | `{title?, location:{directory}}` |

## 6. 事件信封

- V1 `GET /global/event`：`{directory, workspace, payload:{type, properties}}`
- V2 `GET /api/event`：`{id, event, data}`，`data` 为 JSON 编码事件体
- 两代 `permission.*`/`question.*` 变体（`permission.v2.*`、`form.*`）在 `toServerEvent` 归一为统一事件名。

---

## 已知简化与边界（诚实声明）

1. **事件增量更新**：官方客户端按 `message.part.updated`（V1）/ `session.next.*`（V2）做细粒度
   reconcile。本实现对消息内容采用"事件触发去抖刷新该会话"，标题/归属用单条 reconcile。
   这是**刻意的正确性优先**取舍（移动端频繁重连、消息量小，全量刷新代价低且绝不丢内容），
   而非遗漏。若日后要极致性能，可按 V2 `session.next.*` 补增量。
2. **V2 `form` 只做最小映射**：V2 问答形态 `Form.*` 字段较复杂，当前映射为统一 `QuestionRequest`
   的核心字段；复杂表单字段（多类型 fields）尚未完整建模。
3. **V1 问答无 REST 回复端点**（官方 SDK 端点表已核对）：V1 问题仅事件推送，REST 回复路径
   `/question/{id}/reply` 属遗留尝试，实测可能 404，届时问题无法通过 REST 作答。
