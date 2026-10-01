# Android Release 签名密钥

## 固定身份

- 应用：`com.igng.opencode.lagoon`
- 格式：PKCS#12
- 别名：`opencode-lagoon-release`
- 算法：RSA 3072，SHA256withRSA
- 证书 SHA-256：`C2:12:EF:1E:68:0C:C1:24:F6:87:9A:CD:B4:62:58:EC:EB:AD:B0:F5:3B:BD:E2:77:16:20:AA:23:AC:D7:AB:D2`
- 有效期至：2054-02-14

该证书是 Android 安装更新身份的一部分。后续预发布版和正式版必须继续使用它；更换证书会使已安装的旧签名版本无法直接覆盖更新。证书轮换只能作为单独迁移处理，不能重新生成同名密钥来替代。

项目从 “OpenCode Mobile” 更名为 “OpenCode Lagoon” 时，keystore 别名与主文件名同步改为 `opencode-lagoon-release`，仓库与密钥目录改为 `opencode-lagoon`。这次只改别名和路径，私钥与证书未变，上表证书 SHA-256 指纹保持同一个值，因此签名身份连续；`OPENCODE_LAGOON_KEY_ALIAS` 的值必须与清单中的 `key_alias` 一致。

## 密钥保管位置

- 本机主文件：`/home/lvziw/.config/opencode-lagoon/android-signing/opencode-lagoon-release.p12`
- 本机口令文件：`/home/lvziw/.config/opencode-lagoon/android-signing/.env`，权限为 `600`
- NAS 加密备份：`/home/lvziw/项目/.private/opencode-lagoon/android-signing/opencode-lagoon-release.p12`
- 校验清单：[`ANDROID_SIGNING_MANIFEST.txt`](ANDROID_SIGNING_MANIFEST.txt)

主文件和 NAS 备份是同一个 PKCS#12 文件，SHA-256 均为清单记录值。NAS 目录保存受 PKCS#12 口令保护的 keystore、公开证书和校验清单，不保存口令。口令文件保留在本机受限目录；Actions 中的值由 GitHub 加密保存。

不要把 `.p12`、`.env`、Base64 keystore 或任何签名口令提交到仓库。GitHub Secret 的值不可读取；若需要重设，只能从本机主文件和口令文件重新写入。

## GitHub Actions 配置

仓库 `IGNGserver/opencode-lagoon` 已设置以下 Actions Secrets：

- `OPENCODE_LAGOON_KEYSTORE_BASE64`
- `OPENCODE_LAGOON_KEYSTORE_PASSWORD`
- `OPENCODE_LAGOON_KEY_ALIAS`
- `OPENCODE_LAGOON_KEY_PASSWORD`
- `OPENCODE_LAGOON_SIGNING_CERT_SHA256`

PKCS#12 使用同一个随机口令保护 store 和 key，因此 `OPENCODE_LAGOON_KEYSTORE_PASSWORD` 与 `OPENCODE_LAGOON_KEY_PASSWORD` 的值相同。工作流将 keystore 解码到 runner 临时目录，构建 `Release` APK，并在创建 GitHub Release 前检查 APK 非 debuggable、签名有效且证书指纹匹配固定值。缺失或不匹配时工作流失败，不会上传 APK。

管理员可通过 `gh secret list --repo IGNGserver/opencode-lagoon` 核对 Secret 名称和更新时间；此命令不会显示 Secret 值。

## 恢复与重新配置

如果本机主 keystore 丢失，从 NAS 备份恢复后先核对清单中的摘要：

```bash
install -m 600 \
  /home/lvziw/项目/.private/opencode-lagoon/android-signing/opencode-lagoon-release.p12 \
  /home/lvziw/.config/opencode-lagoon/android-signing/opencode-lagoon-release.p12
sha256sum /home/lvziw/.config/opencode-lagoon/android-signing/opencode-lagoon-release.p12
keytool -list -keystore \
  /home/lvziw/.config/opencode-lagoon/android-signing/opencode-lagoon-release.p12 \
  -storepass:file /home/lvziw/.config/opencode-lagoon/android-signing/.env
```

如果 Actions Secret 需要重设，从本机文件安全地写入，不要把口令放在命令参数或终端输出中：

```bash
signing_dir=/home/lvziw/.config/opencode-lagoon/android-signing
base64 -w 0 "$signing_dir/opencode-lagoon-release.p12" \
  | gh secret set OPENCODE_LAGOON_KEYSTORE_BASE64 --repo IGNGserver/opencode-lagoon
tr -d '\r\n' < "$signing_dir/.env" \
  | gh secret set OPENCODE_LAGOON_KEYSTORE_PASSWORD --repo IGNGserver/opencode-lagoon
printf '%s' 'opencode-lagoon-release' \
  | gh secret set OPENCODE_LAGOON_KEY_ALIAS --repo IGNGserver/opencode-lagoon
tr -d '\r\n' < "$signing_dir/.env" \
  | gh secret set OPENCODE_LAGOON_KEY_PASSWORD --repo IGNGserver/opencode-lagoon
sed -n 's/^certificate_sha256=//p' "$signing_dir/manifest.txt" \
  | tr -d '\r\n' \
  | gh secret set OPENCODE_LAGOON_SIGNING_CERT_SHA256 --repo IGNGserver/opencode-lagoon
gh secret list --repo IGNGserver/opencode-lagoon
```

## 已发布 APK 的签名迁移

`v0.1.0-rc.1`、`v0.1.0-rc.2`、`v0.1.0-rc.3` 的 APK 都由不同的临时 debug 证书签名，无法从 APK 还原对应私钥。新固定证书从之后的 release 开始使用；设备若已安装上述旧 APK，需要先保存服务器地址等本地配置，再卸载旧包并安装新包。完成这一次迁移后，后续 release 可用同一证书直接更新。

更名为 OpenCode Lagoon 后 `applicationId` 改为 `com.igng.opencode.lagoon`，与已安装的 `com.igng.opencode.mobile` 在系统内并存为两个应用，旧包不会被新包覆盖升级；两者用同一张签名证书。旧包确认不再需要更新后，可直接在设备上卸载，服务器端无需额外清理（companion 的 registry 按 App 上报的设备 ID 记录，旧设备条目可在下次发布说明里提示用户重新注册）。
