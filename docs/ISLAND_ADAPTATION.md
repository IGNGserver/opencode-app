# 灵动岛（实时活动）适配矩阵

本文件记录 OpenCode Lagoon 的灵动岛 / 实时通知在各手机品牌上的接入方式、代码位置与验收状态。
所有通道共用同一份任务计数口径（`app/src/main/java/com/igng/opencode/lagoon/core/TaskSummary.kt`）：
`运行中 / 未读已完成 / 待回复 / 失败`，由 `LagoonController` 派生后统一发布一条 ongoing 通知。

## 通道矩阵

| 品牌 / 系统 | 官方名称 | 接入方式 | 代码位置 | 是否需要申请 / 合作 | 当前状态 |
|---|---|---|---|---|---|
| Android 16+（Pixel、三星、一加、Nothing 等遵循 AOSP 的 ROM） | Live Updates | 标准通知提升：`setRequestPromotedOngoing` + `setShortCriticalText` + ongoing | `system/IslandAdapters.kt` 的 `StandardLiveUpdateAdapter` | 否 | 已接入；待真机验收 |
| OPPO ColorOS 16 | 流体云 | 已声明**完整兼容 Android 16 Live Updates API**，随标准通道生效 | 同上 | 否 | 已随标准通道覆盖；待真机验收 |
| 小米 HyperOS 3 | 超级岛 / 焦点通知 | `notification.extras["miui.focus.param"]`（`param_v2`）+ `miui.focus.pics` | `system/IslandAdapters.kt` 的 `XiaomiIslandAdapter` | **是**，需向小米申请焦点通知权限 | 已接入；待权限与真机验收 |
| vivo / iQOO OriginOS | 原子岛 / 原子通知 | `notification.extras["notification.superx.*"]` | `system/IslandAdapters.kt` 的 `VivoIslandAdapter` | **是**，需在 vivo 开放平台申请原子岛接入权限（当前公测） | 已接入参数；待权限与真机验收 |
| OPPO ColorOS 15 | 流体云 | 端侧「意图共享」`ContentProviderClient`，或推送侧 `AndroidOppoIntelligentIntent` | `system/IslandAdapters.kt` 的 `OppoFluidCloudAdapter`（骨架，默认关闭） | **是**，需 OPPO 开放平台分配 `serviceId` 并确认需求 | 骨架就绪；ColorOS 16 用户不受影响（走标准通道） |
| 荣耀 MagicOS | 灵动胶囊 / YOYO 建议 | 荣耀开发者平台快捷服务 / 卡片模板，白名单制，非运行时通知 extras | `system/IslandAdapters.kt` 的 `HonorIslandAdapter`（骨架，默认关闭） | **是**，需企业认证 + 白名单 | 骨架就绪；待厂商对接协议 |
| 华为 HarmonyOS NEXT | 实况窗 | 鸿蒙原生 `LiveView`（ArkTS） | 不适用 | 需鸿蒙原生工程 | **超出范围**（Android APK 无法运行） |
| 华为 EMUI / HarmonyOS 4（Android 底座） | 实况窗 | 无公开第三方接入 API | 不适用 | — | 不可行 |
| 其他品牌 | 无专属岛 | 标准通知 / Android 16 Live Updates | 标准通道 | 否 | 已覆盖 |

## 代码结构

- `system/IslandAdapters.kt`：定义 `IslandAdapter` 接口与 `IslandRegistry`。约定按「能力探测 + 品牌兜底」路由，
  不支持时不产生任何副作用；`IslandRegistry.extendAll` 对每个适配器 `runCatching`，单个厂商异常不会中断通知。
  需厂商授权 / 合作的通道（荣耀、OPPO ColorOS 15）由**服务器资料内的持久化开关**控制
  （`ServerProfile.islandHonor` / `islandOppoFluidCloud`，在设置页「灵动岛适配」分区切换），**默认关闭**；
  关闭时对应适配器不产生副作用，开启后显示为「已就绪」。OPPO 流体云还需在申请到 `serviceId` 后
  填入 `OppoFluidCloud.serviceId`（代码级常量）。
- `system/TaskNotifications.kt`：`build` / `buildSummary` 构建通知后调用 `IslandRegistry.extendAll`；
  总览通知额外承载 Android 16 提升所需属性，并提供 `promotedNotificationSettingsIntent()` 跳转授权页。
