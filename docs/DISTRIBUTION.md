# DuoLauncher 开源渠道分发手册

本文件记录把 DuoLauncher 收录进 **IzzyOnDroid** 与 **F-Droid 主仓库** 的全部准备材料与操作步骤。
商店元数据的正式文件在仓库 `fastlane/metadata/android/` 下，本文件只是操作说明。

> 当前状态（2026-11-09）：**v1.5.11 / versionCode 178 已在 GitHub Releases 正式发布，但两家仓库都还没有提交收录申请**，所以在 F-Droid 客户端里搜不到本应用。F-Droid 不会自动同步 GitHub Release。

---

## 一、结论与渠道定位

| 渠道 | 定位 | 分发的是谁签名的包 | 是否需要开发者账号 |
|---|---|---|---|
| GitHub Releases | 现状，主分发渠道 | 项目自己 | 已有 |
| **IzzyOnDroid** | F-Droid 兼容仓库，收录门槛较低，自动跟随 GitHub Releases 更新 | **项目自己的 APK**（原样搬运） | 提交收录 Issue 需要 Codeberg 账号 |
| **F-Droid 主仓库** | 官方 FOSS 仓库，审核最严，从源码用 F-Droid 服务器重新构建 | **F-Droid 官方密钥重签** | 提交 MR 需要 GitLab 账号 |

两家都读取同一套 `fastlane/metadata/android/` 元数据，所以材料只需维护一份。
F-Droid 官方重签的包与 GitHub / Izzy 版**不能互相覆盖安装**，用户换渠道时需先卸载。

---

## 二、硬性门槛对照（2026-11-09 核实）

| 要求 | IzzyOnDroid | F-Droid 主仓库 | 本仓库现状 |
|---|---|---|---|
| FOSS 许可 | 必须 | 必须 | ✅ MIT（`LICENSE`） |
| 公开源码、无闭源 SDK / 无追踪 SDK | 必须 | 必须 | ✅ 仅 AndroidX / Compose / Kotlin（Apache-2.0） |
| Release 附 APK，tag 与版本对应 | 必须 | 建议 | ✅ `v1.3.9` ~ `v1.5.11`，`vX.Y.Z` 对应 `versionName` |
| **APK < 30 MB** | **硬性上限** | 无硬性上限但越小越好 | ✅ v1.5.11 为 **2.38 MiB**（2,491,968 B） |
| **非 debuggable** | 是（否则标 `ApplicationDebuggable`） | 是 | ✅ `assembleRelease` 构建，R8 + 资源缩减 |
| 签名前后一致 | 必须（首次收录记录指纹，之后逐次比对） | 由 F-Droid 重签，不要求 | ✅ 所有版本同一把 key（见下） |
| fastlane 元数据 | 强烈建议 | 建议 | ✅ en-US / zh-CN / ja-JP，含标题、简介、详述、图标、8 张实机截图 |
| `changelogs/<versionCode>.txt` | 建议 | 建议 | ⚠️ 137–143 三语齐备；**144 起按项目约定不再逐版提供**，新版说明见 GitHub Release 正文（提交时如实说明即可） |
| 隐私政策可公开访问 | 建议 | 建议 | ✅ `PRIVACY.md` |
| 不含 Google 签名的 `DEPENDENCY_INFO_BLOCK` | 会被扫描标记 | 会被扫描标记 | ✅ `dependenciesInfo` 已关闭 `includeInApk` / `includeInBundle` |
| 主动申报 AntiFeatures | 必须如实申报 | 必须如实申报 | ✅ `NonFreeNet` / `Tracking` 已写入本手册与 yml |

### 当前 APK 签名（所有已发版本一致）

```
SHA-256: 3de9760f549173a4b2822bc9a9cfff357da12717307191b888ee95ebee7f820e
SHA-1:   AA:C0:B9:0D:4A:CA:50:97:BB:D1:2F:2A:D2:6B:BE:61:10:9D:B2:16
DN:      C=US, O=Android, CN=Android Debug
```

