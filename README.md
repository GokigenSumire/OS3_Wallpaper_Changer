# Hyper Wallpaper Changer

最新工作版本68／1.2.0-p10-lock-input已接受鉴别的LOCK枚举命令与正常认证后的独立编辑链，配套按需模块69／1.1.0-lock；以下67交接说明为历史。67六项用户反馈通过，切域闪屏已关闭；LOCK逐图停用链只有新版本实机完成后才可改为通过。新三手势／认证／GL／后台可达全部仍需新候选真实检查，代码存在不冒设备已通过。主应用仍独立可用，三手势已原生可达的设备无需模块；兼容模块需用户按需启用系统框架和SystemUI作用域并正常重启。主／模块身份与正式签名、Room8保留，不自动安装／卸载／清数据或操作手机。具体自动／未测状态以本轮候选RESULTS和根质量台账最新记录为准，不公开GitHub或开始P11。

本目录是以 Shimmer 为来源的独立个人非商业工作工程；工作区原始来源保留于 `参考源码/app/shimmer-master`。作者、来源历史和 CC BY-NC-SA 4.0 许可保留，许可证见 [许可证文件](LICENSE)，其他来源及当前分发边界见 [第三方来源与许可](THIRD_PARTY_NOTICES.md)。以下是 Shimmer 上游说明的中文译文；2026-09-24 按用户要求将来源工程的 Markdown 说明也统一为中文，原始来源可从已保存的 Git 基线追溯。上游功能不等于本派生应用当前已实现或已验收。

当前已按用户指令实施P10，正式显示名为 `Hyper Wallpaper Changer`，主应用拟交付版本 `67 / 1.1.2-p10-dual-native`，身份保持 `personal.wallpaper.os3` 和原正式证书。P10-01原生双域图片链已接通：图库、播放、特效可选择桌面／锁屏，分别绑定来源、数据库、四顺序、历史、当前图、逐图启停存储、默认调整、过渡和调度。HOME原数据原位保留，LOCK初始空。新锁屏服务只准入系统实际flags=2；共享、错误、未知目标不写正式LOCK状态，需用户在系统选择器仅应用到锁屏。现有两个Canvas能力探针仍只计数，不读生产图库或执行编辑。原模块62不改，按需使用，不把桌面兼容当锁屏支持。本轮仅修应用顶部切换HOME／LOCK时的装饰背景交接，不改变生产播放状态；实际完成情况见候选检查记录，不以此说明代替验证。

版本67按同身份同证书覆盖65／64／63／61设计，保HOME原文件名与Room8结构，不复制其数据到LOCK，不继承旧`.debug`沙箱；不自动卸载或清数据。版本66仅为本轮中间构建，保留未交付，不作为候选安装；后续应用上下文小修重新分配67，不复用不同APK的版本号。用户已回传65原生双域用例通过并指出LOCK编辑未开放，故其逐图停用和停用列表缩略图／恢复子项仍未验证，不将整体pass用于补齐。P10采用原生显示＋按需Root输入，编辑先经系统正常安全解锁；本轮Root锁屏输入和独立安全编辑尚未完成，不开放会显示HOME的透明LOCK编辑入口。完整P10仍在实施，不把原生双域图片链当完整阶段通过。实际构建、候选核验和设备结果见根QUALITY_LEDGER与候选RESULTS，未执行均NOT_RUN，本文不代替实际检查。GitHub按用户选择沿用 `GokigenSumire/OS3_Wallpaper_Changer`，计划源码提交与APK发布，但来源许可／隐私审计和功能验收未闭前不推送半实现包或宣已发布。

构建固定 Gradle 9.7.1、AGP 9.4.1、JDK 17、API/target 37、Build Tools 37.0.0、Kotlin/serialization/Compose plugin 2.2.10、KSP 2.3.6、Room 2.7.2。本机构建进程显式使用用户安装的 JDK17，并将其 bin 放在 PATH 首位；SDK 路径保存在忽略的 `local.properties`，不自动安装 JDK/SDK。

主应用独立运行，不以 Root、LSPosed 或兼容模块为前置条件。只有系统桌面不向动态壁纸转发触摸、且确实需要兼容的设备，才可选安装 `personal.wallpaper.os4.e1` 模块；桌面手势正常的设备无需安装。设置页提供可选入口，不自动申请系统控制或启用兼容。

