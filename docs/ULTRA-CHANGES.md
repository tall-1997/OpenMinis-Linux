# ultra 分支修改清单（基线：上游 OpenMinis 1.15，`1a71df0`）

> 本文件记录 ultra 分支相对上游 OpenMinis/OpenMinis `main`（基线 tag：1.15，提交 `1a71df0`）
> 的**全部修改**，用于后续跟随上游更新基线时快速定位冲突面与重新移植点。
> 每次基线变更（rebase 到新上游 tag）后请同步更新本文件。

## 一、基线与版本方案

| 项 | 值 | 说明 |
|---|---|---|
| 基线 | 上游 `1a71df0`（1.15，versionCode 37） | `git merge-base ultra origin/main` 应始终等于或接近基线 |
| applicationId | `com.openminis.ultra` | 独立安装，与 `com.openminis.app` / `com.openminis.linux` 并存，数据不迁移 |
| namespace | `com.openminis.app`（不变） | 与上游一致，JNI/R 类/MainActivity 类名都不受影响 |
| versionCode | `37001` | 方案：上游 versionCode × 1000 + ultra 后缀（001-999） |
| versionName | `1.15.001` | 上游版本名 + ultra 后缀；上游升 1.16 时改为 `1.16.001`（versionCode 38001） |
| 签名 | fork 仓库的 `src/android/release.keystore`（跟踪在 git） | 密码从 `MINIS_UPLOAD_*` 环境变量或 `src/android/signing.properties` 读取；debug 兜底 |

## 二、提交清单（按移植批次）

| 提交 | 主题 | 关键文件 |
|---|---|---|
| `0271667` | 人格文件长度不再截断 | `agent/SoulStore.kt`（isOverLimit 恒 Ok）、`config/ConfigBuiltins.kt`（soul.body）、`ui/settings/SoulSettingsScreen.kt`、strings |
| `5916f0d` | 自定义导入人格文件 | 新增 `agent/PersonaPromptLibrary.kt`、`agent/PersonaSessionOverride.kt`、`tools/AtomicFileWrite.kt`、`ui/settings/SoulPromptEditorScreen.kt` + 3 个单测；`SoulStore.identitySection` 改三参；`ChatViewModel.buildSystemPrompt` 接线 |
| `b5676d2` | 厂商图标资源 | `res/raw` 358 + `res/raw-night` 137 个 `ic_brand_*.svg`；coil-svg 依赖 + `MinisApp` SvgDecoder |
| `585903a` | 图标核心层 | 新增 `ui/chat/ModelBrandIconMap/Resolver/Icon`、`BrandAccent`、`VendorMark`、`tools/BrandKeywordSources` + 契约测试 |
| `1fabe8f` | 图标接线 | `ChatModelPickerSheet`（3 处）、`ProviderDetailScreen`、`UsageStatsScreen`、`ChatScreen` 顶栏；`SettingsRow` 增加 leading 槽位 |
| `a5d65dd` | Root/su 首选提权 | 新增 `sandbox/offload/SuOffloadHandler.kt`、`offload/HostSuFallback.kt`、`HostSuManager.kt`、`sandbox/offload/OffloadDenial.kt`、`sandbox/BoundedOutputBuffer.kt`、`security/ShellArgv.kt`；`ShizukuManager.runProcess` lane 分派；`MinisApp` 注册 android-su/su |
| `5dca73d` | Ubuntu 24.04 沙箱 | `scripts/prepare_android_sandbox.sh` 重写；`RootfsManager` ubuntu 化；新增 `PRootArgv`/`RootfsTar`/`RootfsStaging`/`GuestAptScript`/`CaBundle`/`OpenSslSubjectHash`/`SandboxResourceGate`；`PRootKernel` 三路合并；`assets/default_mount` 新增 apt/CA/mirror 资产组；gradle noCompress + tgz |
| `94e4f8e` | CI 流水线 | `.github/workflows/android-apk.yml`（ultra 版）、`native-deps.yml`、`sandbox-assets.yml`、`scripts/ci/ensure-build-tools.sh`、`scripts/build_libunwind_aarch64.sh` |
| `51f1374` | 签名 keystore | `src/android/release.keystore`（fork 的公开上传证书） |
| `8378891` `be2323e` | 云端编译修复 | PRootKernel/RootfsManager 合并区修复（详见各提交说明） |
| `b6bdc6d` | 系统提示 Ubuntu 化 | SoulStore 身份模板、HelperRunner、AgentTools、ChatViewModel 系统提示；alpine-rootfs 路径残留修复 |
| `fb0bade` | 特权后端 UI | `OffloadPermissionScreen` su_cli 卡片、新增 `SuPermissionScreen`、`Routes.HOST_SU`、DeepLink、23 组字符串 |
| `14286f6` | 品牌切换 Minis Ultra | applicationId/version、53 个 launcher 图标、签名链、23 语言文案、包名依赖点 |
| `06ce84c` | 修改清单文档 |本文件（docs/ULTRA-CHANGES.md） |
| `edcdefa` | 顶层版本变量修复 | `build.gradle.kts` 顶层 `val minisBaseVersionName = "1.15.001"`、`val ultraVersionCode = 37001`（曾误写在 defaultConfig 内导致 DSL 编译失败） |