这是本机 `~/.android/debug.keystore` 的证书。IzzyOnDroid 只要求**前后一致**，因此收录没有问题；但它是一把调试证书，不适合作为长期正式发布密钥。**如果以后换成正式 release keystore，所有已安装用户都必须卸载重装**，且要提前通知 IzzyOnDroid 更新指纹，否则新包会被签名校验拦下。本轮分发保持原签名不变，用户可直接覆盖安装。

---

## 三、构建与当前收录基准版

### 历史：v1.5.1 瘦身改造（2026-11-06 完成）

v1.5.0 的 52.78 MB 几乎全是 dex，原因是 CI 构建的是 **debug 变体**（打包了 `compose-ui-tooling` 调试库），且 `isMinifyEnabled = false`。当时完成的改造：

1. `app/build.gradle.kts`：`release` 开启 `isMinifyEnabled = true` / `isShrinkResources = true`；关闭 `dependenciesInfo`；版本改为 `1.5.1` / `138`。
2. `.github/workflows/release.yml`：编译任务由 `assembleDebug` 改为 `assembleRelease`，产物路径改为 `app/build/outputs/apk/release/app-release.apk`。
3. `fastlane/metadata/android/*/changelogs/138.txt`：新增三个语言的更新说明。
4. 验收：APK 2.14 MiB、无 `debuggable`、签名指纹一致、真机可覆盖安装；CI 内置 30 MB 体积校验。

### 当前基准版：v1.5.11 / versionCode 178

| 项目 | 值 |
|---|---|
| tag / 源码提交 | `v1.5.11` → `805e7818dff5e8cf7e5dce038a8e4b276923d3a0` |
| Release 资产 | `DuoLauncher-1.5.11.apk`，2,491,968 B |
| 构建 | 本地 JDK 17 `assembleRelease`（本次 CI 因构建机下载 Gradle 发行包超时失败，详见第八节） |
| 校验 | `aapt dump badging` 无 `debuggable`；`apksigner` 指纹与上表一致；Release 资产 sha256 与本地包一致 |

> 收录申请一律以**当前基准版**为准。IzzyOnDroid 的自动更新脚本只认"每个 tag 附带一个 versionCode 更大的 APK"，v1.5.11 满足该条件。

---

## 四、IzzyOnDroid 收录申请

**提交地址**：https://codeberg.org/IzzyOnDroid/repodata/issues/new/choose
选择模板 **App Inclusion Request**（模板文件 `.forgejo/issue_template/app-inclusion-request.yaml`），标题填 `[AppRequest] DuoLauncher`。
注意：Issue 跟踪在 Codeberg 的 `repodata` 仓库，不是老的 GitLab `IzzyOnDroid/repo`；**旧模板名 Request for Packaging 已废弃**。

现行模板是**结构化表单**，不是一整段 Markdown 正文；下面按字段逐项填写即可。已核实 `repodata` 的 Issue 列表与 `fdroiddata` / RFP 中都没有本应用的重复申请。

### Guidelines（勾选项）

```
- [x] I am the developer of the app.
- [x] The app complies with the App Inclusion Policy.
- [x] The app is not already listed in the repo or issue tracker.
- [x] The Fastlane folder is available in the app's repo.
```

### Link to the source code（必填）

```text
https://github.com/d3ja7u/DuoLauncher
```

### Link to app in another app store（选填）

```text
GitHub Releases (signed APKs): https://github.com/d3ja7u/DuoLauncher/releases/latest
```

### License used（必填）

```text
MIT
```

### Categories（必填，多选）

```text
System, Theming
```

### Summary（必填）

```text
A customizable home screen launcher with an iOS-inspired glass UI, hand-tuned for near-square Android devices with a physical keyboard. UI in Simplified Chinese, English and Japanese.
```

### Description（必填）

