# 1.4.0 安装包

`SendToSMB-1.4.0.apk` 是最低支持 Android 8.0 的 release 构建，包名 `com.zlight.sendtosmb`，版本 `1.4.0 / 7`。延续此前由所有者选择的本机 Android 调试密钥签名，可覆盖升级使用同一签名的旧版。

1.4.0 新增 SMB 与本地纯文本编辑器，保留编码和换行，支持查找替换、撤销重做、只读、另存为、分页大文件、可靠保存与草稿恢复。详细变更和验证范围见 `1.4.0.md`。

1.4.0 已发布到 EasyUpdate `http://192.168.95.55:19910`（应用 ID 6，后台 release ID 13）；旧版检查可获取新版，下载包 SHA-256 与本地构建一致。可用 `scripts/Publish-EasyUpdate.ps1` 发布后续版本。构建与验证结果见 `docs/TESTING.md`。

APK 不提交 Git，校验值见 `SHA256SUMS.txt`。
