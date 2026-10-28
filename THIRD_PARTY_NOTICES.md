# 第三方来源与许可

日期：2026-10-16。适用于当前工作源码；已冻结旧 APK、源码归档和历史许可记录不随本说明改变。本文件是工程来源核对，不是权利人的额外授权或法律意见。

## 主工程的来源与许可

主工程基于 Shimmer，原作者 `Copyright (c) 2025 Abdussamet Kocak`。工作区保留来源工程 `参考源码/app/shimmer-master`；其 `LICENSE` 与本目录 [LICENSE](LICENSE) 的 SHA-256 相同：`35F64921BE5C751CFC14DC8186F2CA70222C60631B8E4263265030EED672ADB1`。当前原始参考目录没有 Git 元数据，不猜测其官方仓库地址；公开前补核准确来源 URL 与所用版本。

来源许可为 [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/legalcode)，保留署名、原许可链接、非商业及相同方式共享条件。本项目的来源管理、播放历史、编辑、调度、新界面与诊断等修改已在工作区质量台账、候选源码清单与差异中记录；没有把原作者写成这些修改的作者。

## 液态玻璃的移除与历史归类纠正

- 当前 P9 工作分支按用户要求移除 `LiquidGlass.kt`、`GlassSurface.kt`、相关设置／背板接线和直接 `miuix-blur` 依赖；旧候选仍原样保留，不能借新说明替换旧包中的代码事实。
- 先前 README 将 `top.yukonga.miuix.kmp:miuix-blur-android:0.9.0` 写成 GPL-3.0 不准确。本机已缓存精确发布 POM 声明 Apache-2.0；`0.9.0` 模块元数据只列 Kotlin 与 Compose Foundation，未列 `miuix-shader-android`。本次同时读取的 `miuix-shader-android:0.9.3` POM 也声明 Apache-2.0，但缓存存在不能证明曾进入任一指定 APK。
- 移除的折射实现原声明为经 [KernelSU](https://github.com/tiann/KernelSU) 的实现移植，并追溯 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) 的 Apache-2.0 来源。此处保留历史归属，不把移除当作抹去来源。
- 仍保留的 `ui/components/BlurredNavigationBar.kt` 声明参考 KernelSU 的悬浮底栏。核到的 KernelSU 仓库 `LICENSE` 是 GPL-3.0；其 `FloatingBottomBar.kt` 首行另写明改编自 Apache-2.0 的 `compose-miuix-ui` 示例。当前未取得逐表达来源／全部修改的完整授权链，不把仓库许可或单行说明直接套为整文件唯一结论。仅借鉴交互思想和复制受保护的表达应分开核定。

因此，主工程仍按既有个人非商业边界使用，**尚不宣称整体 GPL 风险清零或已满足公开分发条件**。公开前可选择：取得准确兼容授权／来源证明；确认剩余内容仅为不受版权保护的行为思想；或由未接触受限表达的独立实现者按行为规格替换相关实现，再复核。不能只删版权头、把文件改名或把依赖设为关闭。

## 保留的其他来源

| 来源或依赖 | 本轮核到的许可／边界 | 保留要求 |
| --- | --- | --- |
| `res/drawable/icon_two_fingers.xml` | MIT，`Copyright (c) 2020-2025 Paweł Kuna`，完整许可已在文件头 | 保留该文件的版权与 MIT 许可 |
| AndroidX、Compose／Material3、Room、DocumentFile、Gradle Wrapper、Kotlin、kotlinx.serialization | 主要上游声明 Apache-2.0；具体传递版本以本候选解析结果和发布元数据为准 | 保留对应许可证／必要 NOTICE，不把本表当完整逐依赖审计 |
| Coil Compose `2.6.0` | 已缓存精确 POM 声明 Apache-2.0 | 保留发布依赖归属 |
| `libandroidx.graphics.path.so` | 旧正式主包包含 AndroidX 依赖的四个 ABI 副本，不是自制本地引擎 | 新候选继续按实际依赖与 APK 内容核来源、许可和 16 KB 页适配 |
| JUnit、Robolectric、AndroidX Test、Room Testing | 仅电脑测试或测试 APK 的依赖，不据此声称进入正式 APK | 按实际测试发布物保留原条款 |
| Android SDK 与系统参考材料 | SDK 原条款；OEM APK／DEX／库没有因被读取而获得再分发授权 | 不把设备提取件、反编译产物或私人日志放入公开仓库 |

`sb` 与 Wallpaper Engine 仅为行为参考，不能把反编译所得代码或第三方许可证清单当整个应用的复制授权。可选 LSPosed 兼容模块 `personal.wallpaper.os4.e1` 是独立应用，许可见其自身 `LICENSE` 和 `THIRD_PARTY_NOTICES.md`，不是主包的运行前置条件，也不自动给主工程再许可。

## GitHub 发布前的必要核对

1. 完成上述剩余悬浮底栏来源边界，确定相容许可，保留 Shimmer 与其他上游署名／许可／修改说明；CC BY-NC-SA 不应描述成无限制的开源许可或商用许可。
2. 对当前候选运行真实依赖解析与 APK 检查；确认液态玻璃依赖／类不再进入新包。缓存目录中仍有旧依赖不表示新包仍使用，构建文件无引用也不单独证明打包内容。
3. 只发布已审过的源码白名单与必要文档。排除签名私钥、DPAPI 密码、SDK 路径、私人图片／目录、诊断原件、手机系统提取物与历史候选归档。
4. 用户确定仓库和公开范围后再执行 Git 提交／推送；本轮仅规划，不执行 GitHub 写入，也不改变原历史提交。
