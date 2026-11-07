# DuoLauncher 开源渠道分发手册

本文件记录把 DuoLauncher 收录进 **IzzyOnDroid** 与 **F-Droid 主仓库** 的全部准备材料与操作步骤。
商店元数据的正式文件在仓库 `fastlane/metadata/android/` 下，本文件只是操作说明。

---

## 一、结论与渠道定位

| 渠道 | 定位 | 分发的是谁签名的包 | 是否需要开发者账号 |
|---|---|---|---|
| GitHub Releases | 现状，主分发渠道 | 项目自己 | 已有 |
| **IzzyOnDroid** | F-Droid 兼容仓库，收录门槛较低，自动跟随 GitHub Releases 更新 | **项目自己的 APK**（原样搬运） | 提交收录 Issue 需要 Codeberg 账号 |
| **F-Droid 主仓库** | 官方 FOSS 仓库，审核最严，从源码用 F-Droid 服务器重新构建 | **F-Droid 官方密钥重签** | 提交 MR 需要 GitLab 账号 |

两家都读取同一套 `fastlane/metadata/android/` 元数据，所以材料只需维护一份。

---

## 二、硬性门槛对照（2026-11 核实）

| 要求 | IzzyOnDroid | F-Droid 主仓库 | 本仓库现状 |
|---|---|---|---|
| FOSS 许可 | 必须 | 必须 | ✅ MIT（`LICENSE`） |
| 公开源码、无闭源 SDK / 无追踪 | 必须 | 必须 | ✅ 仅 AndroidX / Compose / Kotlin（Apache-2.0） |
| Release 附 APK，tag 与版本对应 | 必须 | 建议 | ✅ `v1.3.9` ~ `v1.5.1`，`vX.Y.Z` 对应 `versionName` |
| **APK < 30 MB** | **硬性上限** | 无硬性上限但越小越好 | ✅ v1.5.1 起为 **2.14 MiB**（R8 + 资源缩减） |
| **非 debuggable** | 是（否则标 `ApplicationDebuggable`） | 是 | ✅ v1.5.1 起 CI 改用 `assembleRelease` |
| 签名前后一致 | 必须（首次收录记录指纹，之后逐次比对） | 由 F-Droid 重签，不要求 | ✅ 所有版本同一把 key（见下） |
| fastlane 元数据 | 强烈建议 | 建议 | ✅ 已补齐 en-US / zh-CN / ja-JP |
| `changelogs/<versionCode>.txt` | 建议 | 建议 | ✅ 137 / 138 均已补齐三语 |
| 隐私政策可公开访问 | 建议 | 建议 | ✅ `PRIVACY.md` |
| 不含 Google 签名的 `DEPENDENCY_INFO_BLOCK` | 会被扫描标记 | 会被扫描标记 | ✅ `dependenciesInfo` 已关闭 `includeInApk` / `includeInBundle` |
| 主动申报 AntiFeatures | 必须如实申报 | 必须如实申报 | ✅ `NonFreeNet` 已写入本手册与 yml |

### 当前 APK 签名（所有已发版本一致）

```
SHA-256: 3de9760f549173a4b2822bc9a9cfff357da12717307191b888ee95ebee7f820e
SHA-1:   AA:C0:B9:0D:4A:CA:50:97:BB:D1:2F:2A:D2:6B:BE:61:10:9D:B2:16
DN:      C=US, O=Android, CN=Android Debug
```

这是本机 `~/.android/debug.keystore` 的证书。IzzyOnDroid 只要求**前后一致**，因此收录没有问题；但它是一把调试证书，不适合作为长期正式发布密钥。**如果以后换成正式 release keystore，所有已安装用户都必须卸载重装**，且要提前通知 IzzyOnDroid 更新指纹，否则新包会被签名校验拦下。本轮分发保持原签名不变，用户可直接覆盖安装。

---

## 三、v1.5.1 瘦身版（已完成，作为收录基准版）

v1.5.0 的 52.78 MB 几乎全是 dex（`classes.dex` 42 MB + `classes8.dex` 8.6 MB），原因是 CI 构建的是 **debug 变体**（打包了 `compose-ui-tooling` 调试库），且 `isMinifyEnabled = false`。已完成的改造：

1. `app/build.gradle.kts`：`release` 开启 `isMinifyEnabled = true` / `isShrinkResources = true`；关闭 `dependenciesInfo`（避免 Google 签名的依赖信息块）；`versionName = 1.5.1`、`versionCode = 138`。
2. `.github/workflows/release.yml`：编译任务由 `assembleDebug` 改为 `assembleRelease`，产物路径改为 `app/build/outputs/apk/release/app-release.apk`，附件名保持 `DuoLauncher-<版本>.apk`。
3. `fastlane/metadata/android/*/changelogs/138.txt`：新增三个语言的更新说明。
4. 打 tag `v1.5.1` 并推送 → Actions 自动发布 Release。
5. 验收：APK < 30 MB、`aapt dump badging` 无 `debuggable`、签名指纹与上面一致、真机可覆盖安装且功能正常。