```text
DuoLauncher is a complete home screen launcher (android.intent.category.HOME) written in Kotlin with Jetpack Compose, MIT licensed. It was hand-tuned for the Unihertz Titan 2 Elite (1080x1200 near-square display, physical keyboard) and runs on Android 9+ devices.

FEATURES
* Glass-morphism UI with adjustable blur strength and light / dark appearance
* iOS-style custom status bar: time, cellular arc and Wi-Fi dots, switchable sides, 12/24-hour clock
* Editable side dock (right or left) with its own icon size, plus a Now Playing capsule
* App library: category cards OR a strict A-Z list with a draggable alphabet index
* Search apps, contacts and the web; Enter opens the first result, web results open in the default browser
* Notification badges in 8 colors, gesture controls, third-party icon packs, hidden apps, home screen widgets
* Fresh installs start with an empty home screen; batch add / remove home icons, optional auto-add for newly installed apps
* Full configuration export / import as JSON through the system file picker
* UI in Simplified Chinese, English and Japanese; follows the device language with a manual override
* In-place updates and opt-out anonymous usage statistics, both optional and both explained below

No ads, no third-party analytics / crash reporting SDK, no Google Play Services, no Firebase, no proprietary libraries. All layout and settings live in one local JSON file; there is no account and no sign-in. Full privacy policy: https://github.com/d3ja7u/DuoLauncher/blob/main/PRIVACY.md
```

### Build instructions（必填）

```text
Requirements: JDK 17, Android SDK platform android-34 and build-tools 34.0.0. Gradle 8.9 is downloaded by the bundled wrapper.

  git clone https://github.com/d3ja7u/DuoLauncher.git
  cd DuoLauncher
  ./gradlew :app:assembleRelease
  # output: app/build/outputs/apk/release/app-release.apk (~2.4 MB)

The release build is R8-minified, not debuggable, and contains no DEPENDENCY_INFO_BLOCK. If DUO_KEYSTORE is unset, Gradle falls back to the local default debug signing config; CI sets DUO_KEYSTORE (plus pass/alias) to point at a restored keystore. All published APKs use the same certificate (SHA-256: 3de9760f549173a4b2822bc9a9cfff357da12717307191b888ee95ebee7f820e). settings.gradle.kts currently prefers Aliyun Maven mirrors; if those are unreachable from a build host, replace them with google() / mavenCentral().

Every vX.Y.Z tag has exactly one APK attached, named DuoLauncher-X.Y.Z.apk. Latest: v1.5.11, versionCode 178, 2,491,968 bytes.
```

### AI Tools Usage（模板新增的必填组，⚠️ 需本人确认）

> 本项目的开发过程大量使用了 AI 辅助，**请勿选 `None`**。等级与工具名请按实际情况选择／填写；下面给出如实申报的参考措辞，两项 Accountability 勾选也需本人确认。

| 字段 | 建议填写 |
|---|---|
| Assistance Level（必选） | `Substantial – Used throughout development`（或按实际选 `Dominant`） |
| "AI" Tool(s) | `WorkBuddy (AI coding assistant)` —— 如有其他工具／模型请自行补全 |
| What did the tools help with, and how? | 见下方英文文本 |
| AI Accountability | 两项都勾（人工审阅全部产出、人工测试并验证每次改动） |

```text
AI assistance was used throughout implementation: Jetpack Compose UI, launcher and paging logic, bug diagnosis and fixes, three-language string resources, release notes and documentation. All generated output was reviewed and edited by the developer, and every release was installed and manually verified on a physical Unihertz Titan 2 Elite before publishing. The developer remains responsible for the code.
```

### Further Notices（选填，但**建议完整粘贴**：AntiFeature 与权限必须主动申报）