## 三、功能域 → 文件映射（rebase 时重点盯这些）

### 人格（不截断 + 导入）
- `agent/SoulStore.kt`：`isOverLimit` 恒 Ok；`identitySection(context, providerInstanceId, sessionId)` 三参；
  `IDENTITY_TEMPLATE` = Minis Ultra + Ubuntu 24.04；`soulEditHint` 无上限文案
- `agent/PersonaPromptLibrary.kt` / `PersonaSessionOverride.kt`：全新文件；`sessionPersonaFile` 用 `minis-sessions/<id>/`（上游无 SessionWorkspace）
- `ui/settings/SoulSettingsScreen.kt`：人格库 UI（导入/下拉/删除/编辑入口），Save 只写身份字段
- `ui/settings/SoulPromptEditorScreen.kt`：全新文件
- `config/ConfigBuiltins.kt`：`registerSoul` 无上限 + `soul.body` 描述
- `ui/chat/ChatViewModel.kt`：`buildSystemPrompt` 里 `personaProviderInstanceId` / `personaSessionId` 解析

### 厂商图标
- 资源：`res/raw/ic_brand_*.svg` × 358、`res/raw-night/` × 137
- 代码：`ui/chat/ModelBrand*.kt`、`BrandAccent.kt`、`VendorMark.kt`、`tools/BrandKeywordSources.kt`
- 接线：`ChatModelPickerSheet.kt`、`ProviderDetailScreen.kt`、`UsageStatsScreen.kt`、`ChatScreen.kt`（topBarBrandSlug）、`SettingsComponents.kt`（SettingsRow.leading）
- 构建：`build.gradle.kts` 的 `io.coil-kt:coil-svg`；`MinisApp.newImageLoader` 的 `SvgDecoder.Factory()`（漏加 = 图标静默消失）

### 提权（su 首选 → Shizuku 兜底）
- 执行层：`sandbox/offload/SuOffloadHandler.kt`（含顶层 `HOST_SU_MAX_TIMEOUT_MS`）、`offload/HostSuFallback.kt`、`offload/HostSuManager.kt`
- 分派点：`offload/ShizukuManager.kt` 的 `runProcess()` 三分派（所有 Shizuku 消费者自动继承 su 兜底）
- 注册：`MinisApp.kt` 注册 `android-su` / `su`；`OffloadPermissionManager` 的 `su_cli` = BYPASS + `isRegistered()`
- 安全边界：块设备/根删除硬拒绝（`SuOffloadHandler.hostRefusal`，依赖 `security/ShellArgv.kt`）
- UI：`OffloadPermissionScreen` su 卡片 + `SuPermissionScreen` + `Routes.HOST_SU` + DeepLink `host-su|android-su|su|shizuku`

### Ubuntu 沙箱
- 构建：`scripts/prepare_android_sandbox.sh`（ubuntu-base arm64 三档 URL + gzip 门禁）
- 核心：`sandbox/RootfsManager.kt`（`ubuntu-rootfs` 目录、`configureUbuntuGuest`、CA 注入、dpkg/pip world、minis-mirror、legacy alpine 清理）、`PRootArgv.kt`、`RootfsTar.kt`、`RootfsStaging.kt`、`GuestAptScript.kt`、`CaBundle.kt`、`OpenSslSubjectHash.kt`、`SandboxResourceGate.kt`（仅 aptMutex）
- 环境：`PRootKernel.kt`（TMPDIR/SSL_CERT_* 注入、boot 串行化、commonArgv 统一 argv）
- overlay：`assets/default_mount/etc/apt/*`、`etc/dpkg/*`、`etc/pip.conf`、`usr/local/bin/{apt,apt-get,dnf,yum,minis-apt-wrap,minis-ca-dedup,minis-mirror}`、`usr/local/lib/minis/{apt-lock.sh,offline.sh}`
- 保留的上游特性：ContainerDns 自定义 DNS、GIT_CONFIG core.createObject=rename、GitConfigMigration、SessionMounts、MirrorSpeedTestViewModel

