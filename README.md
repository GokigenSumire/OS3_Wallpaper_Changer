# Hyper Wallpaper Changer 壁纸切换器

面向 Android 的个人动态壁纸项目，支持图片、视频与动态照片来源管理、壁纸切换和实时画面调整，基于 Shimmer 修改。

## 当前版本与下载

主应用 **118／1.4.11-p11-fresh-gallery**，正式包名personal.wallpaper.os3，原签名覆盖升级；配套兼容模块 **100／1.1.25-independent-controls**，独立安装、按需开启。minSdk31／targetSdk37，Room数据库9→10为保留数据的升级。

- [主118 APK、对应源码与说明](https://github.com/GokigenSumire/OS3_Wallpaper_Changer/releases/tag/118-1.4.11-p11-fresh-gallery)
- [模块100独立发布](https://github.com/GokigenSumire/OS4_E1_Observer/releases/tag/100-1.1.25-independent-controls)
- [主118发布说明与验证边界](docs/releases/主118-模块100.md)

## 主118与模块100界面优化

主图标改为浅色柔光、天蓝与薄荷绿的壁纸窗口；[117深蓝图标已备案](docs/design/图标备选/117-深蓝层叠画框/说明.md)。图库总开关、类别和内容分层，图片／视频采用可见选中状态的紧凑选项；扫描、预览与移除收进菜单，重复文字减量，位置与必要说明按需打开。

模块桌面与锁屏分别显示自己的开关和状态。桌面选择先保存，系统未就绪以提示说明，开启中仍可改为关闭；旧回执不能覆盖新选择，实际开启仍受原恢复、版本与安全准入控制。主页面保留必要操作，说明与诊断按需打开。

![主118图标资源示意](docs/design/主118图标预览.png)

图标预览为资源示意，实际蒙版、主题配色及本轮功能待目标机验收。本轮不增加数据库迁移，Room10保持；自动检查结果以本轮Release附件为准，旧版本成绩不迁移。

## 本轮与近期改进

- 多图与文件夹来源增加图片／视频两个独立开关，默认均开；动态照片归图片。分类、目录和单项停用分别保留，关闭分类不删除记录与排序；桌面与锁屏独立。
- 图库和播放页统一目录层级、停用与播放同行、媒体逐项声音开关；叶级目录不再显示不可用展开箭头。
- 实际壁纸三手势引导、悬浮目标切换及过渡、当前界面每日定时和诊断P0～P11入口。
- 关于卡片提供版本复制、项目链接、手动检查更新和发布下载；主应用改为层叠画框图标，含适配与单色资源。

## 安装与验证边界

同正式包名与签名覆盖旧主应用，保留来源、历史、单项启停及数据，不卸载或清数据。Room10新增来源分类及类型元数据；不支持直接降级至旧数据库版本，异常采用后续修复版本。

主应用可独立使用；有需要时安装Root／LSPosed可选模块100，保留系统框架和SystemUI作用域并按提示正常重载。进入锁屏编辑仍需要正常系统认证。模块100关于更新为独立发布，当前LSPosed收录例外申请待审核，不表示已获收录。

自动检查、测试卡、APK身份、签名与哈希见Release附件。新候选目标机体验均未验证，旧版本用户结果不迁移；主118Release完整检查未运行，完整Debug结果在发布说明中记录，P10历史未决项保留。保留历史[主105发布](https://github.com/GokigenSumire/OS3_Wallpaper_Changer/releases/tag/v1.3.7-p11-r105-m97)。

## 历史说明与构建资料

以下保留旧版记录，当前入口和验证边界以上方主118说明为准。

## 主要功能

- 管理图片、视频、内嵌动态照片和文件夹／子目录来源，支持顺序、随机和手动排序。
- 视频静音循环；动态照片可选循环、播放一次后回原照片或静态显示。
- 可分别设置锁屏后、解锁后从头播放，默认关闭。
- 双击下一张、三击上一张、两指双击进入编辑。
- 实时调整图片缩放、位置及显示方式，支持过渡效果和自动切换。
- 编辑工具支持纯预览、上下停靠和状态记忆，可设置切图保存／不保存／询问。
- 桌面与锁屏分别保存来源、播放状态和编辑设置。

## 当前源码与正式发布

| 组件 | 版本 | 使用条件 |
| --- | --- | --- |
| 主壁纸当前源码／Release | 105／1.3.7-p11-editor-swap | 可独立使用；快速编译验证与实机待验范围见[记录](docs/验收/P11-主105编辑器.md) |
| 主壁纸历史Release | 102／1.3.4-p11-header | 原已验收发布保留 |
| 主壁纸历史Release | 94／1.2.6-p10-input-priority | 原发布包与记录保留 |
| [可选兼容模块](https://github.com/GokigenSumire/OS4_E1_Observer) | 97／1.1.22-gesture-continuity | 需要Root及LSPosed，仅在设备需要兼容时安装 |

最低 Android 12（API31），targetSdk37；主要实测设备为小米17 Pro／Android16。不同系统的锁屏支持以实际验证为准。

版本说明与安装包见 [Releases](https://github.com/GokigenSumire/OS3_Wallpaper_Changer/releases)。当前发布[主105／模块97](https://github.com/GokigenSumire/OS3_Wallpaper_Changer/releases/tag/v1.3.7-p11-r105-m97)，变化和验证边界见[发布说明](docs/releases/主105-模块97.md)；[主102历史发布](https://github.com/GokigenSumire/OS3_Wallpaper_Changer/releases/tag/v1.3.4-p11-r102-m97)保留。[模块源码仓库](https://github.com/GokigenSumire/OS4_E1_Observer)与[模块97独立Release](https://github.com/GokigenSumire/OS4_E1_Observer/releases/tag/v1.1.22-p10-m97)继续提供原配套文件。

## 安装与使用

1. 安装主应用，在图库选择媒体来源，再通过播放页的系统壁纸入口应用到桌面或锁屏。
2. 从较早主包覆盖升级至主105，保留原数据与来源授权；已安装模块97可保留，首次加载模块按原流程正常重启。
3. 模块作用域保留“系统框架／Android”和“系统界面／SystemUI”。按需开启“原生锁屏业务输入”与“锁屏手势最高优先级”。

最高优先级下，系统动作可能与壁纸手势同时响应；进入锁屏图片编辑仍需系统正常解锁。升级请保留原数据和来源授权。

## 验证状态

主103编辑器已获用户汇总PASS；主104最终Debug／Release各2231项单测通过，lint均0错误／153警告，目标机尚未回传。当前主105只对调工具栏首尾按钮，按用户要求快速增量编译和定向验证；主105本轮全量双变体／lint／正式门禁及实机为NOT_RUN，完整范围见[验证记录](docs/验收/P11-主105编辑器.md)。原主102两批用户通过和发布记录保留，不迁移旧版成绩。

主94／模块97已获小米17 Pro用户反馈“测试基本符合预期”。主应用2136项、模块1324项单测通过；联合门禁340项及45＋10项有效定向通过。原完整反例695/696匹配，断言修正后的单项定向复验已通过，完整696项复验及最终候选绑定仍待完成。本次正式发布保留这两项核验债务，P10仍在进行，未逐项回传的用例不补记通过。

## 本地构建

使用JDK17、Android SDK37、Build Tools37.0.0；Gradle9.7.1和AGP9.4.1已固定。在本机`local.properties`配置SDK路径后执行：

```powershell
.\gradlew.bat :shimmer:testDebugUnitTest :shimmer:lintDebug :shimmer:assembleDebug
```

正式签名使用本机独立配置，不随源码提供。GitHub Actions自动构建及上传保持禁用；发布准备流程见[GitHub配置说明](.github/SETUP.md)。

## 来源与许可

基于[Shimmer](https://github.com/abdusco/shimmer)修改，原作者：Copyright (c) 2025 Abdussamet Kocak。保留原 [CC BY-NC-SA 4.0许可证](LICENSE) 和署名。

悬浮底栏等当前剩余来源已完成[逐段核对](docs/legal/来源与许可核对.md)，保留Apache示例、KernelSU参考及其他上游归属；原许可和历史说明见[第三方来源与许可](THIRD_PARTY_NOTICES.md)。兼容模块采用其自身GPL-3.0许可。
