# 第三方来源与许可

日期：2026-11-02。适用于当前工作源码；已冻结旧 APK、源码归档和历史许可记录不随本说明改变。本文件是工程来源核对，不是权利人的额外授权或法律意见。逐段对照、固定来源和检查范围见[本轮核对报告](docs/legal/来源与许可核对.md)。

## 主工程的来源与许可

主工程基于 [abdusco/shimmer](https://github.com/abdusco/shimmer)，原作者 `Copyright (c) 2025 Abdussamet Kocak`。工作区保留来源工程 `参考源码/app/shimmer-master`；其 `LICENSE` 与本目录 [LICENSE](LICENSE) 的 SHA-256 相同：`35F64921BE5C751CFC14DC8186F2CA70222C60631B8E4263265030EED672ADB1`。参考源码内容基线已定位到上游提交 `69b2f0931d9292065f7507a66247ad8a1ca9e1fb`：105个受控文件逐字节一致，仅 README 与 GitHub 配置说明两份文档不同；不把整个含本地补充文件的目录冒充原始 checkout。

来源许可为 [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/legalcode)，保留署名、原许可链接、非商业及相同方式共享条件。本项目的来源管理、播放历史、编辑、调度、新界面与诊断等修改已在工作区质量台账、候选源码清单与差异中记录；没有把原作者写成这些修改的作者。

## 液态玻璃的移除与历史归类纠正

- 当前 P9 工作分支按用户要求移除 `LiquidGlass.kt`、`GlassSurface.kt`、相关设置／背板接线和直接 `miuix-blur` 依赖；旧候选仍原样保留，不能借新说明替换旧包中的代码事实。
- 先前 README 将 `top.yukonga.miuix.kmp:miuix-blur-android:0.9.0` 写成 GPL-3.0 不准确。本机已缓存精确发布 POM 声明 Apache-2.0；`0.9.0` 模块元数据只列 Kotlin 与 Compose Foundation，未列 `miuix-shader-android`。本次同时读取的 `miuix-shader-android:0.9.3` POM 也声明 Apache-2.0，但缓存存在不能证明曾进入任一指定 APK。
- 移除的折射实现原声明为经 [KernelSU](https://github.com/tiann/KernelSU) 的实现移植，并追溯 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) 的 Apache-2.0 来源。此处保留历史归属，不把移除当作抹去来源。
- 仍保留的 `ui/components/BlurredNavigationBar.kt` 原 KernelSU 参考说明未删。实际参考文件已与官方提交 `7bab12df939668a4406cec4c07f4e2b3c555e6b7` 下的同路径文件逐字节对应；manager 的 GPL-3.0-or-later 声明保留，不能把首行 Apache 示例说明套给其全部增量。
- 本轮逐段核对当前底栏、MainActivity、BlurEffect 与 Shape，并核两次 KernelSU 后续补丁。当前保留的胶囊布局、拖动换算和指示器结构可追溯到 [miuix 的 Apache 示例](https://github.com/compose-miuix-ui/miuix/blob/23b469ee404b0ea9737e225bbd87d224226a3efa/example/shared/src/commonMain/kotlin/component/liquid/LiquidGlassNavigationBar.kt)，未发现只能由 KernelSU GPL 增量解释的保留实现。单一 Animatable、防回弹、项目外观状态与尺寸等改动已在报告分段列明；结论是限定工程对照，不是对 KernelSU 整文件再许可或全部历史的授权证明。

主工程仍保留既有个人非商业及 CC BY-NC-SA 边界。此前剩余四文件的工程来源核对已完成；不宣称全部旧候选／Git历史的 GPL 风险清零，也不把仓库公开或本报告当作所有发布门禁通过。未通过删除版权头、改名或关闭依赖制造来源清零结论。

保留 `Copyright 2026, compose-miuix-ui contributors`、`SPDX-License-Identifier: Apache-2.0` 与 `Copyright 2025 Kyant`；对应原文分别见 [miuix 的 Apache-2.0](licenses/Apache-2.0-miuix.txt)和[Kyant 的 Apache-2.0](licenses/Apache-2.0-Kyant.txt)。Apache部分继续适用其原条款，不以本项目非商业条件限制对应原始第三方材料。

## 保留的其他来源

| 来源或依赖 | 本轮核到的许可／边界 | 保留要求 |
| --- | --- | --- |
| `res/drawable/icon_two_fingers.xml` | MIT，`Copyright (c) 2020-2025 Paweł Kuna`，完整许可已在文件头及[原文副本](licenses/MIT-Tabler.txt) | 保留该文件的版权与 MIT 许可 |
| AndroidX、Compose／Material3、Room、DocumentFile、Gradle Wrapper、Kotlin、kotlinx.serialization | 主要上游声明 Apache-2.0；具体传递版本以本候选解析结果和发布元数据为准 | 保留对应许可证／必要 NOTICE，不把本表当完整逐依赖审计 |
| Coil Compose `2.6.0` | 已缓存精确 POM 声明 Apache-2.0 | 保留发布依赖归属 |
| OkHttp `4.12.0` 内的 Public Suffix List | 主库 POM 为 Apache-2.0，后缀数据自身 NOTICE 为 MPL-2.0，原主94 APK 已保留该 NOTICE | 保留[原 NOTICE 副本](licenses/NOTICE-OkHttp-publicsuffix.txt)、[MPL 全文](licenses/MPL-2.0.txt)及其来源获取链接，不把数据改成 CC 许可 |
| `libandroidx.graphics.path.so` | 旧正式主包包含 AndroidX 依赖的四个 ABI 副本，不是自制本地引擎 | 新候选继续按实际依赖与 APK 内容核来源、许可和 16 KB 页适配 |
| JUnit、Robolectric、AndroidX Test、Room Testing | 仅电脑测试或测试 APK 的依赖，不据此声称进入正式 APK | 按实际测试发布物保留原条款 |
| Android SDK 与系统参考材料 | SDK 原条款；OEM APK／DEX／库没有因被读取而获得再分发授权 | 不把设备提取件、反编译产物或私人日志放入公开仓库 |

`sb` 与 Wallpaper Engine 仅为行为参考，不能把反编译所得代码或第三方许可证清单当整个应用的复制授权。可选 LSPosed 兼容模块 `personal.wallpaper.os4.e1` 是独立应用，许可见其自身 `LICENSE` 和 `THIRD_PARTY_NOTICES.md`，不是主包的运行前置条件，也不自动给主工程再许可。

## GitHub 发布前的必要核对

### 视频与动态照片候选的新增依赖

P11 主98源码增加 AndroidX Media3 `1.11.1`（Android Open Source Project）和 Coil Video `2.6.0`（Coil Contributors），均按各自 Apache-2.0 声明使用，原主工程与其他材料的署名／许可继续保留。原许可正文见 [Apache-2.0 副本](licenses/Apache-2.0-miuix.txt)。当前候选的完整解析坐标、归档 SHA-256、精确 POM 与父 POM 许可链见 [P11依赖清单](docs/legal/P11-依赖许可清单.json)，来源与使用边界见 [P11核对说明](docs/legal/P11-来源与许可.md)。旧90坐标清单保留为此前核对记录。

本项目新增的播放控制、动态照片范围探测与 GPU 接线由项目实现；Media3 官方源码与格式资料用于 API／封装核对。运行时使用 Android 系统解码器，未打包 FFmpeg、小米私有 SDK、OEM 提取代码或 Apple 私有实现。电脑端合成测试样本所用工具不进入应用依赖或源码归档。

1. 当前四文件的剩余来源核对已完成，固定来源、修改说明和范围见[核对报告](docs/legal/来源与许可核对.md)。继续保留各自署名／原许可；CC BY-NC-SA 不应描述成无限制的开源许可或商用许可。历史旧包不随本次结论改写。
2. 本轮实际解析当前正式运行依赖，91条原始记录去重后90归档／90坐标均已核公开 POM，父POM继承及MPL数据例外单列于[依赖许可清单](docs/legal/依赖许可清单.json)。未见miuix／KernelSU／液态玻璃运行依赖；原主94 APK 的只读包名检查亦未见对应类。当前解析不冒充旧构建锁定清单，新候选仍需自己的检查。
3. 只发布已审过的源码白名单与必要文档。排除签名私钥、DPAPI 密码、SDK 路径、私人图片／目录、诊断原件、手机系统提取物与历史候选归档。
4. 用户已授权并完成两库公开。后续只提交经过审阅的文档／源码；本说明不改原历史提交、原 APK 或既有发布附件。本次来源核对不代替完整反例复验、最终绑定或用户阶段决定。
