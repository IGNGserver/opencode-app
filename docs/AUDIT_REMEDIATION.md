# 最终复审后整改记录

审查与修复只依据当前仓库、AUDIT.md、NEEDS_SOL_REVIEW.md、Git 差异及调用链，未使用其他聊天或 Memory。基线 `db7289d`，承接 `4e65e40` 的整改。

## 复审问题处理

| 问题 | 实际修复与回归依据 |
| --- | --- |
| R01 凭据跨源继承 | 测试/保存共用凭据计算；Cookie 仅同 origin 继承；存储自带 origin 绑定，跨两次提交崩溃也不泄漏。MockWebServer 验证实际连接无旧 Cookie，包含仅 Cookie 账号。 |
| R02 异步上下文 | 操作入口捕获连接、API、项目/会话与选择修订；排队发送重验；搜索/文件序号；旧响应不能覆盖新选择。延迟读取、删除、选择与离线读取回归。 |
| R03 V2 契约 | 当前 text/decision/form 与旧 V2 分离；表单类型、值/标签、条件和校验；source.id/save；unrevert DELETE；禁止写失败换体重试。 |
| R04 推送队列一致性 | 插件持久有序待发队列；companion 单事务持久注册、状态、去重、水位、待发队列；落盘失败返回 503，恢复自动发送，旧等待状态不覆盖新完成状态。 |
| R05 注销 | 删除前持久化加密撤销意图，与注册串行；注销失败保留重试；服务端撤销与清理待投递原子提交。 |
| R06 过期状态 | 局部失败保留旧目录会话/任务并标记 stale；消息失败不清除 cached；待授权不被 idle 错判完成；SSE 连通性独立于任务阶段。 |
| R07 缓存删除竞态 | 验证写入代次与提交同锁；删除覆盖加密中的新键；墓碑按在途任务保留，失败不阻断后续批次。 |
| R08 Fork | 使用服务端实际返回的新会话；写入列表后按仍有效的选择提交，不再选择列表中的偶然首项。 |
| R09 发布升级 | 稳定 keystore 注入、证书指纹检查、非调试 Release、递增 versionCode、确定产物路径和 FCM 变体；无密钥阻断发布。 |
| R10 推送认证 | 仅 v3、全字段签名、设备一致、持久递增序号；空密钥及解密失败拒绝；手机不持有插件入站密钥。 |

另修复通知动作复用 UI 客户端、跨服务器监控残留、草稿回包覆盖、长期权限不可撤销、列表静默截断和主线程大 JSON 解析。当前分页最多 40 页/5000 项/1600 万字符，超限明确失败；缓存最多 150 条、单条 2MB、总计 16MB。

## 验证

最终验证：`ANDROID_HOME=/home/lvziw/Android/Sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug` 通过，43 项 JVM/MockWebServer 测试，Lint 0 Error / 31 Warning；`cd companion && node --test` 通过，17/17 项。另用不含凭据的 Firebase 测试配置验证可选插件构建路径，完成后删除测试配置；发布 YAML 与所有 Shell 步骤做语法解析。未用本地 debug 签名构建结果冒充正式发布验证。

V2 依据为 `https://opencode.ai/v2/openapi.json` 的 2026-09-29 快照，SHA-256 `8ab6ec800922fc68890c68134320b4063fb317e0d944252d232083e12d32f4e9`。MockWebServer 验证对应请求/响应，不等同于目标实例已通过。

## 结束条件

在上述自动验证通过后，本轮已确认的代码缺陷可以关闭；发布/上线验收仍须完成 NEEDS_SOL_REVIEW.md 的真实服务端、设备、FCM 和签名环境检查。未进行发布、部署或真机安装。
