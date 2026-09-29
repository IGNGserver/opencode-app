# OpenCode Mobile

原生 Android OpenCode 控制端。Kotlin + Jetpack Compose 界面采用 Fluent 2 的信息层级、蓝色强调、浅色中性表面和紧凑的任务卡片。App 直接访问用户自己的 OpenCode Server，聊天内容不经过伴随服务。

## 功能

- 多服务器连接、Basic Auth/官方 pair 链接、V1/V2 健康检查、Android Keystore 保护的密码与会话 Cookie、离线缓存、SSE 自动重连
- 项目与会话、异步任务、Agent/Model、斜杠命令、文本/Reasoning/Tool 消息、停止与权限/问题处理
- Todo、子会话、改动、文件浏览与搜索；V1 另支持重命名、删除、Fork、Share，V1/V2 支持 Summarize、Revert
- 运行中、待处理、完成通知；Android Live Updates 请求和小米超级岛参数适配
- 可选的 OpenCode 插件 + FCM 伴随服务，用于 App 不在前台时推送状态

## 本地构建

需要 JDK 17+、Android SDK Platform 36。执行：

```sh
ANDROID_HOME=/path/to/Android/Sdk ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

本仓库把 Gradle build 输出放在 `/tmp/opencode-mobile-gradle/`，每个 checkout 使用独立路径 `/tmp/opencode-mobile-gradle/<checkout-hash>/app/outputs/apk/debug/app-debug.apk`。

在 App 中添加可访问的 OpenCode Server URL。服务端需启用 Basic Auth；也可以直接粘贴 `opencode pair` 输出的官方链接，App 会访问一次链接、保存返回的会话 Cookie，再用该 Cookie 连接 API。建议通过 HTTPS 或可信 VPN 访问。使用局域网 HTTP 时，必须在该服务器资料中明确开启明文连接，授权会随资料保存。

## 可靠推送配置

推送依赖外部 Firebase 项目和部署配置。将 Firebase Android 应用的 `google-services.json` 放入 `app/`；此文件已被 Git 忽略，构建时会自动启用 Google Services 插件。FCM 需要兼容的 Google Play 服务设备。设备无 Play 服务时，前台 SSE 与运行中通知仍可用，可靠后台推送需要另配 OEM 渠道。

在 OpenCode 主机运行 `companion/` 中的服务：

1. 安装 Node 20+ 和依赖：`cd companion && npm ci`。
2. 以非仓库路径配置 `GOOGLE_APPLICATION_CREDENTIALS`、`FIREBASE_PROJECT_ID`、`OPENCODE_SERVER_PASSWORD`、`OPENCODE_MOBILE_PLUGIN_SECRET`、独立且不同的 `OPENCODE_MOBILE_PUSH_SECRET`、`OPENCODE_MOBILE_SERVER_KEY`；伴随服务和 OpenCode 使用同一组 Basic Auth 配置，默认用户名为 `opencode`，自定义用户名时同时设置 `OPENCODE_SERVER_USERNAME`。两个进程的 `OPENCODE_MOBILE_SERVER_KEY` 都必须设置为 App 中填写的可访问服务器地址。伴随服务默认仅监听 `127.0.0.1:4344`，对手机公开时需放在 HTTPS 反向代理后。
3. `node companion/src/server.mjs` 启动伴随服务。
4. 将 `companion/opencode-mobile.plugin.js` 复制到 OpenCode 配置的 `plugins/`，给 OpenCode 进程设置 `OPENCODE_MOBILE_COMPANION_URL`、相同的 `OPENCODE_MOBILE_PLUGIN_SECRET` 和 `OPENCODE_MOBILE_SERVER_KEY`。`OPENCODE_URL` 仍填写伴随服务所在主机访问 OpenCode 的本地地址（默认 `http://127.0.0.1:4096`）。插件仅上报事件类型、会话 ID、目录及工具名称；伴随服务查询会话标题后发送 FCM 数据消息，并按服务器地址隔离设备。
5. 在 App 服务器资料中填入伴随服务 HTTPS URL、相同的 `OPENCODE_MOBILE_PUSH_SECRET`（只用于校验后台推送，不向手机分发插件入站密钥），并从设置页注册设备。

伴随服务将设备 ID、FCM Token、任务状态、递增序号和投递队列原子保存于 `~/.local/state/opencode-mobile/devices.json`，权限为 0600。请将此状态目录纳入服务器的安全备份。Firebase 服务账号只放在服务器安全目录，不放进仓库或 App。

### 推送升级与恢复

- App、插件和 companion 应一起升级。App 仅接受签名 v3，校验设备、24 小时时效与每会话递增序号；不接受旧签名或空密钥。旧明文密钥/不可解密密钥需要重新填写。
- 插件在 `~/.local/state/opencode-mobile/` 保存有界待发事件队列；可通过 `OPENCODE_MOBILE_PLUGIN_QUEUE_DIR` 指定目录。每个服务器与项目目录只运行一个插件写入进程，companion 状态文件也只由一个实例写入。重启会自动恢复，勿删除状态目录来“修复”积压。
- 事件先落盘才返回成功。插件最多缓存 5000 条、companion 默认最多保留 500 个待投递项；满时明确拒绝并保留既有队列。过期或不可投递消息进入有界死信记录，`/health` 提供 pending/deadLetters 数量。
- 删除资料、关闭通知或修改伴随服务配置会先记录加密注销任务；离线时保留，App 进程存活期间重试，下次启动继续。系统杀死 App 后不会独立唤醒重试；服务器注销成功前仍可能投递，但本地已删除/禁用的资料不展示通知。
- 同一设备同一会话只保留最新待发状态，旧请求重试不会倒退新状态；已进入 FCM 的消息无法撤回，由 App 持久化序号拒绝晚到旧消息。恢复服务器旧备份可能回退序号，恢复后应重新登记为新的 App 资料，或保留最新 registry 序号。

### 当前连接与授权

本地前台服务只监控当前服务器。切换服务器会明确停止旧服务器的本地监控，远端任务继续；多个服务器的后台通知依赖各自 companion。离线缓存与局部失败数据均标记为过期，不能用于发送操作。

当前 V2（`/api/info`）按发布契约发送消息、回复表单和权限。旧 V2（`/api/health`）保留原请求格式，不在写请求失败后盲目重发不同请求体。V1/旧 V2 不提供未经确认的长期授权；当前 V2 的“始终允许”需确认项目范围，聊天菜单可查看和撤销已保存权限。

## 验收边界

Android 构建与单元测试只能证明代码可编译和有限的 API/状态逻辑。真实 OpenCode 版本、SSE 断线恢复、Android 16 Live Updates 提升、小米超级岛授权与展示、FCM 关 App 投递都需要在目标服务器和设备上验收。Android 15+ 的 dataSync 前台服务有运行时长限制，因此长期可靠通知依赖服务器插件与推送。
