# DuoLauncher 隐私政策 / Privacy Policy / プライバシーポリシー

生效日期 / Effective date / 発効日：2026-11-07
适用版本 / Applies to / 対象バージョン：v1.5.6 及以后 / and later / 以降

本项目为个人开源项目，源码与问题反馈地址：https://github.com/d3ja7u/DuoLauncher
This is a personal open-source project. Source and issue tracker: https://github.com/d3ja7u/DuoLauncher
本プロジェクトは個人のオープンソースプロジェクトです。ソースと Issue：https://github.com/d3ja7u/DuoLauncher

---

## 简体中文

### 一句话结论

**DuoLauncher 没有账号、没有广告，不读取也不上传你的任何个人内容。** 唯一会离开设备的数据是可选的「匿名使用统计」：一个随机生成的匿名 ID、应用版本、界面语言、Android API 等级和上报类型。它不与身份、设备标识或使用内容关联，**首次启动会先询问一次，随时可在设置里关闭，关闭后本机匿名 ID 立即删除**。应用不含第三方统计 / 广告 / 崩溃上报 SDK，也不含 Google Play Services / Firebase 等专有组件。

### 本机存储的数据

应用在设备本地保存一个配置文件（JSON）与一个语言偏好项，内容包括：桌面图标布局与顺序、侧边栏内容与显隐、分类与隐藏应用列表、壁纸与界面外观设置、手势开关、通知角标设置、界面语言、搜索设置、更新源地址、上次检查更新的时间与上次看到的版本号，以及统计开关与上报地址。这些数据仅用于还原你自己的桌面配置，**不会离开设备**，卸载应用即全部删除；也可在系统「设置 → 应用 → DuoLauncher → 清除数据」中清除。

**壁纸图片**：设置 → 壁纸自动切换中从相册选中的图片，会复制到应用私有目录 `files/wp_rotate/`，仅用于在本机设为系统主屏壁纸。不会上传、不写入公共目录、不需要外部存储权限；「清空已选图片」或清除应用数据即全部删除。

**匿名 ID 单独存放**：统计用的随机 UUID 保存在一个独立的偏好文件里，**不写入配置备份**，因此导出/导入备份不会把匿名 ID 带给别人或被别人的 ID 覆盖。卸载、清除数据、或在设置里关闭统计都会立即删除它。

**设置备份**：设置 → 备份与恢复 → 导出设置，会把配置写成 JSON 文件，保存位置完全由你在系统文件选择器里决定。应用不会自动上传，也不会访问该位置以外的文件；导入时只读取你主动选中的那一份文件并整体覆盖当前配置。文件里除配置外还记录导出时间与应用版本号，不含匿名 ID、通知角标已读状态、壁纸图片、应用数据、通讯录或账号信息。此功能不需要存储权限（使用系统文件选择器 SAF）。

### 网络访问

应用只有两类网络请求，都可由你关闭。

**1. 检查更新与在线安装**（设置 → 更新）。向 GitHub Releases API（默认 `https://api.github.com/repos/d3ja7u/DuoLauncher/releases/latest`，受限时回退 `releases.atom`）或你填写的地址发起一次普通 HTTPS GET。请求**不含**设备标识、账号、位置、已安装应用列表或本机配置；响应只用来解析版本号、更新说明、安装包文件名与大小。只有你主动点「下载并安装」才会再发起 APK 下载请求。

- 该功能默认需要手动点击；开启「自动检查更新」后也有最短检查间隔，可随时关闭。
- 是否下载、是否安装**完全由你主动点击决定**，不会后台自动下载或静默安装。APK 只写入应用私有缓存 `cache/updates/`，不写公共目录、不申请外部存储权限；下载完经 FileProvider 临时授权给系统安装器，由你在系统弹窗中确认。首次使用需在系统设置里允许「安装未知来源应用」（`REQUEST_INSTALL_PACKAGES`），该授权可随时撤销。也可只打开下载页，完全跳过下载。
- 更新源地址可清空或改成任意地址；改成第三方服务时，由该服务自己的政策处理请求。
- GitHub 属于专有网络服务，因此在 F-Droid 类仓库中本应用带 `NonFreeNet` 标记。

**2. 匿名使用统计**（设置 → 隐私与统计）。用于让开发者知道「有多少设备在用、都在用哪个版本」。

