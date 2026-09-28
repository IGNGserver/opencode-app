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

本仓库把 Gradle build 输出放在 `/tmp/opencode-mobile-gradle/`，APK 路径为 `/tmp/opencode-mobile-gradle/app/outputs/apk/debug/app-debug.apk`。

在 App 中添加可访问的 OpenCode Server URL。服务端需启用 Basic Auth；也可以直接粘贴 `opencode pair` 输出的官方链接，App 会访问一次链接、保存返回的会话 Cookie，再用该 Cookie 连接 API。建议通过 HTTPS 或可信 VPN 访问。使用局域网 HTTP 时，必须在该服务器资料中明确开启明文连接，授权会随资料保存。

## 可靠推送配置

推送依赖外部 Firebase 项目和部署配置。将 Firebase Android 应用的 `google-services.json` 放入 `app/`；此文件已被 Git 忽略，构建时会自动启用 Google Services 插件。FCM 需要兼容的 Google Play 服务设备。设备无 Play 服务时，前台 SSE 与运行中通知仍可用，可靠后台推送需要另配 OEM 渠道。

在 OpenCode 主机运行 `companion/` 中的服务：

1. 安装 Node 20+ 和依赖：`cd companion && npm ci`。
2. 以非仓库路径配置 `GOOGLE_APPLICATION_CREDENTIALS`、`FIREBASE_PROJECT_ID`、`OPENCODE_SERVER_PASSWORD`、`OPENCODE_MOBILE_PLUGIN_SECRET`、`OPENCODE_MOBILE_SERVER_KEY`；伴随服务和 OpenCode 使用同一组 Basic Auth 配置，默认用户名为 `opencode`，自定义用户名时同时设置 `OPENCODE_SERVER_USERNAME`。两个进程的 `OPENCODE_MOBILE_SERVER_KEY` 都必须设置为 App 中填写的可访问服务器地址。伴随服务默认仅监听 `127.0.0.1:4344`，对手机公开时需放在 HTTPS 反向代理后。
3. `node companion/src/server.mjs` 启动伴随服务。
4. 将 `companion/opencode-mobile.plugin.js` 复制到 OpenCode 配置的 `plugins/`，给 OpenCode 进程设置 `OPENCODE_MOBILE_COMPANION_URL`、相同的 `OPENCODE_MOBILE_PLUGIN_SECRET` 和 `OPENCODE_MOBILE_SERVER_KEY`。`OPENCODE_URL` 仍填写伴随服务所在主机访问 OpenCode 的本地地址（默认 `http://127.0.0.1:4096`）。插件仅上报事件类型、会话 ID、目录及工具名称；伴随服务查询会话标题后发送 FCM 数据消息，并按服务器地址隔离设备。
5. 在 App 服务器资料中填入伴随服务 HTTPS URL、相同的 `OPENCODE_MOBILE_PLUGIN_SECRET`（用于校验后台推送、防止伪造通知），并从设置页注册设备。

伴随服务保存设备 ID 和 FCM Token 于 `~/.local/state/opencode-mobile/devices.json`，权限为 0600。请将此状态目录纳入服务器的安全备份。Firebase 服务账号只放在服务器安全目录，不放进仓库或 App。

## 验收边界

Android 构建与单元测试只能证明代码可编译和有限的 API/状态逻辑。真实 OpenCode 版本、SSE 断线恢复、Android 16 Live Updates 提升、小米超级岛授权与展示、FCM 关 App 投递都需要在目标服务器和设备上验收。Android 15+ 的 dataSync 前台服务有运行时长限制，因此长期可靠通知依赖服务器插件与推送。
