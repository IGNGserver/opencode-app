# OpenCode Mobile 数据层完全重写 · 开发计划

> 状态：**已实施**（Phase 0–5 完成，双协议 V1+V2 均支持）。
> 验证：`:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL；`companion node --test` 3 pass；单测 14/14 全绿。
> 字段级"照抄"可核查基线见 **`docs/contract/CONTRACT.md`**。
> 基线：`origin/main`（commit b8595bc）→ 分支 `refactor/dual-protocol-contract`
> 契约来源：`https://opencode.ai/v2/openapi.json`（OpenAPI 3.1，113 路径 / 245 schema）+ 官方客户端源码
> `anomalyco/opencode@dev`（`packages/sdk/js`、`packages/tui/src/context/sync.tsx`、`packages/schema/src/*`）

---

## 0. 需要你先拍板的一个前提

**你实际连接的 OpenCode Server 是哪套 API？**

| 选项 | 判别方法 | 影响 |
|---|---|---|
| V2（`/api/*`） | `curl <server>/api/health` 返回 200 | 只实现 `/api/*` 一套，最贴合官方现状 |
| V1（`/session`、`/global/*`） | `curl <server>/global/health` 返回 200 | 按 V1 契约实现，消息模型是 `{info, parts}` |
| 两者都要 | 两个都 200 | 需按 spec 生成两套模型 + 运行时能力探测 |

本计划**以 V2 为基准**撰写（当前官方契约），但核心机制是"从服务端自己的 OpenAPI 文档生成模型"，
所以无论你跑哪套，同一套方法论都成立，只是生成的契约不同。**若你的是 V1，请告知，字段对照表需换用 V1 spec。**

---

## 1. 诊断：四个症状的确切根因

结论先行：**这不是零散 bug，而是数据层从头到尾在"猜字段名"**。现有 `OpenCodeApi.kt` +
`Models.kt` 里的 JSON 解析字段，绝大多数在真实契约里**不存在或名字不对**；更糟的是
`OpenCodeApiTest.kt` 把这些猜测固化成了断言，所以测试全绿、线上全挂。

### 症状 1 —— 项目列表加载不出来（必现）

**根因 A：Project 模型字段完全猜错。**
V2 `Project` schema（`components.schemas.Project`）的字段是：

```
id, canonical, vcs?, name?, icon?, commands?, time, sandboxes
required: [id, canonical, time, sandboxes]
```

**注意：没有 `worktree`，也没有 `directory`。** 路径标识字段叫 `canonical`。
而 `Models.kt:101` 的 `toProject()`：

```kotlin
val directory = str("worktree").ifBlank { str("directory") }   // 两个字段都不存在 → ""
return Project(str("id"), directory, ...)                       // directory = ""
```

紧接着 `OpenCodeApi.kt:178` 有 `.filter { it.directory.isNotBlank() }` —— **把所有项目全过滤光**。
项目列表必然为空。

**根因 B：V2 分支连端点都猜错了。**
`OpenCodeApi.kt:180` 的 V2 分支调 `GET /api/location` 当项目列表。真实端点是
**`GET /api/project`**（`operationId: project.list`）。`/api/location` 是"解析当前 location"，
返回单个 `{location, data}`，不是项目数组。

**根因 C：连锁塌陷。**
`MobileController.kt:131` `projects.map { client.sessions(it.directory) }` —— projects 为空 →
sessions 为空 → 会话列表、消息、任务状态全部跟着空白。这就是"一堆问题"的共同上游。

### 症状 2 —— 对话归属错误

**根因：归属判定用了错误的关联键。**
V2 `Session.Info` 的归属字段是 `projectID`（required）+ `location: Location.PublicRef{directory}`。
正确关联是 **`session.projectID == project.id`**。

而 `MobileController.kt:255`：

```kotlin
val project = mutable.value.projects.firstOrNull { it.directory == session.directory }
```

拿 `location.directory` 做**字符串精确匹配**反推项目 —— 路径规范化（尾斜杠、符号链接、
`~` vs 绝对路径、大小写）任一差异就匹配失败，落到 `project?.id ?: it.projectId` 兜底 → 归属错乱。

同时 `OpenCodeApi.kt:189` 的会话查询用 `?directory=&order=desc` 硬拼，
真实查询参数是 `project / directory / subpath / parentID / search / limit / order / cursor`，
且规范明确 **`cursor` 不可与 `order` 同时使用**（"Do not combine with order"）——
现在 `dataObjects()` 里 `order=desc` 全程带着翻页 → 分页重复/漏项。

