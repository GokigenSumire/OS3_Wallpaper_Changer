# Hyper Wallpaper Changer 壁纸切换器

面向 Android 的个人动态壁纸项目，支持图片、视频与动态照片来源管理、壁纸切换和实时画面调整，基于 Shimmer 修改。

## 主要功能

- 管理图片、视频、内嵌动态照片和文件夹／子目录来源，支持顺序、随机和手动排序。
- 视频静音循环；动态照片可选循环、播放一次后回原照片或静态显示。
- 可分别设置锁屏后、解锁后从头播放，默认关闭。
- 双击下一张、三击上一张、两指双击进入编辑。
- 实时调整图片缩放、位置及显示方式，支持过渡效果和自动切换。
- 桌面与锁屏分别保存来源、播放状态和编辑设置。

## 当前源码与正式发布

| 组件 | 版本 | 使用条件 |
| --- | --- | --- |
| 主壁纸当前源码／Release | 102／1.3.4-p11-header | 可独立使用，无需Root或LSPosed；目标机反馈见[验收记录](docs/验收/P11-主102验收.md) |
| 主壁纸历史Release | 94／1.2.6-p10-input-priority | 原发布包与记录保留 |
| [可选兼容模块](https://github.com/GokigenSumire/OS4_E1_Observer) | 97／1.1.22-gesture-continuity | 需要Root及LSPosed，仅在设备需要兼容时安装 |

最低 Android 12（API31），targetSdk37；主要实测设备为小米17 Pro／Android16。不同系统的锁屏支持以实际验证为准。

版本说明与安装包见 [Releases](https://github.com/GokigenSumire/OS3_Wallpaper_Changer/releases)。当前正式发布[主102／模块97](https://github.com/GokigenSumire/OS3_Wallpaper_Changer/releases/tag/v1.3.4-p11-r102-m97)，变化与边界见[发布说明](docs/releases/主102-模块97.md)；[主94历史说明](docs/releases/主94-模块97.md)保留。[模块源码仓库](https://github.com/GokigenSumire/OS4_E1_Observer)与[模块独立Release](https://github.com/GokigenSumire/OS4_E1_Observer/releases/tag/v1.1.22-p10-m97)分别提供源码和配套安装文件。

## 安装与使用

1. 安装主应用，在图库选择媒体来源，再通过播放页的系统壁纸入口应用到桌面或锁屏。
2. 从较早正式主包覆盖升级至主102，保留原数据与来源授权；已安装模块97可保留，首次加载模块按原流程正常重启。
3. 模块作用域保留“系统框架／Android”和“系统界面／SystemUI”。按需开启“原生锁屏业务输入”与“锁屏手势最高优先级”。

最高优先级下，系统动作可能与壁纸手势同时响应；进入锁屏图片编辑仍需系统正常解锁。升级请保留原数据和来源授权。

## 验证状态

当前主102源码已获小米17 Pro两批用户反馈通过；Debug／Release各2202项单测、lint 0错误／148警告及29项交接门禁通过。视频／动态照片、重播与四栏布局的入口、证据粒度和未验证边界见[主102验收记录](docs/验收/P11-主102验收.md)。主102正式Release使用已测原APK，配套模块97原包保留。

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
