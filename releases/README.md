# 1.3.0 安装包

`SendToSMB-1.3.0.apk` 是最低支持 Android 8.0 的 release 构建，包名 `com.zlight.sendtosmb`，版本 `1.3.0 / 6`。延续此前由所有者选择的本机 Android 调试密钥签名，可覆盖升级使用同一签名的旧版。

1.3.0 新增可勾选的后台传输、通知进度及取消入口；连接后的首页保留宣传图案；传输任务显示实时速度，固定顶栏显示包含小文件切换耗时的整批平均速度。详细变更和验证范围见 `1.3.0.md`。

1.3.0 已发布到 EasyUpdate 服务 `http://192.168.95.55:19910`（应用 ID 6，后台 release ID 11）。旧版检查可获取新版，下载包 SHA-256 与本地构建一致；可用 `scripts/Publish-EasyUpdate.ps1` 发布后续版本。构建与验证结果见 `docs/TESTING.md`。

APK 不提交 Git，校验值见 `SHA256SUMS.txt`。