- **采集字段**：本应用自己生成的随机 UUID（**不是** IMEI、序列号、Android ID、广告 ID 或账号）、应用版本与 versionCode、界面语言（如 `zh` / `en` / `ja`）、Android API 等级、上报类型（`install` 首次安装 / `upgrade` 版本升级 / `heartbeat` 每日活跃）。
- **发送时机**：首次启动并确认告知后一次；应用升级后一次；之后每天最多一次心跳（按 24 小时节流）。失败静默，不弹提示、不反复重试，不影响桌面使用。
- **默认接收端**：`https://duolauncher-stats.app.workbuddy.host/ping`，汇总页 `https://duolauncher-stats.app.workbuddy.host/`。接收端由应用托管平台提供，**不落盘 IP**（访问日志刻意剔除客户端地址）、不写 Cookie、不做设备指纹；本机原始匿名记录可用 `/export` 导出。上报记录除保存在接收端本机 SQLite 外，还会同步到 WorkBuddy 云数据库的 `stats_pings` 表，**字段仍只有上述那几项，不新增任何信息**；云端开启行级权限、客户端只能追加写入，看板只读取聚合结果。云端服务按每月资源点额度运行，额度用尽会自动暂停而不会产生费用。需要完全掌控数据时，可在设置里把地址改为自建服务（仓库 `tools/stats-server` 提供零依赖脚本与 Docker / compose 配置）。
- **不发送**：设备型号 / 序列号 / IMEI / Android ID / 广告 ID、位置、通讯录、短信、账号、壁纸、已安装应用列表、桌面布局、搜索关键词、通知内容或任何文件内容。（网络层面运营商与托管平台仍能看到请求来源 IP，但接收端不保存。）
- **关闭方式**：设置 → 隐私与统计 → 关闭「匿名使用统计」。关闭后**立即删除本机匿名 ID**并停止一切上报；再次打开会生成一个全新 ID，**无法与之前的记录关联**。把「统计上报地址」清空也能彻底停用。关闭后所有启动器功能完全不受影响。
- **留存与删除**：服务端按匿名 ID 去重保存首次 / 最后活跃时间与版本分布，不设自动删除期限。因为记录不含任何身份信息，服务端无法定位到具体个人；如需删除历史记录，可在 Issue 中提供匿名 ID 前缀，或自托管后自行管理数据。
- 若把上报地址改为第三方服务，该服务按自己的政策处理数据，由你自行决定。

**网页搜索**只是把关键词拼进搜索 URL，用 `Intent.ACTION_VIEW` 交给系统默认浏览器打开；实际联网发生在浏览器里，由浏览器按它自己的政策处理，应用不记录关键词、不接收搜索结果。除上述两类请求外，应用不发起任何网络请求，也没有后台数据同步。

### 权限逐项说明

| 权限 / 能力 | 用途 | 是否必需 |
|---|---|---|
| `QUERY_ALL_PACKAGES` | 枚举已安装应用，用于 App 资源库、字母索引与搜索 | 启动器核心功能，必需；应用列表仅在本地使用 |
| `READ_CONTACTS` | 在搜索结果里按姓名 / 号码匹配通讯录联系人 | 可选，首次使用时按需申请；只在设备本地查询，不上传、不保存副本 |
| 通知使用权（`BIND_NOTIFICATION_LISTENER_SERVICE`） | 统计未读通知数量以显示角标 | 可选，仅本机处理，不读取通知正文上传 |
| 无障碍服务（`BIND_ACCESSIBILITY_SERVICE`） | 仅用于「双击桌面息屏」，调用系统锁屏全局操作 | 可选；`canRetrieveWindowContent=false`，不读取屏幕内容、不监听输入、不自主执行动作 |
| `EXPAND_STATUS_BAR` | 下滑手势展开通知栏 / 控制中心 | 可选，未授权时有降级处理 |
| `SET_WALLPAPER` | 设置系统主屏壁纸（壁纸自动切换 / 上滑切换） | 可选；所选图片只保存在应用私有目录，不上传 |
| `CAMERA` / `FLASHLIGHT` | 可选的手电筒快捷动作 | 可选，不会拍照或录像 |
| `ACCESS_WIFI_STATE` / `ACCESS_NETWORK_STATE` | 在自定义状态栏绘制 Wi-Fi 与信号指示 | 仅本机读取系统状态 |
| `INTERNET` | 上述检查更新、在线安装与可选的匿名统计 | 可选；关闭更新检查与匿名统计后不产生业务请求 |
| `VIBRATE` | 长按等操作的触感反馈 | 可选 |
| `REQUEST_DELETE_PACKAGES` | 图标菜单中的卸载入口，需系统确认 | 可选 |
| `REQUEST_INSTALL_PACKAGES` | 在线更新时调起系统安装器；安装仍须你在系统弹窗中确认 | 可选，仅手动点「下载并安装」时用到，可在系统设置中撤销 |
| `BIND_APPWIDGET` | 桌面小组件 | 可选 |
| 设备管理器（`BIND_DEVICE_ADMIN`） | 个别 ROM 上息屏的备用方案 | 可选，从不自动激活，可在系统设置中停用 |

