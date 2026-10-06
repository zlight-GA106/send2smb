# 1.2.0 正式安装包

`SendToSMB-1.2.0.apk` 是最低支持 Android 8.0 的 release 构建，包名 `com.zlight.sendtosmb`，版本 `1.2.0 / 5`。按所有者选择，本包使用本机 Android 调试密钥签名，可覆盖升级调试版；对外正式发行前应改用所有者自有发布签名。调试对比包 `SendToSMB-1.2.0-debug.apk` 一并保留。

1.2.0 不再强制要求系统识别到 Wi-Fi 才能发起连接：VPN、USB 网络共享等可达路径可以直接尝试，局域网断开也不会自动关闭现有会话。新增“设置”页：E Ink 模式开启后整个应用以灰度渲染，适合电子墨水屏设备；EasyUpdate 项可填写自托管服务地址检查新版本，下载 APK 并通过 SHA-256、包名和版本校验后调用系统安装器，同时支持向服务上报设备心跳。

本版本已在 `http://192.168.95.55:19910` 的 EasyUpdate 服务注册并发布（应用 ID 6，release ID 9），可用 `scripts/Publish-EasyUpdate.ps1` 发布后续版本。编译、单元测试、Lint、测试机仪器测试与发布验证结果见 `docs/TESTING.md`。

APK 不提交 Git，校验值见 `SHA256SUMS.txt`。