```text
ANTI-FEATURES TO DECLARE

* NonFreeNet: the optional "Check for updates" feature queries the GitHub Releases API (or releases.atom as fallback) for version information, and downloads an APK only when the user explicitly taps "Download and install". GitHub is a proprietary service. The check is off by default, can be disabled in Settings, and nothing is fetched or installed in the background. The launcher works fully offline.
* Tracking: optional, opt-out anonymous usage statistics. A random local ID, app version, UI language, Android API level and report type are sent to a statistics endpoint to count devices. A first-launch notice asks before anything is sent; the user can turn it off at any time in Settings (which deletes the local ID), clear the endpoint, or point it at a self-hosted server. No device identifiers, no IP storage, no contacts, location or usage content. Disabling it affects no launcher feature. See PRIVACY.md.

SENSITIVE PERMISSIONS (all justified, see PRIVACY.md)

* QUERY_ALL_PACKAGES - enumerate installed apps for the app library, A-Z index and search; the list never leaves the device.
* READ_CONTACTS - optional contact search; names and numbers are matched on device only.
* BIND_NOTIFICATION_LISTENER_SERVICE - count unread notifications for optional badges; processed on device only.
* BIND_ACCESSIBILITY_SERVICE - only for "double-tap to sleep" via the system lock-screen action. canRetrieveWindowContent=false; it cannot read screen content or keystrokes and performs no autonomous actions.
* EXPAND_STATUS_BAR - down-swipe gestures for the notification shade / quick settings.
* CAMERA / FLASHLIGHT - optional flashlight toggle (CameraManager.setTorchMode); never takes photos.
* BIND_DEVICE_ADMIN - optional fallback screen-off on a few ROMs; never auto-activated, can be disabled in system settings.
* REQUEST_INSTALL_PACKAGES - optional in-place updates: opens the system installer after a user-initiated download; the install still needs user confirmation and can be revoked in system settings.
* INTERNET - the optional update check / in-place download and the opt-out anonymous statistics; no business requests when both are disabled.
* VIBRATE, ACCESS_WIFI_STATE, ACCESS_NETWORK_STATE, REQUEST_DELETE_PACKAGES, BIND_APPWIDGET - haptics, status-bar indicators, uninstall shortcut, widgets.

FASTLANE & RELEASES

https://github.com/d3ja7u/DuoLauncher/tree/main/fastlane/metadata/android
Locales: en-US, zh-CN, ja-JP. Available: title, short_description, full_description, icon (512px), 8 real-device phone screenshots, changelogs 137-143. From versionCode 144 on the project documents releases in the GitHub Release notes instead of per-version Fastlane changelogs.

All releases use one signing key; SHA-256 of the signing certificate:
3de9760f549173a4b2822bc9a9cfff357da12717307191b888ee95ebee7f820e
Release builds are not debuggable, are R8-minified, and contain no DEPENDENCY_INFO_BLOCK.

Hand-tuned for a near-square 1080x1200 device with a physical keyboard, but works on Android 9+ devices. UI is available in Simplified Chinese, English and Japanese.
```

审核者可能会要求**真机测试**（标签 `needs/on-device-testing`），届时按对方要求回复即可；他们会把 APK 传到 VirusTotal / Pithus 扫描，并用网络监控工具确认没有意外外联——本应用除**可选的更新检查**与**可关闭的匿名统计**外没有联网行为，两者都可在设置里关闭，关闭后完全离线可用。

---

## 五、F-Droid 主仓库收录

**提交地址**：https://gitlab.com/fdroid/fdroiddata/-/merge_requests （或先在 https://gitlab.com/fdroid/rfp/issues 提收录提案）
官方推荐直接提 `fdroiddata` 的 MR，标题格式 `New app: DuoLauncher`。流程：

```bash
# 1. Fork https://gitlab.com/fdroid/fdroiddata 并克隆
git clone https://gitlab.com/<your-account>/fdroiddata.git
cd fdroiddata
git checkout -b com.duolauncher master

# 2. 复制模板（本仓库 docs/fdroid/com.duolauncher.yml）
cp /path/to/DuoLauncher/docs/fdroid/com.duolauncher.yml metadata/com.duolauncher.yml

# 3. 核对 commit：模板里已填好 v1.5.11 tag 的完整 40 位 hash，无需替换
git -C /path/to/DuoLauncher rev-list -n 1 v1.5.11
# → 805e7818dff5e8cf7e5dce038a8e4b276923d3a0

# 4. 本地校验（需 fdroidserver）
fdroid lint com.duolauncher
fdroid rewritemeta com.duolauncher
fdroid readmeta
fdroid build com.duolauncher     # 可选，验证能否从源码构建

# 5. 提交并提 MR
git add metadata/com.duolauncher.yml
git commit -m "New app: DuoLauncher"
git push origin com.duolauncher
```

模板要点：