所有可选权限都可以在系统设置中随时撤销，撤销后对应功能停用，不影响桌面主体功能。匿名统计**不需要任何额外权限**（只用已有的 `INTERNET`）。

### 第三方组件

仅使用 AndroidX / Jetpack Compose / Kotlin 等官方开源库（Apache-2.0）。不含广告 SDK、统计 SDK、崩溃上报 SDK、推送 SDK 或任何闭源二进制依赖；匿名统计由应用自身用 `HttpURLConnection` 实现，没有嵌入第三方 SDK。统计接收端是应用托管平台提供的服务，可按上文改为自建。

### 儿童与数据共享

本应用不面向特定年龄群体，不要求注册，也不以任何方式识别使用者身份。匿名统计仅用于汇总计数与版本分布，不用于画像、广告、推荐或自动化决策，也不与任何身份信息关联。数据只提交给上述统计接收端（或你自建的地址），不共享、不出售给第三方，不用于跨境传输业务。

### 你的权利与联系方式

你可以随时关闭匿名统计（同时删除本机匿名 ID）、清空上报地址、清除应用数据、撤销权限、卸载应用，或自行导出/删除本机配置文件。如有隐私相关问题或需要删除历史匿名记录，请在 https://github.com/d3ja7u/DuoLauncher/issues 提交 Issue。

### 政策变更

若将来引入任何会改变上述结论的功能，会先更新本文件并在 Release 说明中注明。

---

## English

### Summary

**DuoLauncher has no accounts, no ads, and never reads or uploads your personal content.** The only data that can leave the device is the optional anonymous usage statistic: a randomly generated ID, app version, UI language, Android API level and report type. It is not linked to your identity, device identifiers or usage content. **You are asked once on first launch, you can turn it off at any time in Settings, and turning it off immediately deletes the local anonymous ID.** No third-party analytics, ad, crash-reporting SDK, and no proprietary components such as Google Play Services or Firebase.

### On-device storage

The app keeps one local JSON configuration file and one language preference, containing home screen layout and icon order, dock contents, categories and hidden apps, wallpaper and appearance settings, gesture toggles, badge settings, UI language, search settings, the update source URL, the last update-check timestamp, the last version seen, and the statistics toggle/endpoint. This exists only to restore your own setup, **never leaves the device**, and is removed on uninstall. You can clear it via Android Settings → Apps → DuoLauncher → Clear data.

**Wallpaper images**: pictures you pick in Settings → Wallpaper rotation are copied into the app's private directory (`files/wp_rotate/`) and used only to set the home screen wallpaper on this device. They are never uploaded, never written to shared storage, and no external-storage permission is needed. "Clear selected images" or clearing app data removes them all.

**The anonymous ID is stored separately**, in its own preference file, and is **excluded from settings backups** — exporting or importing a backup never transfers an ID. Uninstalling, clearing data, or disabling statistics deletes it immediately.

**Settings backup**: Settings → Backup and restore → Export settings writes the configuration to a JSON file at a location you choose with the system file picker. Nothing is uploaded; importing reads only the file you explicitly pick. Apart from the settings, the file holds the export time and app version — no anonymous ID, badge read state, wallpaper image, app data, contacts or account information. No storage permission is needed (Storage Access Framework).

### Network access

The app makes only two kinds of network requests, both of which you can disable.