### 症状 3 —— 对话标题加载不出来 / 找错了

**根因 A：把可选字段当必有字段。**
V2 `Session.Info` 的 `title` **不在 required 里**（required 只有 id/projectID/cost/tokens/time/location）。
缺省时是空。而 `Models.kt:106` 直接 `str("title").ifBlank { "未命名会话" }`。

**根因 B：不了解官方标题生成时序。**
官方标题流程（`packages/opencode/src/session/prompt.ts` 的 `SessionPrompt.ensureTitle`）：
- 会话创建时标题是默认值 `"New session - <ISO时间>"`（子会话 `"Child session - <ISO>"`）；
- agent loop 第 1 步后台调 title agent 生成，截断 100 字符；
- 结果通过 **`session.updated` 事件**推送，**没有独立的 title 事件**。

现有 `MobileController.kt:221` 对 `session.updated` 的处理是 `reload()` → `loadAll()` →
`sessions = sessionResults.flatMap{...}.distinctBy{it.id}` **整体覆盖**。若事件时序导致重拉
拿到旧标题，或 distinctBy 保留了先到的旧记录，标题就"找错了"。
正确做法是按 `sessionID` **reconcile 单条**（官方 `sync.tsx` 即如此），不是整表刷新。

**根因 C：V1/V2 消息格式互串。**
`Models.kt:110` `if (info.length() == 0 && str("type").isNotBlank()) return toV2Message()` ——
用"info 是否为空"猜格式，两个格式字段互相覆盖时 role/title 会错位。

### 症状 4 —— 对话内容显示不全

**根因 A：只认 3 种消息类型，其余 8 种全丢。**
V2 `Session.Message.Info` 是 **11 种 tagged union**，判别字段是顶层 `type`：
`agent-switched / model-selected / location-switched / user / synthetic / system / skill /
shell / assistant / compaction / idle`。

`Models.kt:135` `toV2Message()` 只处理 `user / assistant / shell`，其余走
`else -> listOfNotNull(str("text")...)` —— 而这些类型**没有 text 字段** → 返回空 → 消息直接消失。

**根因 B：工具输出读的是不存在的字段（致命）。**
V2 `ToolState` 是 `streaming / running / completed / error` 四态，输出在
**`ToolState.Completed.content: Tool.Content[]`**，其中 `Tool.Content = Tool.TextContent{type:"text",text} |
Tool.FileContent{type:"file",uri,mime,name}`。

而 `Models.kt:120` 读的是 `state.valueText("output").ifBlank { state.valueText("result") }` ——
**`output` 和 `result` 两个字段在 V2 里根本不存在** → 所有工具结果渲染为空 → "内容显示不全"。

**根因 C：input 类型随状态变化，被当成恒定 object。**
`ToolState.Streaming.input` 是 **string**（原始流式文本），
`Running/Completed/Error.input` 是 **object**。现在统一 `valueText("input")` 语义混用。

**根因 D：消息分页不完整。**
`GET /api/session/{id}/message` 返回 `{data, cursor}`，现有 `dataObjects()` 带着 `order` 翻页
（违反 cursor 约束）→ 只拿到部分消息。

### 附带查实：两个写操作请求体也猜错了（必被服务端拒绝）

V2 schema 均为 `additionalProperties: false`（严格校验），字段名错即 400：

| 操作 | 现有发送 | 正确契约 |
|---|---|---|
| 发消息 `POST /api/session/{id}/prompt` | `{"prompt":{"text":...}}` | `{"text": ...}`（`text` 是**顶层必填**，另有 `files/agents/skills/metadata/delivery/resume`） |
| 权限回复 `POST .../permission/{id}/reply` | `{"reply":"once"}` | `{"decision":"once"\|"always"\|"reject", "message"?}` |

> 即"发消息"和"权限确认"这两个核心动作，在 V2 上当前也是坏的。

---

## 2. 重写的核心原则：从"猜字段"改为"生成契约"

> **一句话：本应用不是"调用 OpenCode API 的 App"，而是"官方 OpenCode 客户端换了个 Android 壳"。
> 判定标准只有一个 —— 数据层每一行解析代码都能指到 OpenAPI spec 里的一个具体字段。**

三条铁律：

1. **禁止手写 JSON 字段名。** 所有 DTO 字段必须由服务端自己的 OpenAPI 文档生成。
   官方 SDK 就是这么做的（`packages/sdk/js/script/build.ts`：
   `bun dev generate > openapi.json` → `@hey-api/openapi-ts` 生成 → 打包）。