- `Categories: [System]`、`License: MIT`、`AutoUpdateMode: Version v%v`、`UpdateCheckMode: Tags`，由 `checkupdates` 自动跟随新 tag 提 MR。
- `Builds[0].gradle: [yes]` → 执行 `assembleRelease`，`subdir: app`；只保留最新版本一个 Build，`commit` 是完整 hash 而非 tag 名。
- `AntiFeatures.NonFreeNet` 与 `Tracking` 已声明，理由写在英文说明里。
- F-Droid 用自己的密钥重新签名构建产物，因此**不需要**填 `AllowedAPKSigningKeys`。
- `settings.gradle.kts` 默认使用阿里云 Maven 镜像，且仓库里**没有** `-Pduo.cnMirror` 开关。构建机若访问不了，按维护者建议在 build 块加 `prebuild` 把仓库替换成 `google()` / `mavenCentral()`。

### MR 描述（可直接复制粘贴）

```markdown
## Checklist

### Policy
* [x] The app complies with the inclusion criteria: MIT-licensed, fully open source, no proprietary SDKs.
* [x] I am the original app author (d3ja7u) and I am submitting this MR myself.
* [x] The upstream repo contains Fastlane metadata at `fastlane/metadata/android/` with `en-US`, `zh-CN` and `ja-JP` (title, short/full description, icon, 8 real-device screenshots, changelogs 137-143). From versionCode 144 on, release notes are published in the GitHub Releases instead of per-version Fastlane changelogs.

### Docs / MR setup
* [x] MR title follows "New app: DuoLauncher".
* [x] The fork is public and the branch is not protected; one app per MR.
* [x] No related RFP or fdroiddata issues exist for this application (checked on 2026-11-09).
* [x] Only `metadata/com.duolauncher.yml` is added, valid YAML with LF endings. No summary, description, images or changelog files are duplicated here.
* [x] Releases are tagged `vX.Y.Z`; `AutoUpdateMode: Version v%v` and `UpdateCheckMode: Tags` are enabled.
* [x] Issue tracker, WebSite and AuthorName are present.
* [x] `commit` is the full 40-character hash of tag `v1.5.11` (`805e7818dff5e8cf7e5dce038a8e4b276923d3a0`).
* [x] Only the latest version is kept in the metadata; no disabled builds.

### Metadata
* [x] `Categories: [System]`, `License: MIT`, `RepoType: git`.
* [x] `Builds[0]`: `gradle: [yes]`, `subdir: app`, versionName `1.5.11`, versionCode `178`. The release build is R8-minified, not debuggable and contains no DEPENDENCY_INFO_BLOCK.
* [x] AntiFeatures `NonFreeNet` and `Tracking` are declared proactively with their reasons in the yml.

## Anti-Features

* **NonFreeNet**: the optional update checker queries the GitHub Releases API (or releases.atom) for version information and downloads an APK only after the user explicitly taps "Download and install". GitHub is a proprietary service. The check is off by default, can be disabled in Settings, and nothing is fetched or installed in the background.
* **Tracking**: optional, opt-out anonymous usage statistics send a random local ID, app version, UI language, Android API level and report type to a statistics endpoint to count devices. A first-launch notice asks before anything is sent; the user can turn it off at any time in Settings (which deletes the local ID), clear the endpoint, or point it at a self-hosted server. No device identifiers, IP storage, contacts, location or usage content. Disabling it affects no launcher feature. See PRIVACY.md.

## Build and signing

`./gradlew :app:assembleRelease` with JDK 17, Android SDK platform 34 and Gradle 8.9 (bundled wrapper). `settings.gradle.kts` prefers Aliyun Maven mirrors; if those are unreachable from the build host, a `prebuild` step replacing them with `google()` / `mavenCentral()` is acceptable.

Reproducible Builds are not enabled: the upstream release build is signed with a local keystore that is not part of the repository, so an external builder cannot reproduce that signature. F-Droid may use its standard signing process. Users switching between the GitHub/IzzyOnDroid builds (SHA-256 `3de9760f549173a4b2822bc9a9cfff357da12717307191b888ee95ebee7f820e`) and the F-Droid build need to uninstall first.

## Additional information

DuoLauncher is a complete HOME launcher written in Kotlin + Jetpack Compose, hand-tuned for the Unihertz Titan 2 Elite (near-square 1080x1200 display, physical keyboard) and usable on Android 9+ devices. No ads, no third-party analytics / crash reporting SDK, no Google Play Services, no Firebase; dependencies are AndroidX / Compose / Kotlin (Apache-2.0) only. All permissions and their justifications are documented in `PRIVACY.md` and `fastlane/metadata/android/en-US/full_description.txt`.

Source: https://github.com/d3ja7u/DuoLauncher
Releases: https://github.com/d3ja7u/DuoLauncher/releases
Privacy policy: https://github.com/d3ja7u/DuoLauncher/blob/main/PRIVACY.md
```

