# SendToSMB

用于 Android 手机和平板的局域网 SMB 文件管理器。原生 Kotlin + Jetpack Compose 实现，支持 Android 8.0（API 26）及以上版本。

## 功能

- 保存多个网络位置，支持 `smb://服务器[:端口]/共享/子目录`、Windows UNC 路径和 `/服务器/共享` 简写。
- 用户名、密码、域及上次选择的连接保存在本机；整个配置使用 Android Keystore + AES-GCM 加密，关闭“记住凭据”后不持久化密码，禁用应用备份。
- 设置中可勾选“在后台继续运行”：上传、下载在切换应用或锁屏后由前台服务继续，通知栏显示进度、平均速度并可取消全部任务；传输结束自动释放唤醒锁、Wi-Fi 锁并关闭后台连接。默认关闭，未开启时离开前台会停止传输；回到前台重新连接上次的位置。
- 识别 Wi-Fi 和以太网局域网；未检测到局域网时仅提示并提供 Wi-Fi 设置入口，不会阻止直接连接 SMB 服务器（VPN、USB 网络共享等场景），也不要求 Wi-Fi 能访问互联网。
- 设置页提供 E Ink 模式，开启后整个应用以灰度显示，适合电子墨水屏设备。
- 设置页提供 EasyUpdate 更新：可填写自托管 EasyUpdate 服务地址检查新版本，下载 APK 后校验 SHA-256 并调用系统安装器，同时支持向服务上报设备心跳。
- 显示服务器报告的总容量与当前用户可用容量（可能受配额限制）；服务器不支持时显示“服务器未提供容量”。
- 首页与文件管理使用独立底部栏目；文件管理提供类似资源管理器的面包屑、文件夹优先列表、名称/修改日期/大小排序、搜索、列表/网格、多选和详细信息。
- 创建文件夹、重命名、复制、剪切、粘贴及带确认的删除，支持目录树操作；拒绝覆盖已有目标和移动到自身内部。
- Android 系统文件选择器支持多文件上传；系统目录选择器支持文件和整个远程文件夹下载。任务显示实时速度，完成记录保留平均速度；传输页固定顶栏显示整批平均速度，计入小文件准备、切换及等待的耗时。
- 首页连接 SMB 后继续展示“连接，让文件触手可及”和文件夹 / Wi-Fi 宣传图案。
- 内置记事本式纯文本编辑器：在 SMB 文件夹中新建 `.txt`，点开现有 TXT 后先缓存到本机再编辑；首页也能打开本地编辑器和恢复草稿。
- 文本编辑保留 UTF-8 / UTF-8 BOM / GBK / UTF-16 BOM 编码、CRLF / LF / CR 及混合换行；提供查找替换、撤销重做、跳转行、字数 / 字符 / 行数、字体行距、自动换行、只读和另存为。触摸选择工具栏及方向键便于手机操作。
- 文本修改显示 `●`，本机草稿与写回 SMB 分别显示状态；自动保存可关闭、离开时保存或每 30 秒保存。SMB 保存先校验远程内容，再上传同目录临时文件、flush、重命名替换；冲突保留草稿，未改内容不重写。
- Android 文件管理器“打开方式”和聊天软件分享可进入文本编辑器；通过系统选择器另存到 Documents 等位置，保存后读回校验，也可把纯文本文件分享给其他应用。本地编辑不依赖网络。
- 传输记录默认加密保存在本机，最多保留 200 条；完成、失败或取消的下载可从记录中再次发起，并自动使用原 SMB 连接。传输页可指定并加密保存默认下载目录，也可恢复为每次询问。
- 目录读取遇到已经关闭的共享、失效会话或服务器临时拒绝时会丢弃旧连接并自动重连一次；下载在服务器拒绝读取属性时使用最小文件读取权限重试。
- 上传、下载同名文件自动保留两份；上传通过同目录临时文件完成后再重命名，避免把未完成内容作为最终文件发布。
- 窄屏使用底部导航，宽度达到 840dp 时使用侧边栏与详细文件列表，适配手机长屏及 16:9 横屏平板。

## 使用

1. **连接网络**：手机连接可访问 SMB 服务器的 Wi-Fi 或有线网络。应用不会强制要求系统识别到局域网，VPN 等可达路径也可以直接尝试连接。
2. **发起连接并保存凭据**：填写连接名称、共享 URL 与账号，点击“保存并连接”。服务器允许访客访问时用户名和密码可留空。
3. **访问你的文件**：进入“文件管理”，打开文件夹、搜索或长按多选；使用上传与下载操作在手机和共享目录之间传输文件。
4. **编辑文本**：点开 `.txt`，或在文件管理的“文件操作”菜单选择“新建文本文档”。标题栏保存按钮写回 SMB；编辑器菜单提供查找替换、字体、编码、自动保存和另存为。主页“文本编辑器”入口可打开设备文档和恢复未保存草稿。

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