2. **禁止 `ifBlank{...}` 式猜字段兜底。** 契约里没有的字段就不要读；
   可选字段用可空类型表达，不用默认值掩盖。
3. **禁止用测试固化猜测。** 单元测试必须**从同一份 spec 派生**（契约测试），
   而不是把当前实现的输出抄成断言。

---

## 3. 目标架构

```
core/
├── contract/          ← 新增：由 OpenAPI 生成的数据类（kotlinx.serialization）
│   ├── V2Schemas.kt       Project / Session.Info / Session.Message.* / ToolState.* / Permission.*
│   └── V2Events.kt        事件 payload 类型
├── transport/          ← 新增：纯 HTTP/SSE，不含业务语义
│   ├── Http.kt            认证、directory 头/参数改写、错误映射
│   └── Sse.kt             事件订阅 + 断线重连 + Last-Event-ID
├── OpenCodeClient.kt  ← 替代 OpenCodeApi：端点一一对应 spec operationId
├── SessionStore.kt    ← 替代 MobileController 的状态部分：官方 reconcile 模型
└── Models.kt          ← 只保留 UI 视图模型（不含 JSON 解析）
```

关键点：
- **contract/ 是生成物**，构建时或运行时从 `<server>/openapi.json` 产出，人工不改。
- **SessionStore 采用官方 reconcile 语义**（`packages/tui/src/context/sync.tsx`）：
  - 列表：按 `time.updated` 有序数组，二分查找定位后**单条替换**，不整表覆盖；
  - 消息：`{messageID → parts}` 二级缓存，事件到达按 id reconcile/insert；
  - 打开会话：**并行** `GET /session/{id}` + `GET .../message` + diff/todo，合并时
    "事件已 touch 过的以本地为准，空文本不覆盖本地"；
  - 标题：只响应 `session.updated` 单条 reconcile。

---

## 4. 分阶段实施计划

### Phase 0 —— 契约冻结与能力探测（0.5 天）
- 从目标服务器抓 `/openapi.json` 存入 `docs/contract/`（版本化留档）。
- 写 `ContractProbe`：启动时探测 `/api/health` vs `/global/health`，确定协议代际并缓存。
- **产出**：一份 `docs/contract/v2-openapi.json` + 探测结果。
- **验收**：探测结果与你实际服务端一致（这是唯一需要你配合确认的点）。

### Phase 1 —— 生成 contract/ 数据模型（1 天）
- 引入 kotlinx.serialization + JSON，按 spec 生成全部用到的 schema：
  `Project`、`Session.Info`、`Session.Message.Info`（11 种 union）、
  `ToolState.*`、`Tool.Content`、`Permission.Request/Reply`、事件 payload。
- union 用 `@JsonClassDiscriminator("type")` / `status` 严格映射。
- **验收**：用 spec 里的 example 或真实响应样本做**反序列化契约测试**，全部通过；
  断言"未知 type"必须显式失败而非静默丢弃。

### Phase 2 —— transport/ + OpenCodeClient（1.5 天）
- 端点严格按 operationId 建立一一对应方法（见 §5 对照表）。
- 分页统一遵守 `cursor` 约束：**带 cursor 的请求不带 order**。
- 写操作请求体严格按 spec（`session.prompt` 顶层 `text`；权限回复 `decision`）。
- **验收**：MockWebServer 契约测试 —— 每个方法断言"请求路径 + 请求体字段名 + 查询参数"
  与 spec 完全一致。**不再断言"当前实现的输出"，改为断言"spec 的输入契约"。**

### Phase 3 —— SessionStore reconcile 状态层（1.5 天）
- 实现官方的列表/消息/标题 reconcile 语义（§3）。
- SSE：`GET /api/event`（V2 native）事件分发，按 `session.updated` 单条更新标题与归属。
- **验收**：构造事件序列单测 —— `session.updated` 只改一条、`message.part.updated` 只改一个 part、
  分页补齐不丢消息。

### Phase 4 —— 四症状专项修复 + UI 适配（1 天）
- 项目列表：改用 `GET /api/project`，路径用 `canonical`，归属用 `projectID`。
- 消息渲染：补齐 11 种消息类型 + `Tool.Content[]` 展平（text/file）。
- 标题：默认标题识别 + `session.updated` 增量更新。
- **验收**：逐条对照 §1 症状，每个症状有一个复现用例转绿。

### Phase 5 —— 真机验收与清理（0.5 天）
- 按 `docs/ARCHITECTURE.md` 的"必须在真实环境检查"清单逐项过。
- 删除 `OpenCodeApi.kt` 的 V1/V2 猜测分支、`toV2Message()`、错误的单测。
- **验收**：`ANDROID_HOME=... ./gradlew :app:testDebugUnitTest :app:assembleDebug` 通过 +
  真机四症状消失。

