# SendToSMB 测试说明

## 本机工具和设备

2026-09-06 检测到一台通过 USB 调试授权的测试机：型号 `23013RK75C` / `mondrian`，Android 16 (API 36)，物理分辨率 1440 × 3200，density 560。手机 Wi-Fi 已连接。

- Android SDK：`%LOCALAPPDATA%\Android\Sdk`
- ADB：`%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`
- JDK：`C:\Program Files\Android\Android Studio\jbr`，Java 21.0.10
- Gradle：8.13，工程 wrapper 或 `%USERPROFILE%\.gradle\wrapper\dists\gradle-8.13-bin` 缓存

环境检查未扫描局域网或调整防火墙。电脑有线网络与手机 Wi-Fi 处于不同 IPv4 子网，隔离集成测试使用 USB 转发；用户指定真实共享另做只读连接、目录和容量验证。

## 构建

```powershell
.\scripts\build.ps1
.\scripts\build.ps1 -Tasks 'assembleDebug', 'testDebugUnitTest', 'lintDebug'
.\scripts\build.ps1 -Tasks 'connectedDebugAndroidTest'
```

脚本默认构建 Debug APK 并执行单元测试。它为当前构建进程临时指定正常绝对路径的 Java Unix-domain socket 目录，并在退出时恢复环境变量。本机默认 `%TEMP%` 使用 `ZLIGHT~1` 形式的 8.3 路径，Java NIO `Selector.open()` 会因此报 `Unable to establish loopback connection / Invalid argument: connect`；只设置 IPv4 不能修复。`LoopbackProbe.java` 同时检查 TCP loopback 和 NIO selector。已使用此修复执行 Gradle `help --offline` 和生成 8.13 wrapper 成功。

## 隔离 SMB 服务

