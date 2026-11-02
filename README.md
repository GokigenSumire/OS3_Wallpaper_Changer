# Hyper Wallpaper Changer 壁纸切换器

面向 Android 的个人动态壁纸项目，支持图片来源管理、壁纸切换和实时画面调整，基于 Shimmer 修改。

## 主要功能

- 管理单图、文件夹及子目录来源，支持顺序、随机和手动排序。
- 双击下一张、三击上一张、两指双击进入编辑。
- 实时调整图片缩放、位置及显示方式，支持过渡效果和自动切换。
- 桌面与锁屏分别保存来源、播放状态和编辑设置。

## 当前版本

| 组件 | 版本 | 使用条件 |
| --- | --- | --- |
| 主壁纸 | 94／1.2.6-p10-input-priority | 可独立使用，无需Root或LSPosed |
| [可选兼容模块](https://github.com/GokigenSumire/OS4_E1_Observer) | 97／1.1.22-gesture-continuity | 需要Root及LSPosed，仅在设备需要兼容时安装 |

最低 Android 12（API31），targetSdk37；主要实测设备为小米17 Pro／Android16。不同系统的锁屏支持以实际验证为准。

版本说明与安装包见 [Releases](https://github.com/GokigenSumire/OS3_Wallpaper_Changer/releases)。当前为P10预发布，详细验证状态见[主94／模块97发布说明](docs/releases/主94-模块97.md)。[模块源码仓库](https://github.com/GokigenSumire/OS4_E1_Observer)独立管理，对应源码归档随配套版本提供。

## 安装与使用

1. 安装主应用，选择图片来源，再通过系统壁纸选择器应用到桌面或锁屏。
2. 已安装主94的设备无需重装同一主包；需要锁屏兼容时，可单独更新模块97并正常重启，使模块重新加载。
3. 模块作用域保留“系统框架／Android”和“系统界面／SystemUI”。按需开启“原生锁屏业务输入”与“锁屏手势最高优先级”。

最高优先级下，系统动作可能与壁纸手势同时响应；进入锁屏图片编辑仍需系统正常解锁。升级请保留原数据和来源授权。

## 验证状态

主94／模块97已获小米17 Pro用户反馈“测试基本符合预期”。主应用2136项、模块1324项单测通过；联合门禁340项及45＋10项有效定向通过。完整反例695/696匹配，剩余一项门禁断言待修复复验。P10仍在进行，未逐项回传的用例不补记通过。

## 本地构建

使用JDK17、Android SDK37、Build Tools37.0.0；Gradle9.7.1和AGP9.4.1已固定。在本机`local.properties`配置SDK路径后执行：

```powershell
.\gradlew.bat :shimmer:testDebugUnitTest :shimmer:lintDebug :shimmer:assembleDebug
```

正式签名使用本机独立配置，不随源码提供。GitHub Actions自动构建及上传保持禁用；发布准备流程见[GitHub配置说明](.github/SETUP.md)。

## 来源与许可

基于[Shimmer](https://github.com/abdusco/shimmer)修改，原作者：Copyright (c) 2025 Abdussamet Kocak。保留原 [CC BY-NC-SA 4.0许可证](LICENSE) 和署名。

悬浮底栏等当前剩余来源已完成[逐段核对](docs/legal/来源与许可核对.md)，保留Apache示例、KernelSU参考及其他上游归属；原许可和历史说明见[第三方来源与许可](THIRD_PARTY_NOTICES.md)。兼容模块采用其自身GPL-3.0许可。
