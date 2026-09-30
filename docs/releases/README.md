# Release notes

每个版本必须有一个同名中文说明文件，例如 `v0.1.0-rc.1.md`。文件名必须与 Git tag 完全一致。

## 未发布的版本

说明文件存在、GitHub Releases 页面却找不到的版本记录在这里，避免后来的人再去找不存在的安装包。

- `v0.1.0-rc.9`：只有 git tag，没有 GitHub Release。当时的发布流水线在「验证 companion」步骤无限挂起（companion 测试套件的临时目录竞态，见 PR #27），发布步骤从未执行。该提交的改动已随 `v0.1.0-rc.10` 发布，因此不单独补发；tag 保留以对应历史提交。