### 品牌（Minis Ultra）
- `build.gradle.kts`：applicationId `com.openminis.ultra`、versionCode 37001、`minisBaseVersionName = "1.15.001"`、签名链、RELEASE_REPO = tall-1997/OpenMinis-Linux
- `AndroidManifest.xml`：`roundIcon`、`com.openminis.ultra.action.OPEN_WEBAPP`
- 包名依赖点：`res/xml/shortcuts.xml`（targetPackage）、`AppIconRepository.PACKAGE_NAME`、`MinisAccessibilityService.SERVICE_ID`、`DebugServer`/`DebugMethodRegistry`（run-as）、`security/ShellArgv.kt`（APP_DATA_ROOTS）、`ResourceWatermark(+Test)`、androidTest bind 路径
- 文案：全部 `strings.xml`（23 locale）+ Kotlin 内硬编码文案统一 `Minis Ultra`（规则 `\bMinis\b(?! Ultra)`）
- 明确不改：namespace、私有 Intent action、JNI 符号、`minis://`、`minis-config` 等 CLI 名、`/var/minis`、SAF authority `com.openminis.minis.documents`

## 四、跟随上游更新的操作流程

```bash
cd D:\minis
git fetch origin                       # 上游 OpenMinis/OpenMinis
git rebase origin/main                 # 或 rebase 到指定 tag：git rebase <tag>
```

1. **预期冲突热点**（按历史经验排序）：
   `ui/chat/ChatViewModel.kt`（系统提示+buildSystemPrompt）、`sandbox/RootfsManager.kt`、
   `sandbox/PRootKernel.kt`、`config/ConfigBuiltins.kt`、`agent/SoulStore.kt`、
   `ui/settings/SoulSettingsScreen.kt`、`strings.xml`（上游加新键时）、`build.gradle.kts`（依赖行）
2. **冲突解决原则**：上游新特性优先保留；我们的功能块按本文件第三节的映射原样保留；
   系统提示区冲突时以上游新结构为骨架、把 Ubuntu/Minis Ultra 措辞重新套回去。
3. **版本号**：rebase 到上游新 tag 后，把 `build.gradle.kts` 的 `versionCode = 37001` 改为
   `上游vc*1000+001`、`minisBaseVersionName = "<上游版本>.001"`。
3. **版本号**：rebase 到上游新 tag 后，改 `build.gradle.kts` **顶层两个 val**：
   `ultraVersionCode = <上游vc*1000+001>`、`minisBaseVersionName = "<上游版本>.001"`。
   **每次发布要手动把后缀 +1**（当前静态 `001`，不按 PR 号自动递增），
   否则两次构建的版本名/版本号相同，Android只视为同版本重装而非升级。
4. **CI 版本读取**：`.github/workflows/android-apk.yml` 的 "Read app version" 步骤
   靠**顶层** `minisBaseVersionName = "..."` 声明行和 `versionCode = ultraVersionCode`
   正则解析——**不要**把它们挪回 `defaultConfig` 块内（上次就是那样编译挂掉的）。
   ultra 分支 push 不触发 `MINIS_VERSION_SUFFIX`（那步只对 main 生效）。
   `sandbox-assets` 的缓存键基于 `prepare_android_sandbox.sh` 的 hash，改脚本会自动重建 rootfs。
5. **验证**：推送后看 Actions 的 APK 构建；绿色后再真机冒烟（见第六节）。

## 五、已知未移植 / 有意不移植

- fork 的 `sandbox/kernel`（预算/Guardian）、`sandbox/jobs`、`config` 重构（PrefsHandle/Registry）、
  `di.AppGraph`、CI 门禁体系、dev-suite 脚本组、MCP、iOS、Termux PTY 重构、
  SessionWorkspace 布局、jieba 词典 gz 压缩
- 上游 1.15 之后的 36 个上游提交（上游 v1.14/v1.15 发版线）未回移；
  其中 Tailscale 出口节点修复（`ee718c66`）值得单独 cherry-pick

## 六、真机冒烟清单

- [ ] 启动器名称/图标为 Minis Ultra（浅色/深色/圆形）
- [ ] 设置 → 权限：出现「宿主 su」卡片（有 root 显示 FOUND，无 root 显示 NOT FOUND），点进详情可「执行 su -c id」
- [ ] 设置 → Soul：人格文件行 + 下拉 + 「+」导入 .md/.txt；编辑页可保存；按供应商绑定
- [ ] 人格正文超长不再被拒/截断；计数为纯数字
- [ ] 模型选择器/供应商详情/用量页/顶栏显示厂商 logo
- [ ] 首启装 Ubuntu rootfs：guest 内 `cat /etc/os-release` = Ubuntu 24.04；`apt-get update` 走 ports.ubuntu.com；`which bash` 为 GNU bash
- [ ] 模型回答中自述环境为 Ubuntu 24.04 + apt（不再出现 Alpine/apk）
- [ ] About 页版本显示 1.15.001；仓库链接指向 tall-1997/OpenMinis-Linux
