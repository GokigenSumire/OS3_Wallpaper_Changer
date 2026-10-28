# GitHub Actions 发布 APK 配置

## 当前P10实际状态

当前`workflows/build-release.yml`只有`workflow_dispatch`，且任务明确`if: ${{ false }}`，自动构建／上传处于禁用状态。创建Release不会按本说明自动构建。用户已选择沿用`GokigenSumire/OS3_Wallpaper_Changer`、后续提交源码和发布APK，但能力候选未完成完整锁屏功能，来源许可与隐私／历史白名单仍待核，尚未提交、推送或上传。

本机正式密钥与DPAPI凭据仅本地保管，不上传仓库或因本说明配置云端密钥。发布前先核准确源码与许可、排除私钥／手机材料／系统提取件，使用同一经过验证的APK和SHA；是否启用CI及采用何种签名托管需另定，不自动启用工作流。

## 上游发布配置示例（历史保留，当前不执行）

以下为原说明，不能作为当前自动发布已开启或已授权上传密钥的依据。当前真实构建参数与签名方式以主README、根质量台账和候选证据为准。

## 前置条件

需要在 GitHub 仓库中配置以下机密值：

1. 打开仓库的 GitHub 页面。
2. 进入“设置”（Settings）→“机密和变量”（Secrets and variables）→ Actions。
3. 添加下面列出的仓库机密值。

### 必需的机密值

#### `KEYSTORE_BASE64`

以 base64 编码的签名 keystore。

生成示例：

```bash
base64 -i your-keystore.jks | pbcopy
```

将输出粘贴到对应的机密值中。

#### `KEYSTORE_PASSWORD`

密钥库文件的密码。

#### `KEY_ALIAS`

密钥别名。

#### `KEY_PASSWORD`

密钥密码。

## 使用方法

### 创建发布版本

1. 创建并推送 Git 标签：

   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```

2. 在 GitHub 仓库进入“发布版本”（Releases）→“起草新版本”（Draft a new release）。
3. 选择刚创建的标签（例如 `v1.0.0`）。
4. 填写发布标题和说明。
5. 点击“发布版本”（Publish release）。
6. GitHub Actions 会自动构建发布版 APK、根据提交信息生成变更日志，并将 APK 上传到该发布版本。

### 生成变更日志

Action 会根据提交信息自动生成变更日志。建议：

- 功能提交使用 `feat:` 前缀（例如 `feat: 添加模糊效果`）。
- 修复提交使用 `fix:` 前缀（例如 `fix: 修复渲染器内存泄漏`）。

这些提交会按类别归入变更日志。

## 本地测试

如需使用相同参数检查构建是否可行：

```bash
./gradlew shimmer:assembleRelease \
  -Pversion.name=1.0.0 \
  -Pversion.code=1
```

## 构建产物

成功发布后，可以在以下位置找到产物：

- GitHub Release 附带的 APK。
- Actions 页面中保留 90 天的构建产物。