**1. Update check and in-app install** (Settings → Updates). A plain HTTPS GET against the GitHub Releases API (default `https://api.github.com/repos/d3ja7u/DuoLauncher/releases/latest`, falling back to `releases.atom`) or a URL you enter. It contains **no** device identifiers, account, location, installed-app list or local settings; the response is parsed only for the version, release notes and APK name/size. An APK download happens only after you tap Download and install.

- Triggered manually by default; auto-check is rate-limited and can be disabled.
- Downloading and installing are **always your deliberate tap** — nothing is fetched in the background or installed silently. The APK is written only to the app's private cache (`cache/updates/`), shared with the system installer via a temporary FileProvider grant, and the install still needs your confirmation. On first use you must grant Install unknown apps (`REQUEST_INSTALL_PACKAGES`), revocable at any time. You can just open the release page instead.
- The update source can be cleared or pointed elsewhere; a third-party endpoint follows its own policy.
- GitHub is a proprietary service, so the app carries the `NonFreeNet` anti-feature in F-Droid-style repositories.

**2. Anonymous usage statistics** (Settings → Privacy and statistics), so the developer can tell how many devices use the launcher and which versions are in use.

- **Collected**: a random UUID generated by the app itself (**not** IMEI, serial, Android ID, advertising ID or an account), app version and versionCode, UI language (e.g. `zh` / `en` / `ja`), Android API level, and report type (`install` / `upgrade` / `heartbeat`).
- **When**: once after you confirm the first-launch notice, once after an app upgrade, and at most one daily heartbeat afterwards (24-hour throttle). Failures are silent — no prompts, no retry storms, no impact on the launcher.
- **Default endpoint**: `https://duolauncher-stats.app.workbuddy.host/ping`, summary page `https://duolauncher-stats.app.workbuddy.host/`. The receiver does **not store IP addresses** (client addresses are stripped from access logs), writes no cookies and does no device fingerprinting; local raw anonymous records can be exported via `/export`. Reports are kept in the receiver's local SQLite and also synced to the `stats_pings` table in the WorkBuddy cloud database, **with exactly the same fields and no additional information**; that table is row-level secured, clients may only append rows, and the dashboard reads aggregates only. The cloud tier runs on a monthly resource-point allowance and pauses automatically when exhausted, without incurring charges. For full control, point the endpoint at your own server (`tools/stats-server` ships a zero-dependency script plus Docker / compose files).
- **Never sent**: device model / serial / IMEI / Android ID / advertising ID, location, contacts, messages, accounts, wallpaper, installed app list, home layout, search queries, notification content or any file content. (Your carrier and the hosting platform still see the source IP at the network level, but the receiver does not store it.)
- **Opt out**: Settings → Privacy and statistics → turn off Anonymous usage statistics. This **immediately deletes the local anonymous ID** and stops all reporting; turning it back on creates a brand-new ID that **cannot be linked** to previous records. Clearing the endpoint also disables it completely. Every launcher feature keeps working.
- **Retention and deletion**: the server keeps first/last active time and version distribution keyed by the anonymous ID, with no automatic expiry. Because records contain no identity, the server cannot locate a specific person. To delete historical records, open an issue with the ID prefix, or self-host and manage the data yourself.
- If you point the endpoint at a third-party service, that service's policy applies.

**Web search** only builds a search URL and hands it to the system default browser via `Intent.ACTION_VIEW`; the request happens inside the browser under its own policy. The launcher neither logs the query nor receives the results. Apart from these two request types, the app makes no network calls and has no background sync.

### Permissions