**验收结果（2026-11-06 本地实测）**：release 包 **2.14 MiB**（2,247,648 B）、`aapt dump badging` 无 `debuggable`、签名指纹与上表一致、真机覆盖安装后搜索手势与通知角标均正常，R8 未误裁服务类。CI 工作流里也内置了 30 MB 体积校验，超标直接失败。

> 这一版是 IzzyOnDroid 的收录基准版：它的自动更新脚本只认"每个 tag 附带一个 versionCode 更大的 APK"。

---

## 四、IzzyOnDroid 收录申请

**提交地址**：https://codeberg.org/IzzyOnDroid/repodata/issues/new
选择 **Request for Packaging** 模板。注意：原 GitLab 仓库的 Issue 跟踪已迁到 Codeberg，不要再往 GitLab 提。

以下正文可直接复制（提交前把 `<...>` 占位替换掉）：

```text
### App to be added

* Name: DuoLauncher
* applicationId: com.duolauncher
* Author: d3ja7u
* Source code: https://github.com/d3ja7u/DuoLauncher
* Releases with APK: https://github.com/d3ja7u/DuoLauncher/releases
* Latest release: v1.5.1 (versionCode 138), APK size <APKSIZE> MB
* License: MIT (https://github.com/d3ja7u/DuoLauncher/blob/main/LICENSE)
* Category: System / Theming
* Website / README: https://github.com/d3ja7u/DuoLauncher#readme (中文) and README.en.md (English)
* Issue tracker: https://github.com/d3ja7u/DuoLauncher/issues
* Changelog: https://github.com/d3ja7u/DuoLauncher/releases plus fastlane/metadata/android/*/changelogs/
* Privacy policy: https://github.com/d3ja7u/DuoLauncher/blob/main/PRIVACY.md

### Fastlane metadata

https://github.com/d3ja7u/DuoLauncher/tree/main/fastlane/metadata/android
Locales: en-US, zh-CN, ja-JP.
Available: shortdesc, fulldescMD, title, icon (512px), phoneScreenshots (8 real-device screenshots), changelogs.

### Update mechanism

GitHub Releases. Each tag vX.Y.Z has exactly one APK attached (DuoLauncher-X.Y.Z.apk), built by GitHub Actions from the tagged source. All releases use the same signing key; SHA-256 of the signing certificate:
3de9760f549173a4b2822bc9a9cfff357da12717307191b888ee95ebee7f820e
Release builds are not debuggable, R8-minified, and do not contain a DEPENDENCY_INFO_BLOCK.

### Anti-Features to declare

* NonFreeNet: the optional "Check for updates" feature queries the GitHub Releases API (or releases.atom as fallback) for version information, and downloads an APK only when the user explicitly taps "Download and install". GitHub is a proprietary service. The check is disabled by default and can be turned off in Settings; nothing is fetched or installed in the background. The launcher works fully offline.

### Sensitive permissions (all justified, see PRIVACY.md)

* QUERY_ALL_PACKAGES - core launcher function: enumerate all installed apps for the app library, A-Z index and search. The list never leaves the device.
* BIND_NOTIFICATION_LISTENER_SERVICE - count unread notifications for optional badges; processed on device only.
* BIND_ACCESSIBILITY_SERVICE - only for "double-tap to sleep" via the system lock-screen action. canRetrieveWindowContent=false; it cannot read screen content or keystrokes and performs no autonomous actions.
* EXPAND_STATUS_BAR - down-swipe gestures for the notification shade / quick settings.
* CAMERA / FLASHLIGHT - optional flashlight toggle (CameraManager.setTorchMode); never takes photos.
* BIND_DEVICE_ADMIN - optional fallback screen-off on a few ROMs; never auto-activated, can be disabled in system settings.
* REQUEST_INSTALL_PACKAGES - optional in-place updates: opens the system installer after a user-initiated download; the install still needs user confirmation and can be revoked in system settings.
* VIBRATE, ACCESS_WIFI_STATE, ACCESS_NETWORK_STATE, REQUEST_DELETE_PACKAGES, BIND_APPWIDGET - haptics, status-bar indicators, uninstall shortcut, widgets.

No ads, no analytics, no crash reporting, no tracking SDK, no Google Play Services, no Firebase, no proprietary libraries. Dependencies are AndroidX / Jetpack Compose / Kotlin (Apache-2.0) only.

### Notes

Hand-tuned for the Unihertz Titan 2 Elite (near-square 1080x1200 screen with a physical keyboard), but works on Android 9+ devices. UI is available in Simplified Chinese, English and Japanese.
```