正式签名密钥与旧调试证书分离，由工作区本机私有目录保管，密码使用当前 Windows 用户的 DPAPI 保护；不进入源码与交付包。工作区执行 `tools/Invoke-FormalSignedBuild.ps1 -Role wallpaper -EvidenceDirectory <本轮证据目录>`，实际执行 `testReleaseUnitTest`、`lintRelease`、`compileDebugAndroidTestKotlin`、`assembleRelease --rerun-tasks`。签名工具和证据以工作区交接为准，源码复建需自行配置 SDK 及个人签名，不能获得原私钥。

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat :shimmer:testDebugUnitTest :shimmer:lintDebug :shimmer:assembleDebug
```

首次依赖下载如需本机已有代理，可仅为上述命令加 `-Dhttps.proxyHost` / `-Dhttps.proxyPort` 参数；本輪实际环境和命令见工作区 `docs/evidence/P0/BUILD_ENVIRONMENT_2026-09-22.md`。构建不会安装 APK 或操作手机。正式身份 `personal.wallpaper.os3`，调试身份 `personal.wallpaper.os3.debug`，debug 与 release 密钥分开；只安装候选目录中已核验哈希的 APK。不要删除或替换本机 debug 密钥；同包名不同签名不能覆盖升级，原 Shimmer 数据不自动导入。

## Shimmer 上游说明（保留）

![shimmer_logo](https://github.com/user-attachments/assets/6490fdd3-4b6a-45ba-8b4c-d74d2146cd61)

# Shimmer（上游说明）

一款现代 Android 动态壁纸应用，使用平滑的 GPU 效果和过渡来呈现照片。

## 功能

- **动态图片**：从本地文件夹加载照片，支持不重复随机选择和自动幻灯片播放。
- **平滑过渡**：交叉淡化动画和响应桌面滑动的视差滚动。
- **硬件加速**：使用 OpenGL ES 3.0 提供高性能效果并尽量降低电量消耗。
- **智能随机**：Shimmer 轮换选择文件夹并记录“上次显示”时间，确保集合中的每张图片都显示过后才重复。

### 视觉效果

- **高斯模糊**：可调节强度，并支持智能超时和锁屏触发。
- **胶片颗粒**：可定制的复古纹理。
- **双色调**：提供 25 个以上预设和多种混合模式，为图片着色。
- **色差**：响应多点触控手势的交互式 RGB 畸变。
- **调暗**：平滑调暗壁纸，提高图标和小组件的可读性。

### 交互

- **手势**：可自定义三击和多指双击操作（下一张、切换模糊、随机双色调、加入收藏）。
- **分享**：支持 `ACTION_SEND` 和 `ACTION_SEND_MULTIPLE`，可从任意应用分享图片作为候选壁纸。
- **自动化**：集成解锁、锁屏等系统事件。

### 特殊集合

- **收藏夹**：通过手势或快捷方式将喜欢的壁纸快速保存到专用文件夹。
- **Shared**：通过系统“分享”菜单，直接从图库或其他应用将图片发送到 Shimmer。图片会进入特殊的“Shared”集合，便于访问。

## 高级功能：自动化与 Intent

Shimmer 支持若干广播 Intent，可通过 **Tasker**、**MacroDroid** 或 ADB 从外部控制壁纸。

### 操作

| 操作 Action | 说明 | 参数 Extras |
| :--- | :--- | :--- |
| `dev.abdus.apps.shimmer.action.NEXT_IMAGE` | 切换到下一张随机图片。 | - |
| `dev.abdus.apps.shimmer.action.RANDOM_DUOTONE` | 切换到随机双色调预设。 | - |
| `dev.abdus.apps.shimmer.action.SET_BLUR_PERCENT` | 立即设置模糊程度。 | `blur_percent`（Float：0.0 到 1.0） |
| `dev.abdus.apps.shimmer.action.ENABLE_BLUR` | 强制壁纸进入模糊状态。 | - |
| `dev.abdus.apps.shimmer.action.REFRESH_FOLDERS` | 触发重新扫描图片文件夹。 | - |
| `dev.abdus.apps.shimmer.action.ADD_TO_FAVORITES` | 将当前图片保存到收藏夹目录。 | - |

### 对外广播

当图片加入收藏夹时，Shimmer 会广播：
`dev.abdus.apps.shimmer.action.FAVORITE_ADDED`
- `favorite_uri`：已保存文件的 URI。
- `favorite_display_name`：文件名。

## 环境要求

- Android 8.0（API 26）或更高版本。
- 访问照片文件夹所需的存储权限。

## 许可证

Copyright (c) 2025 Abdussamet Kocak

本项目采用 [知识共享署名-非商业性使用-相同方式共享 4.0 国际许可协议](https://creativecommons.org/licenses/by-nc-sa/4.0/) 授权。

### 当前许可与液态玻璃移除

2026-10-16 按用户要求，当前 P9 工作分支移除液态玻璃开关、折射实现、背板接线和直接 `miuix-blur` 构建依赖；这不修改已冻结旧候选及其历史来源记录。半透明卡片、壁纸背景模糊与悬浮底栏不等于液态玻璃，按各自实际来源继续管理。

本次复核发现，先前把 `miuix-blur-android:0.9.0` 一概写成 GPL-3.0 不准确：本机缓存中该精确版本的发布 POM 声明 **Apache-2.0**，且其 Gradle 模块元数据未声明 `miuix-shader-android` 为依赖。其他已缓存版本不代表本构建实际解析版本。完整证据和哈希记录于工作区的 `docs/evidence/P9/GLASS_REMOVAL_2026-10-16/LICENSE_AUDIT.md`。纠正错误归类不等于给本应用换许可证。

Shimmer 派生部分仍保留 **CC BY-NC-SA 4.0** 与原作者署名，不擅自改为 MIT 或 GPL。当前悬浮底栏仍有 KernelSU 来源声明；其上游仓库 GPL-3.0 与文件头的 Apache-2.0 来源说明需要继续核定，不能仅因删除玻璃就宣称所有 GPL 来源风险已清零。公开 GitHub 源码、APK 或其他分发前，须完成剩余来源／许可兼容核定、归属声明和隐私排除；本轮不执行公开推送。可选兼容模块是独立应用，其自有 GPL-3.0 许可不自动改变主壁纸派生源码的授权。
