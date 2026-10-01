# OpenCode Lagoon

原生 Android OpenCode 控制端。Kotlin + Jetpack Compose 界面采用小米 HyperOS / MIUIX 设计规范，引入连续曲率 Squircle、科技蓝语义调色板与 Liquid Glass 液态玻璃交互层。App 直接访问用户自己的 OpenCode Server，聊天内容不经过伴随服务。

## 功能

- 多服务器连接、Basic Auth/官方 pair 链接、V1/V2 健康检查、Android Keystore 保护的密码与会话 Cookie、离线缓存、SSE 自动重连
- 项目与会话、异步任务、Agent/Model、斜杠命令、文本/Reasoning/Tool 消息、停止与权限/问题处理
- Todo、子会话、改动、文件浏览与搜索；V1 另支持重命名、删除、Fork、Share，V1/V2 支持 Summarize、Revert
- 全服务器任务总览灵动岛：实时显示「xx 个运行中、xx 个未读已完成」，有待处理时追加「xx 个待回复」、有失败时追加「xx 个失败」；同一口径按设备能力分发到各厂商灵动岛（小米超级岛、vivo 原子岛、Android 16 Live Updates / OPPO ColorOS 16 流体云），详见 `docs/ISLAND_ADAPTATION.md`
- 运行中、待处理、完成通知；任务总览仅统计未读结果，用户打开会话后即视为已读；配置 companion + FCM 后，App 进程被杀时仍可由后台推送重建并刷新总览计数
- 可选的 OpenCode 插件 + FCM 伴随服务，用于 App 不在前台时推送状态

## 本地构建

需要 JDK 17+、Android SDK Platform 36。执行：

```sh
ANDROID_HOME=/path/to/Android/Sdk ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

本仓库把 Gradle build 输出放在 `/tmp/opencode-lagoon-gradle/`，每个 checkout 使用独立路径 `/tmp/opencode-lagoon-gradle/<checkout-hash>/app/outputs/apk/debug/app-debug.apk`。

在 App 中添加可访问的 OpenCode Server URL。服务端需启用 Basic Auth；也可以直接粘贴 `opencode pair` 输出的官方链接，App 会访问一次链接、保存返回的会话 Cookie，再用该 Cookie 连接 API。建议通过 HTTPS 或可信 VPN 访问。使用局域网 HTTP 时，必须在该服务器资料中明确开启明文连接，授权会随资料保存。

## 可靠推送配置

推送依赖外部 Firebase 项目和部署配置。将 Firebase Android 应用的 `google-services.json` 放入 `app/`；此文件已被 Git 忽略，构建时会自动启用 Google Services 插件。FCM 需要兼容的 Google Play 服务设备。设备无 Play 服务时，前台 SSE 与运行中通知仍可用，可靠后台推送需要另配 OEM 渠道。

在 OpenCode 主机运行 `companion/` 中的服务：

1. 安装 Node 20+ 和依赖：`cd companion && npm ci`。
2. 以非仓库路径配置 `GOOGLE_APPLICATION_CREDENTIALS`、`FIREBASE_PROJECT_ID`、`OPENCODE_SERVER_PASSWORD`、`OPENCODE_LAGOON_PLUGIN_SECRET`、独立且不同的 `OPENCODE_LAGOON_PUSH_SECRET`、`OPENCODE_LAGOON_SERVER_KEY`；伴随服务和 OpenCode 使用同一组 Basic Auth 配置，默认用户名为 `opencode`，自定义用户名时同时设置 `OPENCODE_SERVER_USERNAME`。两个进程的 `OPENCODE_LAGOON_SERVER_KEY` 都必须设置为 App 中填写的可访问服务器地址。伴随服务默认仅监听 `127.0.0.1:4344`，对手机公开时需放在 HTTPS 反向代理后。
3. `node companion/src/server.mjs` 启动伴随服务。
4. 将 `companion/opencode-lagoon.plugin.js` 复制到 OpenCode 配置的 `plugins/`，给 OpenCode 进程设置 `OPENCODE_LAGOON_COMPANION_URL`、相同的 `OPENCODE_LAGOON_PLUGIN_SECRET` 和 `OPENCODE_LAGOON_SERVER_KEY`。`OPENCODE_URL` 仍填写伴随服务所在主机访问 OpenCode 的本地地址（默认 `http://127.0.0.1:4096`）。插件仅上报事件类型、会话 ID、目录及工具名称；伴随服务查询会话标题后发送 FCM 数据消息，并按服务器地址隔离设备。
5. 在 App 服务器资料中填入伴随服务 HTTPS URL、相同的 `OPENCODE_LAGOON_PUSH_SECRET`（只用于校验后台推送，不向手机分发插件入站密钥），并从设置页注册设备。

伴随服务将设备 ID、FCM Token、任务状态、递增序号和投递队列原子保存于 `~/.local/state/opencode-lagoon/devices.json`，权限为 0600。请将此状态目录纳入服务器的安全备份。Firebase 服务账号只放在服务器安全目录，不放进仓库或 App。