审核者可能会要求**真机测试**（标签 `needs/on-device-testing`），届时按对方要求回复即可；他们会把 APK 传到 VirusTotal / Pithus 扫描，并用网络监控工具确认没有意外外联——本应用除可选的更新检查外没有联网行为。

---

## 五、F-Droid 主仓库收录

**提交地址**：https://gitlab.com/fdroid/fdroiddata/-/merge_requests （或先在 https://gitlab.com/fdroid/rfp/issues 提收录提案）
官方推荐直接提 `fdroiddata` 的 MR。流程：

```bash
# 1. Fork https://gitlab.com/fdroid/fdroiddata 并克隆
git clone https://gitlab.com/<your-account>/fdroiddata.git
cd fdroiddata
git checkout -b com.duolauncher master

# 2. 复制模板（本仓库 docs/fdroid/com.duolauncher.yml）
cp /path/to/DuoLauncher/docs/fdroid/com.duolauncher.yml metadata/com.duolauncher.yml

# 3. ⚠️ 把 commit 改成 v1.5.1 tag 的完整 40 位 hash
git -C /path/to/DuoLauncher rev-list -n 1 v1.5.1

# 4. 本地校验（需 fdroidserver）
fdroid lint com.duolauncher
fdroid rewritemeta com.duolauncher
fdroid readmeta
fdroid build com.duolauncher     # 可选，验证能否从源码构建

# 5. 提交并提 MR
git add metadata/com.duolauncher.yml
git commit -m "New App: com.duolauncher"
git push origin com.duolauncher
```

模板要点：

- `Categories: [System]`、`License: MIT`、`AutoUpdateMode: Version v%v`、`UpdateCheckMode: Tags`，由 `checkupdates` 自动跟随新 tag 提 MR。
- `Builds[0].gradle: [yes]` → 执行 `assembleRelease`，`subdir: app`。
- `AntiFeatures.NonFreeNet` 已声明，理由写在英文说明里。
- F-Droid 用自己的密钥重新签名构建产物，因此**不需要**填 `AllowedAPKSigningKeys`。
- 构建机若无法访问 `settings.gradle.kts` 里的阿里云镜像，可在 build 块加 `prebuild` 把仓库换成 `google()` / `mavenCentral()`，或按维护者建议改用官方源。

MR 合入后通常还要 **24–48 小时**（签名环节需要人工介入）才会出现在主仓库，审核中被问到 AntiFeature / 权限用途时，直接引用 `PRIVACY.md` 和 `full_description.txt` 回答。

---

## 六、每次发版的维护清单

1. `app/build.gradle.kts`：`versionCode` 递增、`versionName` 更新。
2. `fastlane/metadata/android/{en-US,zh-CN,ja-JP}/changelogs/<versionCode>.txt`：新增说明（en-US 必需，单条 ≤ 500 字符）。
3. 提交并推送 `main`，再打 tag `git tag vX.Y.Z && git push origin vX.Y.Z`。
4. Actions 自动编译 → 发布 Release → 确认只附 1 个 APK、体积 < 30 MB、签名指纹未变。
5. IzzyOnDroid 自动抓取新版本；F-Droid 的 `checkupdates` 会自动开 MR（或人工跟一下）。
6. 若改动了权限、网络行为或第三方依赖，同步更新 `PRIVACY.md` 与三个 `full_description.txt`。

---

## 七、必须由本人完成的动作

| 动作 | 原因 |
|---|---|
| 注册 / 登录 Codeberg 账号并提交 IzzyOnDroid 收录 Issue | 需要你的账号与身份 |
| 注册 / 登录 GitLab 账号、Fork `fdroiddata` 并提 MR | 需要你的账号 |
| 回复两家审核者的问询、按需配合真机测试 | 需要开发者本人答复 |
| 推送 `v1.5.1` tag 触发正式发版 | 对外发布动作，需你确认 |

材料、元数据、构建改造、Issue / MR 正文都可以由本仓库直接提供，上面四项需要你本人点最后一下。

---

## 八、已知备注

- **NonFreeNet**：更新检查默认关闭；开启后进入设置页会静默检查（12 小时内最多一次），只请求版本信息，不下载 APK。若维护者仍认为不合适，可为 F-Droid 单独出一个关闭该功能的 flavor。
- **debug 签名**：见第二节。长期建议生成正式 release keystore，但换 key 必须提前通知 IzzyOnDroid 并告知用户卸载重装。
- **设备适配**：本应用为近方屏设备手作，F-Droid / Izzy 的审核设备多为常规比例手机，若对方反馈显示异常，附上 Titan 2 Elite 的实机截图说明即可。
- **Google Play / 国内商店**：本轮不处理。相关门槛（targetSdk 36、正式签名、无障碍合规披露、软著与 APP 备案等）另议。
