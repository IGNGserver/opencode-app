# opencode-app · 项目 Agent 规范
> 只写本仓库与设备级规范的差异。Git 纪律、worktree、冲突处理见 `~/.qoder/coder-rules/global-rules.md`。

Collaboration: solo
Default branch: main
Integration: direct-after-validation
Release: TBD
Worktree: `~/项目/.wt/opencode-app/<slug>`

## 这是什么
2026-09-27 新建的独立 Android 项目基线。当前仓库**只有一个空基线提交、没有任何跟踪文件、也没有 remote**，所以本文件里凡是"待定"的都必须在项目真正落地时补齐，不得凭空填命令。

## 验证命令
- 待定。项目落地后必须登记：安装依赖、lint/类型检查、测试、构建四组命令；在登记之前，任何 Agent 不得声称"已验证"，只能报告缺口。

## 集成
- 无 remote，"推送 / PR / 发布"目前不可能完成。第一个真实改动之前先建 GitHub 仓库并 `git remote add origin ...`。
- Android 工程建好后按设备级规范走 worktree（`~/项目/.wt/opencode-app/<slug>`），Gradle 产物用共享 `GRADLE_USER_HOME`，不要把 `build/` 提交进仓库。

## 项目特殊限制
- 参考 `IGNG/IGNGmc软件` 的约定：`keystore/`、签名 properties、`local.properties` 必须保持未跟踪；签名口令禁止写进 `build.gradle.kts`、提交信息或日志。
- `.gitattributes` 必须在第一个提交前建好（`* text=auto eol=lf`，`*.bat`/`*.cmd` 用 `eol=crlf`），否则会重现其它仓库的 `gradlew.bat` 换行抖动。