### 推送升级与恢复

- App、插件和 companion 应一起升级。App 仅接受签名 v3，校验设备、24 小时时效与每会话递增序号；不接受旧签名或空密钥。旧明文密钥/不可解密密钥需要重新填写。
- 插件在 `~/.local/state/opencode-lagoon/` 保存有界待发事件队列；可通过 `OPENCODE_LAGOON_PLUGIN_QUEUE_DIR` 指定目录。每个服务器与项目目录只运行一个插件写入进程，companion 状态文件也只由一个实例写入。重启会自动恢复，勿删除状态目录来“修复”积压。
- 事件先落盘才返回成功。插件最多缓存 5000 条、companion 默认最多保留 500 个待投递项；满时明确拒绝并保留既有队列。过期或不可投递消息进入有界死信记录，`/health` 提供 pending/deadLetters 数量。
- 删除资料、关闭通知或修改伴随服务配置会先记录加密注销任务；离线时保留，App 进程存活期间重试，下次启动继续。系统杀死 App 后不会独立唤醒重试；服务器注销成功前仍可能投递，但本地已删除/禁用的资料不展示通知。
- 同一设备同一会话只保留最新待发状态，旧请求重试不会倒退新状态；已进入 FCM 的消息无法撤回，由 App 持久化序号拒绝晚到旧消息。恢复服务器旧备份可能回退序号，恢复后应重新登记为新的 App 资料，或保留最新 registry 序号。

### 当前连接与授权

本地前台服务只监控当前服务器。切换服务器会明确停止旧服务器的本地监控，远端任务继续；多个服务器的后台通知依赖各自 companion。离线缓存与局部失败数据均标记为过期，不能用于发送操作。

当前 V2（`/api/info`）按发布契约发送消息、回复表单和权限。旧 V2（`/api/health`）保留原请求格式，不在写请求失败后盲目重发不同请求体。V1/旧 V2 不提供未经确认的长期授权；当前 V2 的“始终允许”需确认项目范围，聊天菜单可查看和撤销已保存权限。

## 更名与迁移

项目由 “OpenCode Mobile” 更名为 “OpenCode Lagoon”（与 GitHub 仓库名 `IGNGserver/opencode-lagoon` 对齐）。以下标识随之改变，跨版本部署需要按此迁移：

- 显示名与工程名：应用显示名、`rootProject.name`、发布工作流名与安装包文件名（`OpenCode-Lagoon-<version>.apk`）、Gradle 输出目录 `/tmp/opencode-lagoon-gradle/`。
- 应用标识：`applicationId` 与 Kotlin 包改为 `com.igng.opencode.lagoon`，与旧的 `com.igng.opencode.mobile` 在 Android 上是两个不同应用。存量安装不会覆盖升级，需要卸载旧包再装新包；`KeystoreCipher` 别名改为 `opencode-lagoon-*`，旧包保存的密码、Cookie 与离线缓存本来就不跨 UID，新包一律重新录入。
- 深链接：scheme 改为 `opencode-lagoon://`。旧版通知与旧 scheme 链接不会被新包接住，升级后由新包重新发出通知。
- Firebase：`google-services.json` 按包名绑定，需要在 Firebase 项目里为 `com.igng.opencode.lagoon` 重新添加 Android 应用并放置新配置文件；沿用旧文件会让 Google Services 插件因包名不匹配而构建失败。
- companion 与插件：文件名 `opencode-lagoon.plugin.js`、环境变量前缀 `OPENCODE_LAGOON_*`、入站头 `x-opencode-lagoon-secret`、状态目录 `~/.local/state/opencode-lagoon/`、npm 包名 `opencode-lagoon-companion`。已有部署请 `mv` 旧状态目录到新路径以保留已注册设备与待发队列，并同步更新两处进程的环境变量与 OpenCode `plugins/` 下的插件文件。
- CI：仓库 Secrets 由 `OPENCODE_MOBILE_*` 改名为 `OPENCODE_LAGOON_*`，keystore 别名与主文件名改名但证书不变（见 [`docs/ANDROID_SIGNING.md`](docs/ANDROID_SIGNING.md)）。旧的 `OPENCODE_MOBILE_*` Secrets 已随更名进入 `main` 删除；若需要回到更名前的发布工作流，只能按 `docs/ANDROID_SIGNING.md` 从本机 keystore 与口令文件重新写入。
- `docs/releases/` 下的历史发布说明保持原样，它们记录的是当时实际发布的名称。

## 验收边界

Android 构建与单元测试只能证明代码可编译和有限的 API/状态逻辑。真实 OpenCode 版本、SSE 断线恢复、Android 16 Live Updates 提升、小米超级岛授权与展示、FCM 关 App 投递都需要在目标服务器和设备上验收。Android 15+ 的 dataSync 前台服务有运行时长限制，因此长期可靠通知依赖服务器插件与推送。