> 合计约 6 人天。各 Phase 独立可验收，可按 PR 拆分（collaborative 仓库走 PR + Squash）。

---

## 5. 接口对照表（旧猜测 → 官方契约）

| 功能 | 现有实现（猜测） | 官方契约（V2） |
|---|---|---|
| 健康检查 | `GET /api/health`（V2 分支） | `GET /api/health` → `ServiceHealth` ✅ |
| **项目列表** | `GET /api/location` ❌ | `GET /api/project` → `Project[]` |
| **项目路径字段** | `worktree` → `directory` ❌ | `canonical` |
| 当前 location | — | `GET /api/location` → `{location:{directory,project{id,directory,canonical}},data}` |
| **会话列表** | `?directory=&order=desc` + cursor 混用 ❌ | `?project=<id>&parentID=null&order=desc&limit=&cursor=` |
| **会话归属** | `directory` 字符串匹配 ❌ | `session.projectID == project.id` |
| **会话标题** | `str("title")` 当必有 ❌ | `title` 可选；默认值 + `session.updated` 更新 |
| 会话创建 | `{"location":{"directory":...}}` ✅ | `POST /api/session` `{id?,title?,agent?,model?,location?,...}` |
| **消息列表** | `?order=asc` + dropAfterFirst ❌ | `?limit=&order=&cursor=` → `{data,cursor}` |
| **消息模型** | 3 种 ❌ | 11 种 tagged union（`type` 判别） |
| **工具输出** | `state.output`/`state.result` ❌ | `ToolState.Completed.content: Tool.Content[]` |
| **发消息** | `{"prompt":{"text":...}}` ❌ | `{"text":...}`（顶层必填） |
| **权限回复** | `{"reply":...}` ❌ | `{"decision":"once"\|"always"\|"reject"}` |
| 中断 | `POST .../interrupt` ✅ | 同 |
| 事件流 | `GET /api/event`，按 `payload` 解析 | `GET /api/event`（data 为 JSON 编码事件） |

图例：✅ 已正确 / ❌ 需修正

---

## 6. 验证策略：证明"照抄"而非"猜"

1. **契约测试**：MockWebServer 的每个断言都标注 spec 来源（`operationId` + schema 字段路径），
   评审时可逐条核对。
2. **样本回归**：从真实服务端抓 3~5 个真实响应（项目列表、会话列表、含工具的消息、
   含 compaction 的消息）存为 golden file，反序列化 + 渲染快照测试。
3. **未知字段策略**：遇到契约外的 `type`/`status` 必须打日志并显式降级，**禁止静默丢弃**
   （这是"内容显示不全"复发的唯一通道）。
4. **Validation 命令**（仓库 AGENTS.md 规定）：
   `ANDROID_HOME=/home/lvziw/Android/Sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug`
   + `cd companion && node --test`

---

## 7. 风险与回滚

| 风险 | 缓解 |
|---|---|
| 目标服务端是 V1 而非 V2 | Phase 0 探测先行；方法论不变，换 V1 spec 生成契约 |
| 服务端版本漂移导致字段变化 | contract/ 版本化留档 + 未知字段显式降级，不静默吞 |
| codegen 引入构建复杂度 | 若不愿上生成器，可退化为"手写但严格锁死 spec 字段 + 契约测试"，仍满足铁律 1 的可验证性 |
| 重写期间功能回退 | 保留旧 `core/` 直至 Phase 4 验收通过再删除；UI 层接口不变，可随时切回 |
| companion 插件事件类型不匹配 | 插件已含 `permission.v2.*`/`question.v2.*` 映射，随 Phase 3 一并核对 |

**回滚点**：每个 Phase 一个独立 PR/commit，任一阶段失败 `git revert` 单个提交即可，
不影响已验收阶段。

---

## 附：本计划的证据出处

- V2 OpenAPI：`https://opencode.ai/v2/openapi.json`（已存 `/tmp/ocresearch/v2-openapi.json`）
- 官方 SDK 生成流程：`anomalyco/opencode@dev` `packages/sdk/js/script/build.ts`
- 官方客户端加载/事件语义：`packages/tui/src/context/sync.tsx`、`context/data.tsx`
- Schema 定义：`packages/schema/src/{project,session,session-message,location}.ts`
- 现有缺陷代码：`app/src/main/java/com/igng/opencode/mobile/core/{OpenCodeApi,Models,MobileController}.kt`