| Permission / capability | Purpose | Required |
|---|---|---|
| `QUERY_ALL_PACKAGES` | Enumerate installed apps for the library, alphabet index and search | Core launcher function; the list stays on device |
| `READ_CONTACTS` | Match contact names / numbers in search results | Optional, requested on first use; on-device query only, never uploaded |
| Notification Access (`BIND_NOTIFICATION_LISTENER_SERVICE`) | Count unread notifications for badges | Optional; on device only, no content uploaded |
| Accessibility Service (`BIND_ACCESSIBILITY_SERVICE`) | Double-tap to sleep via the system lock-screen action | Optional; `canRetrieveWindowContent=false`, cannot read screen content or input |
| `EXPAND_STATUS_BAR` | Down-swipe to open the notification shade / quick settings | Optional, with a graceful fallback |
| `SET_WALLPAPER` | Set the home screen wallpaper (auto-rotate / swipe-up) | Optional; selected images stay in the app's private storage, never uploaded |
| `CAMERA` / `FLASHLIGHT` | Optional flashlight toggle | Optional; never takes photos or video |
| `ACCESS_WIFI_STATE` / `ACCESS_NETWORK_STATE` | Draw Wi-Fi and signal indicators in the custom status bar | On-device system state only |
| `INTERNET` | Update check, in-app install and optional anonymous statistics | Optional; no business requests when both are disabled |
| `VIBRATE` | Haptic feedback | Optional |
| `REQUEST_DELETE_PACKAGES` | Uninstall shortcut in the icon menu, with system confirmation | Optional |
| `REQUEST_INSTALL_PACKAGES` | Opens the system installer for in-place updates; install still needs your confirmation | Optional, only when you tap Download and install; revocable |
| `BIND_APPWIDGET` | Home screen widgets | Optional |
| Device Admin (`BIND_DEVICE_ADMIN`) | Fallback screen-off on some ROMs | Optional; never auto-activated |

Every optional permission can be revoked at any time. Anonymous statistics need **no additional permission** (only the existing `INTERNET`).

### Third-party components

Only official open-source libraries (AndroidX / Jetpack Compose / Kotlin, Apache-2.0). No ad, analytics, crash-reporting or push SDKs, and no closed-source binary dependencies; the anonymous statistic is implemented in the app with `HttpURLConnection`, without any embedded SDK. The receiving endpoint is hosted by an app platform and can be replaced with your own as described above.

### Children and data sharing

The app is not directed at a specific age group, requires no registration, and does not identify users. Anonymous statistics are used only for aggregate counts and version distribution — no profiling, ads, recommendations or automated decision-making, and no link to any identity. Data goes only to the endpoint above (or one you self-host); it is never shared or sold to third parties.

### Your rights and contact

You can turn off statistics (which also deletes the local ID), clear the endpoint, clear app data, revoke permissions, uninstall, or manage the local configuration file at any time. For privacy questions or deletion of historical anonymous records, open an issue at https://github.com/d3ja7u/DuoLauncher/issues.

### Changes

Any future feature that changes the above will be reflected here first and noted in the release notes.

---

## 日本語

### 要約

**DuoLauncher にはアカウントも広告もなく、個人の内容を読み取ったり送信したりしません。** 端末外に出る可能性があるのは任意の「匿名利用統計」だけです。内容はランダムに生成した匿名 ID、アプリバージョン、表示言語、Android API レベル、種別のみで、身元・端末識別子・利用内容とは結び付きません。**初回起動時に一度確認し、設定からいつでもオフにでき、オフにすると端末内の匿名 ID をただちに削除します。** 第三者の解析・広告・クラッシュレポート SDK や、Google Play Services / Firebase などのプロプライエタリなコンポーネントはありません。

### 端末内の保存データ

設定は端末内の JSON ファイル 1 つと言語設定 1 項に保存されます。ホーム画面のレイアウトと並び順、サイドドック、カテゴリと非表示アプリ、壁紙・外観設定、ジェスチャー、バッジ設定、表示言語、検索設定、更新元 URL、最終確認日時、最後に確認したバージョン、統計のオン・オフと送信先です。自分の設定を復元するためだけに使われ、**端末外に送信されません**。アンインストールで削除され、Android の設定 → アプリ → DuoLauncher → データを消去 からも消せます。

**壁紙画像**：設定 → 壁紙自動切替で選んだ画像はアプリの私有ディレクトリ（`files/wp_rotate/`）にコピーされ、この端末のホーム壁紙を設定するためだけに使います。外部送信せず、共有ストレージにも書き込まず、ストレージ権限も不要です。「選択画像をクリア」またはデータ消去で全て削除されます。

**匿名 ID は別ファイルに保存**され、**設定バックアップには含まれません**。バックアップの書き出し・読み込みで ID が移ったり上書きされたりしません。アンインストール、データ消去、統計のオフでただちに削除されます。

