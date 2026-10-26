# 1.5.0 安装包

`SendToSMB-1.5.0.apk` 是最低支持 Android 8.0 的 release 构建，包名 `com.zlight.sendtosmb`，版本 `1.5.0 / 8`。延续此前由所有者选择的本机 Android 调试密钥签名，可覆盖升级使用同一签名的旧版。

1.5.0 新增上传、下载和文件夹下载的断点续传，支持暂停、继续、加密任务恢复、源文件及片段校验、临时文件提交和断点清理。72 项主机自动测试通过。详细变更和边界见 `1.5.0.md`。

1.5.0 已发布到 EasyUpdate `http://192.168.95.55:19910`（应用 ID 6，后台 release ID 16）；旧版检查可获取新版，下载包 SHA-256 与本地构建一致。可用 `scripts/Publish-EasyUpdate.ps1` 发布后续版本。构建与验证结果见 `docs/TESTING.md`。

APK 不提交 Git，校验值见 `SHA256SUMS.txt`。