本次安装包另外保存为 `releases/SendToSMB-1.4.0.apk`，SHA-256 记录在 `releases/SHA256SUMS.txt`。APK 本体不纳入 Git，并作为 GitHub Release 附件发布。

## 实现与边界

- `ui/`：Fluent 2 风格的 Compose UI，通过 `UiState` / `UiAction` 与应用逻辑连接。
- `ExplorerViewModel.kt`：串行文件操作、连接生命周期、传输队列、系统 URI 读写。
- `SendToSmbApplication.kt` / `BackgroundTransferService.kt`：界面与服务共享传输队列；按需运行 `dataSync` 前台服务、传输通知和锁屏保持。`TransferSpeedMeter.kt` 使用单调时钟统计实时及平均速度。
- `data/SmbRepository.kt`：SMBJ 0.14.0，SMB 2/3，不支持 SMB1；流式处理文件，断开优先关闭原始 Socket。
- `data/SmbAddress.kt`：URL 规范化、Unicode 路径及相对路径边界验证。
- `ProfileStore.kt` / `LanMonitor.kt`：凭据加密及局域网监听。
- `SettingsStore.kt` / `EasyUpdateClient.kt`：E Ink 与更新服务设置、EasyUpdate 检查/下载/心跳客户端。
- `editor/`：严格编码读写、逐换行保留、磁盘分页、本机原子草稿、SMB 冲突检查和原生 Android 文本输入组件。

文本编辑器支持最大 256 MiB 源文件，按约 64K 字符分页，单页编辑上限为 256K 字符；超限会提示，不静默截断。查找 / 替换与跳转行可跨页，单次查找内容最长 4096 个字符；全文件替换的撤销记录写入磁盘。字符数按 Unicode 码点（包含归一化换行），字数按中日韩逐字、其他文字按连续字母 / 数字计词。无 BOM 的编码存在歧义，默认优先严格 UTF-8、再尝试 GBK；不能无损往返时阻止打开，可手动选择编码重试。不能由所选编码表示的新字符会阻止保存，草稿仍保留。

设备文档通过 SAF“另存为”创建副本，不直接覆盖外部提供方的原文件；保存后读回校验。SMB 临时文件替换依赖服务端的 rename 支持；远程修改检查不构成服务器端的跨客户端事务锁。网络中断或保存失败后，可从编辑器菜单恢复本机草稿；清除应用数据或卸载会删除本机缓存和草稿。

取消、强制停止、进程被系统终止和网络中断会停止传输，不提供断点续传；已成功传输的其他项目保留。开启后台传输后，切换应用、锁屏或从最近任务移除界面会继续已有队列。Android 15 及以上对 dataSync 前台服务设有后台运行时限；达到系统时限时应用会安全取消剩余任务，并提示回到应用重新发起。强制断网时服务器上可能残留 `.sendtosmb-*.part` 临时文件，可在确认不再需要后手动处理。目录下载中断时，已完成文件保留，当前不完整本地文件会清理。复制、移动、删除目录不是跨整个目录树的事务，途中出错时已完成操作保留。为避免循环或误操作，复制/递归删除不跟随符号链接，递归深度限制为 128 层。

用户指定真实共享在本次开发验收中仅做读取，全部上传、移动、重命名、删除测试均限定在 `127.0.0.1:1445/TESTSHARE` 的隔离测试目录。

## 设计依据

后台传输参考 [LocalSend 的 Android 前台服务逻辑](https://github.com/localsend/localsend/blob/main/packages/localsend_isolates/lib/util/foreground_service.dart)：按需启动 dataSync 服务、低优先级通知、唤醒锁与 Wi-Fi 锁，并在任务结束时停止。系统时限处理依据 [Android 前台服务超时说明](https://developer.android.com/develop/background-work/services/fgs/timeout)。

参考 [Fluent 2 设计指南](https://fluent2.microsoft.design/get-started/design)、[颜色](https://fluent2.microsoft.design/color)、[形状](https://fluent2.microsoft.design/shapes)和 [Android 组件说明](https://fluent2.microsoft.design/components/android/)。采用中性表面、细边框、微软蓝品牌色、8/12dp 圆角及适应移动端的原生控件。这是依据 Fluent 2 定制的 Compose 界面，并未引入官方 Fluent Android 组件库。

测试步骤、设备及验证结果见 [docs/TESTING.md](docs/TESTING.md)。SMB 依赖与兼容性问题参考 [SMBJ 官方项目](https://github.com/hierynomus/smbj)和 [SMB3 匿名会话问题 #872](https://github.com/hierynomus/smbj/issues/872)。