**設定のバックアップ**：設定 → バックアップと復元 → 設定を書き出す と、システムのファイル選択画面で指定した場所へ JSON を保存します。自動アップロードはなく、読み込みも利用者が選んだ 1 ファイルだけです。設定以外には書き出し日時とアプリバージョンのみで、匿名 ID・通知バッジの既読状態・壁紙画像・アプリデータ・連絡先・アカウント情報は含まれません。ストレージ権限は不要です（SAF を利用）。

### ネットワークアクセス

通信は 2 種類だけで、どちらも無効化できます。

**1. 更新確認とアプリ内インストール**（設定 → 更新）。GitHub Releases API（既定 `https://api.github.com/repos/d3ja7u/DuoLauncher/releases/latest`、利用不可時は `releases.atom`）、または利用者が指定した URL に通常の HTTPS GET を 1 回送ります。端末識別子・アカウント・位置情報・インストール済みアプリ一覧・端末内設定は**含まれず**、レスポンスからはバージョン・更新内容・APK 名とサイズのみを読み取ります。APK のダウンロードは「ダウンロードしてインストール」をタップした場合のみです。

- 既定では手動実行。自動確認を有効にしても間隔が制限され、いつでも無効化できます。
- ダウンロードとインストールは**常に利用者の明示的なタップ**で開始され、バックグラウンドの自動ダウンロードやサイレントインストールはありません。APK はアプリ専用キャッシュ（`cache/updates/`）にのみ保存し、FileProvider でシステムのインストーラーへ一時共有、インストール自体もシステム画面で確認が必要です。初回は「提供元不明のアプリのインストール」（`REQUEST_INSTALL_PACKAGES`）が必要で、いつでも取り消せます。ダウンロードせずページを開くだけにもできます。
- 更新元は空欄または任意の URL に変更可能です。第三者サービスに変更した場合はそのポリシーが適用されます。
- GitHub はプロプライエタリなサービスのため、F-Droid 系リポジトリでは `NonFreeNet` アンチフィーチャーが付きます。

**2. 匿名利用統計**（設定 → プライバシーと統計）。利用台数とバージョン分布を把握するためのものです。

- **収集項目**：アプリ自身が生成するランダム UUID（IMEI・シリアル・Android ID・広告 ID・アカウントでは**ありません**）、アプリバージョンと versionCode、表示言語（例 `zh` / `en` / `ja`）、Android API レベル、種別（`install` / `upgrade` / `heartbeat`）。
- **送信タイミング**：初回起動の確認後 1 回、バージョン更新後 1 回、以後は 1 日最大 1 回のハートビート（24 時間で抑制）。失敗しても静かに諦め、通知や再試行の連打はなく、ランチャーの動作に影響しません。
- **既定の送信先**：`https://duolauncher-stats.app.workbuddy.host/ping`、集計ページ `https://duolauncher-stats.app.workbuddy.host/`。受信側はアプリホスティングのサービス上にあり、**IP を保存しません**（アクセスログからクライアントアドレスを除外）、Cookie やデバイスフィンガープリントも使いません。`/export` でローカルの匿名生記録を書き出せます。記録は受信側のローカル SQLite に加え、WorkBuddy クラウドデータベースの `stats_pings` テーブルにも同期されますが、**項目は上記と完全に同じで、情報は一切増えません**。クラウド側は行レベル権限が有効で、クライアントは追記のみ、ダッシュボードは集計値だけを読み取ります。クラウドサービスは毎月のリソースポイント枠で動作し、枠を使い切ると課金なしで自動停止します。データを完全に管理したい場合は、設定から自前サーバー（`tools/stats-server` の零依存スクリプトと Docker / compose 設定）に変更してください。
- **送信しないもの**：端末型番・シリアル・IMEI・Android ID・広告 ID、位置情報、連絡先、SMS、アカウント、壁紙、インストール済みアプリ一覧、ホーム配置、検索語、通知内容、あらゆるファイル内容。（ネットワーク上では通信事業者やホスティング側に送信元 IP が見えますが、受信側は保存しません。）
- **オフにする**：設定 → プライバシーと統計 →「匿名利用統計」をオフ。すると**端末内の匿名 ID をただちに削除**し、一切送信しません。再度オンにすると新しい ID が作られ、**以前の記録とは紐付きません**。送信先を空にしても完全に停止できます。オフにしてもランチャーの機能は変わりません。
- **保存と削除**：サーバーは匿名 ID ごとに初回・最終アクティブ日時とバージョン分布を保存し、自動削除期限はありません。記録に身元情報がないため特定個人を割り出せません。履歴の削除を希望する場合は匿名 ID の先頭を添えて Issue へ、または自前運用でデータを管理してください。
- 送信先を第三者サービスに変更した場合、そのサービスのポリシーが適用されます。