脚本使用 [Impacket 的 SimpleSMBServer](https://github.com/fortra/impacket/blob/master/examples/smbserver.py)，锁定测试依赖 `impacket==0.13.0`。仅监听电脑 `127.0.0.1:1445`，并通过 `adb reverse` 转发至手机。不修改系统 SMB、Windows 共享、445 端口或防火墙。服务只共享工程 `.testenv/share` 内生成的测试文件。所有测试环境文件应由 `.gitignore` 排除。

在工程目录的 PowerShell 执行：

```powershell
.\scripts\Start-TestSmb.ps1
# 如果多台设备同时连接，使用 -Serial 指定 adb devices 列出的设备。
```

首次运行会把 Python 依赖安装到 `.testenv/python-packages`。默认使用 Codex 随附 Python，也可传入 `-Python C:\path\to\python.exe`。脚本自动执行电脑端 SMB 验证：身份验证、创建目录、上传、下载 SHA-256 一致性、重命名、列目录、删除。

手机 App 内填写以下仅供测试的凭据：

| 设置 | 值 |
| --- | --- |
| URL | `smb://127.0.0.1:1445/TESTSHARE` |
| 用户名 | `android-test` |
| 密码 | `SendToSMB-test-only!` |
| 域 | 空 |

结束后执行：

```powershell
.\scripts\Stop-TestSmb.ps1
```

停止脚本核对 PID、启动时间和可执行路径后关闭本脚本服务，并仅移除它对应的 USB 端口转发。保留测试文件供核对，避免删除用户数据。

Windows 上 Python `os.open` 的句柄未启用 `FILE_SHARE_DELETE`，因此测试服务仅在 SMB rename 操作前关闭该测试文件句柄，操作后重新打开；此兼容处理只影响测试服务，不在 App 代码内。

Impacket 是协议测试服务器，`FILE_FS_FULL_SIZE_INFORMATION` 返回固定模拟容量（总量约 1.14 TB，可用量相同），不是电脑磁盘真实余量。脚本修正其将 SMB2 filesystem class 7 误当作 EA information 而仅返回 4 字节的问题，返回规范的 32 字节完整容量结构。真实共享的目录和容量已单独验证；SMB 3 加密、服务器配额和其他 NAS 的兼容性不在此次覆盖范围内。

## 验收清单

- [x] 无连接配置时显示引导；自定义 URL 支持服务器、端口、共享名和初始子目录。
- [x] 保存配置后能连接共享并显示容量；未知容量显示占位，不能显示为 0 容量。
- [x] 列表展示文件夹、文件大小、修改时间；支持面包屑、搜索、排序、刷新。
- [x] 中文名、空格文件名、零字节文件显示与传输正确。
- [x] 通过 Android 文件选择器上传，下载使用 Android 授权位置，文件校验和一致。
- [x] 新建目录、重命名、递归复制/移动/删除协议测试通过，界面删除确认可取消。
- [x] 退出或进入后台后断开，重新进入后使用保存的凭据重新连接。
- [x] Wi-Fi 不可用状态显示提示，并发出系统 Wi-Fi 设置操作（Compose UI 测试，未关闭用户手机网络）。
- [x] 手机 21:9 窄屏可滚动操作；平板 16:9 横屏采用侧栏与文件内容的双栏布局。
- [x] 错误密码被拒绝；无响应服务器连接可被断开及时中止，后续重连正常。

平板布局优先通过专用模拟器、Compose 预览或测试环境验证。使用测试机调整显示设置后，恢复它原本的分辨率与密度。

## 已执行的验证（2026-09-06）

- `assembleDebug`、`assembleDebugAndroidTest`、`testDebugUnitTest` 和 `lintDebug` 成功；Lint 无错误，保留依赖版本和代码风格建议。
- 8 个 JVM 地址解析单元测试通过。
- Redmi Android 16 上 10 个功能/界面集成测试通过：文件树往返、SHA-256、零字节、取消不发布部分文件、错误密码与重连、无响应连接的强制断开、容量、真实共享只读、凭据加密与不记住密码、ViewModel 生命周期、界面引导/搜索/网格/删除确认。Runner 显示 `OK (11 tests)`，其中 1 个是未传参而跳过的手工配置辅助测试。
- 真实 `smb://192.168.95.55/windisk` 在手机上使用访客认证读取成功：82 个根目录项目，总容量 126,495,985,664 字节，可用 50,355,122,176 字节（当时的读取结果）。整个真实共享测试函数只有连接、list、capacity 和 disconnect，无写入/删除调用。
- 系统文件选择器实测上传 `SendToSMB-upload-check.txt` 到隔离共享成功，SHA-256：`76AC51CD9DF0521D6DDFBFB0B20063AE4D972455820082529964A4937C16CF06`，与手机上传前的测试源一致。
- 通过文件菜单下载同一文件，系统目录选择器授权 `Documents/SendToSMB-check` 后保存成功；下载后的 SHA-256 与上述源文件完全一致。
- 使用同一测试机临时模拟 1080×2520、420dpi 的 21:9 手机，以及 1920×1080、240dpi 的 16:9 平板横屏；两种布局各 2 个 UI 测试通过，并实际查看截图。修复横屏搜索键盘挤压文件列表的问题：键盘出现时收起顶部标题与容量区，保留搜索、操作栏及列表。
- 检查结束后恢复原始 1440×3200、560dpi、旋转 0、自动旋转关闭的设置。实际平板硬件尚未测试；布局通过手机显示尺寸模拟验证。
- 已移除手机里的隔离测试连接配置，保留用户的 Windisk 连接。回到桌面后重新打开应用，真实共享自动重连成功，界面显示 82 个项目及真实容量。

SMBJ 0.14.0 的匿名 SMB3 登录遇到上游 #872 空 session key 问题，应用对空凭据使用 `AuthenticationContext.guest()`，真实共享只读回归已通过。Android 16 UI 自动化需要 Espresso 3.7.0，已显式锁定以避免旧版反射 `InputManager.getInstance()` 的失败。

手工复现仪器测试（先运行隔离服务）：

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e realSmbUrl smb://192.168.95.55/windisk com.zlight.sendtosmb.test/androidx.test.runner.AndroidJUnitRunner
```

省略 `realSmbUrl` 即跳过真实共享检查。`DeviceSetupTest` 仅在显式传入 `setupUrl` 时写入本地加密连接配置，供手工 UI 验证，不会访问共享。

布局验证可运行 `scripts/Check-Layouts.ps1`，它在 `finally` 中恢复显示设置；需先安装应用和测试 APK，解锁手机。截图和详细运行日志写入忽略提交的 `.testenv`。

## 界面截图

以下为隔离测试共享的界面，容量是测试服务模拟值，未包含用户真实共享的文件名。

![21:9 手机](screenshots/phone-21x9.png)

![16:9 平板布局模拟](screenshots/tablet-16x9.png)