- `ui/Screens.kt` 设置页「灵动岛适配」分区：列出各通道的 `已就绪 / 待授权 / 不支持` 与说明，并在需要时提供授权按钮。
- `core/TaskSummary.kt`：全服务器计数与统一文案（`text` / `shortText`）。

## 各通道接入步骤

### 小米 HyperOS 3 超级岛
1. 在小米开放平台申请「焦点通知」使用权限（邮件主题格式见官方 FAQ）。
2. 系统 `notification_focus_protocol >= 3` 时客户端自动写入 `miui.focus.param`；无需发版。
3. 在真机确认：息屏 AOD、状态栏胶囊、通知中心卡片、展开态大岛文案为任务计数。

### vivo 原子岛
1. 在 vivo 开放平台申请原子通知 / 原子岛接入权限（当前为公测）。
2. 依官方样式模板核对 `notification.superx.*` 字段；未获批时系统忽略这些 extras，`showNotify=true` 保证退化为普通通知。
3. **待确认项**：官方示例场景值为 `HEALTH_REGISTER`、`TAXI` 等垂域；当前实现使用 `TASK`，需在申请时与 vivo 确认是否可用。本地接口的 `operation` 采用 `1`（更新），若真机要求严格的 `0→1` 创建序列需调整。
4. 注意系统限制：单活动最多 10s/次刷新、超 2 小时不更新会被清除、最长显示 8 小时。

### Android 16 Live Updates / OPPO ColorOS 16
1. 无需厂商合作。
2. 首次使用需用户在系统设置中允许「实时更新 / 提升为常驻通知」；设置页提供跳转按钮。
3. 锁屏、状态栏 chip（`shortText`）与通知中心应显示任务总览。

### OPPO ColorOS 15 流体云（骨架就绪，待合作）
1. 在 OPPO 开放平台「接入准备」确认需求并获得 `serviceId`、`client_id` / `client_secret`。
2. 端侧按「意图共享」数据结构通过 `ContentProviderClient` 创建 / 更新 / 结束，`actionStatus = 0/1/2`。
3. 实现 `OppoFluidCloudTransport` 并注入 `OppoFluidCloud.transport`，在 `OppoFluidCloud.serviceId` 填入
   `serviceId`，再在设置页开启「OPPO 流体云（ColorOS 15）」开关即启用。
   `OppoFluidCloudAdapter` 已负责构建意图 JSON（`intentName` / `actionStatus` / `capsule` / `primary`），
   SDK 细节留在传输层，便于在无厂商环境下构建与测试。

### 荣耀灵动胶囊（骨架就绪，待合作）
1. 完成荣耀开发者企业认证，提交快捷服务 / 卡片模板申请并进入白名单。
2. 胶囊由 YOYO 建议服务呈现，非通行的通知 extras 通道；需与荣耀确认可用模板与下发方式。
3. 对接层就绪后在设置页开启「荣耀灵动胶囊」开关（持久化于服务器资料）；`HonorIslandAdapter` 不会写入
   `notification.extras`，避免干扰标准提示。

## 真机验收清单

1. 连接服务器并运行多个任务，确认灵动岛 / 系统通知 / 状态栏 chip 均显示一致的计数。
2. 触发权限确认与问题回答，确认「待回复」计数出现；处理完成后下降。
3. 打开某个已完成会话，确认「已完成」计数下降（已读语义）。
4. 触发失败任务，确认「失败」单独显示。
5. 分品牌验证：小米 HyperOS 3（超级岛）、vivo OriginOS（原子岛）、Pixel/三星/OPPO ColorOS 16（实时更新）。
6. 记录每个未通过项的服务端版本、系统版本与截图，回填本文件。

## 已知限制

- 小米、vivo、荣耀、OPPO ColorOS 15 均需向厂商申请权限或白名单，**应用侧无法自证可用**；未获批时退化为标准通知。
- 华为实况窗需鸿蒙原生应用，Android APK 不可用。
- 各厂商对刷新频率与展示时长有限制（vivo 见上；小米、OPPO 各有销卡/超时规则）。
- 本文件中的「已接入」仅表示代码路径就绪，**不代表已在实体设备上验收通过**。
- 荣耀与 OPPO ColorOS 15 目前仅为骨架，且在厂商权限 / 对接就绪前由服务器资料内的开关保持关闭，不产生任何行为。