**Web 検索**は入力語を検索 URL に組み込み、`Intent.ACTION_VIEW` で標準ブラウザに渡すだけです。実際の通信はブラウザ側で行われ、ランチャーは検索語を記録せず結果も受け取りません。上記 2 種類以外の通信はなく、バックグラウンド同期もありません。

### 権限

| 権限・機能 | 用途 | 必須か |
|---|---|---|
| `QUERY_ALL_PACKAGES` | アプリライブラリ・文字インデックス・検索のため全アプリを列挙 | ランチャーの中核機能。一覧は端末内のみ |
| `READ_CONTACTS` | 検索結果に連絡先の名前／番号を表示 | 任意。初回時に要求。端末内のみで照会し、外部送信しない |
| 通知アクセス（`BIND_NOTIFICATION_LISTENER_SERVICE`） | 未読数をバッジに表示 | 任意。端末内処理のみ、本文の送信なし |
| アクセシビリティサービス（`BIND_ACCESSIBILITY_SERVICE`） | ダブルタップでスリープ（システムのロック操作を呼ぶだけ） | 任意。`canRetrieveWindowContent=false` で画面内容・入力を読み取らない |
| `EXPAND_STATUS_BAR` | 下スワイプで通知欄／クイック設定 | 任意。未許可時は代替動作 |
| `SET_WALLPAPER` | ホーム画面の壁紙を設定（自動切替・上スワイプ） | 任意。選んだ画像はアプリの私有領域にのみ保存し、外部送信しない |
| `CAMERA` / `FLASHLIGHT` | 任意の懐中電灯アクション | 任意。撮影・録画はしない |
| `ACCESS_WIFI_STATE` / `ACCESS_NETWORK_STATE` | 独自ステータスバーの Wi-Fi・電波表示 | 端末内のシステム状態のみ |
| `INTERNET` | 更新確認・アプリ内インストール・任意の匿名統計 | 任意。両方オフなら業務通信なし |
| `VIBRATE` | 触覚フィードバック | 任意 |
| `REQUEST_DELETE_PACKAGES` | アイコンメニューのアンインストール（システム確認あり） | 任意 |
| `REQUEST_INSTALL_PACKAGES` | アプリ内更新でシステムのインストーラーを起動（インストールはシステム画面で要確認） | 任意。「ダウンロードしてインストール」時のみ、取り消し可能 |
| `BIND_APPWIDGET` | ホーム画面ウィジェット | 任意 |
| デバイス管理（`BIND_DEVICE_ADMIN`） | 一部 ROM での代替スリープ | 任意。自動有効化しない |

任意の権限はいつでも取り消せます。匿名統計に**追加の権限は不要**です（既存の `INTERNET` のみ）。

### サードパーティ製コンポーネント

AndroidX / Jetpack Compose / Kotlin などの公式オープンソースライブラリ（Apache-2.0）のみ。広告・解析・クラッシュレポート・プッシュの SDK やクローズドソースのバイナリ依存はありません。匿名統計はアプリ自身が `HttpURLConnection` で実装しており、第三者の SDK は埋め込んでいません。受信側はアプリホスティングのサービスですが、上記のとおり自前運用に変更できます。

### 子どもとデータ共有

特定の年齢層を対象とせず、登録も不要で、利用者を識別しません。匿名統計は集計とバージョン分布のみに使い、プロファイリング・広告・推薦・自動意思決定には使わず、身元とも結び付けません。送信先は上記の端点（または自前サーバー）だけで、第三者への共有・販売は行いません。

### 権利と連絡先

統計のオフ（匿名 ID の削除を伴う）、送信先のクリア、アプリデータの消去、権限の取り消し、アンインストール、設定ファイルの管理はいつでも可能です。プライバシーの質問や匿名記録の削除依頼は https://github.com/d3ja7u/DuoLauncher/issues へ Issue を投稿してください。

### 変更

上記を変える機能を将来追加する場合は、まず本ファイルを更新し、リリースノートにも記載します。