MR 合入后通常还要 **24–48 小时**（签名环节需要人工介入）才会出现在主仓库。审核中若被问到 AntiFeature / 权限用途，直接引用 `PRIVACY.md` 与 `full_description.txt` 回答；流水线若报阿里云镜像不可达，按上面的 `prebuild` 建议处理。

---

## 六、每次发版的维护清单

1. `app/build.gradle.kts`：`versionCode` 递增、`versionName` 更新。
2. Release 正文写「更新说明」（官方、克制、只写用户可感知的新增与修复），`en-US` 的更新说明也可同步到 `fastlane/metadata/android/*/changelogs/<versionCode>.txt`；**144 起按项目约定不再逐版补三语 changelog**，不必为收录单独补历史文件。
3. 提交并推送 `main`，再打 tag `git tag vX.Y.Z && git push origin vX.Y.Z`。
4. 确认每个 tag **只附 1 个 APK**、体积 < 30 MB、签名指纹未变；然后回填 `docs/fdroid/com.duolauncher.yml` 的 `versionName` / `versionCode` / `CurrentVersion*` / `commit`。
5. IzzyOnDroid 自动抓取新版本；F-Droid 的 `checkupdates` 会自动开 MR（或人工跟一下）。
6. 若改动了权限、网络行为或第三方依赖，同步更新 `PRIVACY.md` 与三个 `full_description.txt`。
7. 若 CI 因外部网络问题失败而改用本地 release 包发布（见第八节），仍需确认线上资产与本地包 sha256 一致、`/releases/latest` 指向新版本，否则收录方的自动抓取会停在旧版。

---

## 七、必须由本人完成的动作

| 动作 | 状态 / 原因 |
|---|---|
| 发布 v1.5.11 并附带 APK | ✅ 已完成（2026-11-08） |
| 注册 / 登录 Codeberg 账号，按 **App Inclusion Request** 模板提交收录 Issue | ⬜ 待办，需要你的账号与身份 |
| 确认并记录 **AI 工具使用等级与工具名**，如实填写模板必填组 | ⬜ 待办，属于开发者本人的声明，不可代选 |
| 注册 / 登录 GitLab 账号、Fork `fdroiddata` 并提 MR | ⬜ 待办，需要你的账号 |
| 回复两家审核者的问询、按需配合真机测试 | ⬜ 待办，需要开发者本人答复 |

材料、元数据、构建改造、Issue / MR 正文都可以由本仓库直接提供，上面五项需要你本人点最后一下。

---

## 八、已知备注

- **CI 与发版兜底**：v1.5.11 的 GitHub Actions 连续三次失败，原因都是构建机下载 `gradle-8.9-bin.zip` 触发 wrapper 内置 10 秒超时（`SocketTimeoutException`），与代码无关。最终用本地 JDK 17 编译的 release 包直接创建 Release 并上传资产，sha256 与本地一致、公开下载正常。**不要再 rerun 那次的 workflow**（`append_body: true` 会重复追加安装段）。以后遇到同样故障可沿用该兜底方式，或给 workflow 的 Gradle 发行包加缓存／重试。
- **NonFreeNet**：更新检查默认关闭；开启后进入设置页会静默检查（12 小时内最多一次），只请求版本信息，不下载 APK。若维护者仍认为不合适，可为 F-Droid 单独出一个关闭该功能的 flavor。
- **debug 签名**：见第二节。长期建议生成正式 release keystore，但换 key 必须提前通知 IzzyOnDroid 并告知用户卸载重装。
- **设备适配**：本应用为近方屏设备手作，F-Droid / Izzy 的审核设备多为常规比例手机，若对方反馈显示异常，附上 Titan 2 Elite 的实机截图说明即可。
- **Google Play / 国内商店**：本轮不处理。相关门槛（targetSdk 36、正式签名、无障碍合规披露、软著与 APP 备案等）另议。
