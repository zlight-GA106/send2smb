# SendToSMB

用于 Android 手机和平板的局域网 SMB 文件管理器。原生 Kotlin + Jetpack Compose 实现，支持 Android 8.0（API 26）及以上版本。

## 功能

- 保存多个网络位置，支持 `smb://服务器[:端口]/共享/子目录`、Windows UNC 路径和 `/服务器/共享` 简写。
- 用户名、密码、域及上次选择的连接保存在本机；整个配置使用 Android Keystore + AES-GCM 加密，关闭“记住凭据”后不持久化密码，禁用应用备份。
- 离开应用前台即关闭 SMB Socket，并停止正在进行的传输；回到前台重新连接上次的位置。进程重启后从保存的连接重新打开共享根目录。
- 识别 Wi-Fi 和以太网局域网；无局域网连接时提示并提供 Wi-Fi 设置入口，不要求 Wi-Fi 能访问互联网。
- 显示服务器报告的总容量与当前用户可用容量（可能受配额限制）；服务器不支持时显示“服务器未提供容量”。
- 类似资源管理器的面包屑、文件夹优先列表、名称/修改日期/大小排序、搜索、列表/网格、多选、详细信息。
- 创建文件夹、重命名、复制、剪切、粘贴及带确认的删除，支持目录树操作；拒绝覆盖已有目标和移动到自身内部。
- Android 系统文件选择器支持多文件上传；系统目录选择器支持文件和整个远程文件夹下载，传输显示进度、完成/失败/取消状态。
- 上传、下载同名文件自动保留两份；上传通过同目录临时文件完成后再重命名，避免把未完成内容作为最终文件发布。
- 窄屏使用底部导航，宽度达到 840dp 时使用侧边栏与详细文件列表，适配手机长屏及 16:9 横屏平板。

## 使用

1. 手机连接可访问 SMB 服务器的 Wi-Fi，打开应用，点击“添加网络位置”。
2. 填写连接名称和包含共享名称的 URL，例如 `smb://192.168.1.10/Documents`。服务器允许访客访问时用户名、密码可留空；否则填写服务器账户信息。
3. 点击“保存并连接”。打开文件夹、点击文件查看详情，或使用每行右侧菜单操作文件。长按进入多选。
4. 点击“上传”选择手机文件；在文件菜单中点击“下载”，选择手机上的保存目录。

文件访问使用 Android Storage Access Framework，只访问系统文件选择器授权的内容，不申请“管理所有文件”。系统限制的其他应用私有目录不能通过本应用绕过访问。

## 构建与安装

Android Studio 打开本目录，安装 Android SDK 36.1、JDK 17 或更新版本；在不提交的 `local.properties` 中配置 `sdk.dir`。Gradle 8.13 wrapper 已包含。

```powershell
# Windows：脚本修复部分环境下 Java NIO 临时目录使用 8.3 路径的问题
.\scripts\build.ps1
.\scripts\build.ps1 -Tasks ':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug'

# 常规 JDK 环境
.\gradlew.bat assembleDebug testDebugUnitTest

# 安装调试构建
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。正式发布需由所有者配置自己的发布签名；密码、密钥、SDK 本地路径和测试环境不进入 Git。

本次已验证安装包另外保存为 `releases/SendToSMB-1.0.0-debug.apk`，SHA-256 记录在 `releases/SHA256SUMS.txt`。APK 本体不纳入 Git。

## 实现与边界

- `ui/`：Fluent 2 风格的 Compose UI，通过 `UiState` / `UiAction` 与应用逻辑连接。
- `ExplorerViewModel.kt`：串行文件操作、连接生命周期、传输队列、系统 URI 读写。
- `data/SmbRepository.kt`：SMBJ 0.14.0，SMB 2/3，不支持 SMB1；流式处理文件，断开优先关闭原始 Socket。
- `data/SmbAddress.kt`：URL 规范化、Unicode 路径及相对路径边界验证。
- `ProfileStore.kt` / `LanMonitor.kt`：凭据加密及局域网监听。

取消、退出和网络中断会停止传输，不提供断点续传；已成功传输的其他项目保留。强制断网时服务器上可能残留 `.sendtosmb-*.part` 临时文件，可在确认不再需要后手动处理。目录下载中断时，已完成文件保留，当前不完整本地文件会清理。复制、移动、删除目录不是跨整个目录树的事务，途中出错时已完成操作保留。为避免循环或误操作，复制/递归删除不跟随符号链接，递归深度限制为 128 层。

用户指定真实共享在本次开发验收中仅做读取，全部上传、移动、重命名、删除测试均限定在 `127.0.0.1:1445/TESTSHARE` 的隔离测试目录。

## 设计依据

参考 [Fluent 2 设计指南](https://fluent2.microsoft.design/get-started/design)、[颜色](https://fluent2.microsoft.design/color)、[形状](https://fluent2.microsoft.design/shapes)和 [Android 组件说明](https://fluent2.microsoft.design/components/android/)。采用中性表面、细边框、微软蓝品牌色、8/12dp 圆角及适应移动端的原生控件。这是依据 Fluent 2 定制的 Compose 界面，并未引入官方 Fluent Android 组件库。

测试步骤、设备及验证结果见 [docs/TESTING.md](docs/TESTING.md)。SMB 依赖与兼容性问题参考 [SMBJ 官方项目](https://github.com/hierynomus/smbj)和 [SMB3 匿名会话问题 #872](https://github.com/hierynomus/smbj/issues/872)。
