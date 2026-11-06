# DuoLauncher 隐私政策 / Privacy Policy / プライバシーポリシー

生效日期 / Effective date / 発効日：2026-11-06
适用版本 / Applies to / 対象バージョン：v1.5.0 及以后 / and later / 以降

本项目为个人开源项目，源码与问题反馈地址：https://github.com/d3ja7u/DuoLauncher
This is a personal open-source project. Source and issue tracker: https://github.com/d3ja7u/DuoLauncher
本プロジェクトは個人のオープンソースプロジェクトです。ソースと Issue：https://github.com/d3ja7u/DuoLauncher

---

## 简体中文

### 一句话结论

**DuoLauncher 不收集、不上传、不共享任何个人数据。** 没有账号体系、没有登录、没有广告、没有统计分析、没有崩溃上报、没有追踪 SDK，也不包含 Google Play Services / Firebase 等专有组件。

### 本机存储的数据

应用只在设备本地保存一个配置文件（JSON）与一个语言偏好项，内容包括：桌面图标布局与顺序、侧边栏内容、分类与隐藏应用列表、壁纸与界面外观设置、手势开关、通知角标设置、界面语言、可选的更新源地址、上次检查更新的时间与上次看到的版本号。这些数据仅用于还原你自己的桌面配置，**不会离开设备**，卸载应用即全部删除；也可以在系统「设置 → 应用 → DuoLauncher → 清除数据」中随时清除。

### 网络访问

唯一的联网功能是**可选的「检查更新」**（设置 → 更新）。它会向 GitHub Releases API（默认 `https://api.github.com/repos/d3ja7u/DuoLauncher/releases/latest`，接口受限时自动回退到 `releases.atom`）或你在设置里自行填写的地址发起一次普通 HTTPS GET 请求。请求中**不包含**设备标识、账号、位置、已安装应用列表或任何本机配置。响应只被用来解析版本号、更新说明以及安装包的文件名与大小。

- 该功能默认需要你手动点击「检查更新」；开启「自动检查更新」后也设有最短检查间隔，可在设置里随时关闭。
- 应用**不会自动下载、安装或执行任何 APK**，也不申请安装未知应用的权限。发现新版本时只显示一个提示框，是否打开下载页、是否复制链接，完全由你主动点击决定。
- 更新源地址可以清空或改成任意地址。若把地址改为第三方服务，该服务将按它自己的政策处理请求，这一点由你自行决定。
- GitHub 属于专有网络服务，因此在 F-Droid 类仓库中本应用会带 `NonFreeNet` 标记。

除上述功能外，应用不发起任何网络请求，也没有后台数据同步。

### 权限逐项说明

| 权限 / 能力 | 用途 | 是否必需 |
|---|---|---|
| `QUERY_ALL_PACKAGES` | 枚举已安装应用，用于 App 资源库、字母索引与搜索 | 启动器核心功能，必需；应用列表仅在本地使用 |
| 通知使用权（`BIND_NOTIFICATION_LISTENER_SERVICE`） | 统计未读通知数量以显示角标 | 可选，仅本机处理，不读取通知正文上传 |
| 无障碍服务（`BIND_ACCESSIBILITY_SERVICE`） | 仅用于「双击桌面熄屏」，调用系统锁屏全局操作 | 可选；`canRetrieveWindowContent=false`，不读取屏幕内容、不监听输入、不自主执行动作 |
| `EXPAND_STATUS_BAR` | 下滑手势展开通知栏 / 控制中心 | 可选，未授权时有降级处理 |
| `CAMERA` / `FLASHLIGHT` | 可选的手电筒快捷动作 | 可选，不会拍照或录像 |
| `ACCESS_WIFI_STATE` / `ACCESS_NETWORK_STATE` | 在自定义状态栏绘制 Wi-Fi 与信号指示 | 仅本机读取系统状态 |
| `INTERNET` | 上述可选的更新检查 | 可选，关闭更新检查后不产生业务请求 |
| `VIBRATE` | 长按等操作的触感反馈 | 可选 |
| `REQUEST_DELETE_PACKAGES` | 图标菜单中的卸载入口，需系统确认 | 可选 |
| `BIND_APPWIDGET` | 桌面小组件 | 可选 |
| 设备管理器（`BIND_DEVICE_ADMIN`） | 个别 ROM 上熄屏的备用方案 | 可选，从不自动激活，可在系统设置中停用 |

所有可选权限都可以在系统设置中随时撤销，撤销后对应功能停用，不影响桌面主体功能。

### 第三方组件

仅使用 AndroidX / Jetpack Compose / Kotlin 等官方开源库（Apache-2.0）。不含广告 SDK、统计 SDK、崩溃上报 SDK、推送 SDK 或任何闭源二进制依赖。

### 儿童与数据共享

本应用不面向特定年龄群体，不要求注册，也不以任何方式识别使用者身份。因为没有数据收集，所以不存在向第三方共享、出售、跨境传输或用于自动化决策的情形。

### 你的权利与联系方式

你可以随时清除应用数据、撤销权限、卸载应用，或自行导出/删除本机配置文件。如有隐私相关问题，请在 https://github.com/d3ja7u/DuoLauncher/issues 提交 Issue。

### 政策变更

若将来引入任何会改变上述结论的功能，会先更新本文件并在 Release 说明中注明。

---

## English

### Summary

**DuoLauncher does not collect, upload, or share any personal data.** There is no account system, no sign-in, no ads, no analytics, no crash reporting, no tracking SDK, and no proprietary components such as Google Play Services or Firebase.

### On-device storage

The app keeps one local JSON configuration file and one language preference, containing home screen layout and icon order, dock contents, categories and hidden apps, wallpaper and appearance settings, gesture toggles, badge settings, UI language, the optional update source URL, the last update-check timestamp, and the last version seen. This data exists only to restore your own setup, **never leaves the device**, and is removed when you uninstall the app. You can also clear it at any time via Android Settings → Apps → DuoLauncher → Clear data.

### Network access

The only network feature is the **optional update check** (Settings → Updates). It performs a plain HTTPS GET against the GitHub Releases API (default `https://api.github.com/repos/d3ja7u/DuoLauncher/releases/latest`, with automatic fallback to `releases.atom`) or a URL you enter yourself. The request contains **no** device identifiers, account information, location, installed-app list, or local settings. The response is parsed only for the version number, release notes, and the APK file name and size.

- The check is triggered by tapping "Check for updates"; if auto-check is enabled it is rate-limited and can be disabled at any time.
- The app **never downloads, installs, or executes an APK** and does not request the install-unknown-apps permission. When a new version exists it only shows a dialog; opening the download page or copying the link always requires a deliberate tap.
- The update source can be cleared or pointed at any endpoint. If you change it to a third-party service, that service's own policy applies.
- GitHub is a proprietary service, so the app carries the `NonFreeNet` anti-feature in F-Droid-style repositories.

No other network requests are made and there is no background synchronization.

### Permissions

| Permission / capability | Purpose | Required |
|---|---|---|
| `QUERY_ALL_PACKAGES` | Enumerate installed apps for the library, alphabet index and search | Core launcher function; the list stays on device |
| Notification Access (`BIND_NOTIFICATION_LISTENER_SERVICE`) | Count unread notifications for badges | Optional; processed on device only, no content uploaded |
| Accessibility Service (`BIND_ACCESSIBILITY_SERVICE`) | Double-tap to sleep, via the system lock-screen action | Optional; `canRetrieveWindowContent=false`, cannot read screen content or input, takes no autonomous actions |
| `EXPAND_STATUS_BAR` | Down-swipe to open the notification shade / quick settings | Optional, with a graceful fallback |
| `CAMERA` / `FLASHLIGHT` | Optional flashlight toggle | Optional; never takes photos or video |
| `ACCESS_WIFI_STATE` / `ACCESS_NETWORK_STATE` | Draw Wi-Fi and signal indicators in the custom status bar | On-device system state only |
| `INTERNET` | The optional update check above | Optional; no business requests when disabled |
| `VIBRATE` | Haptic feedback | Optional |
| `REQUEST_DELETE_PACKAGES` | Uninstall shortcut in the icon menu, with system confirmation | Optional |
| `BIND_APPWIDGET` | Home screen widgets | Optional |
| Device Admin (`BIND_DEVICE_ADMIN`) | Fallback screen-off on some ROMs | Optional; never auto-activated, can be disabled in system settings |

Every optional permission can be revoked in Android settings at any time; the corresponding feature then stops while the launcher keeps working.

### Third-party components

Only official open-source libraries (AndroidX / Jetpack Compose / Kotlin, Apache-2.0). No ad, analytics, crash-reporting or push SDKs, and no closed-source binary dependencies.

### Children and data sharing

The app is not directed at a specific age group, requires no registration, and does not identify users. Since no data is collected, there is no sharing, selling, cross-border transfer, or automated decision-making.

### Your rights and contact

You can clear app data, revoke permissions, uninstall the app, or manage the local configuration file at any time. For privacy questions, open an issue at https://github.com/d3ja7u/DuoLauncher/issues.

### Changes

Any future feature that changes the above will be reflected here first and noted in the release notes.

---

## 日本語

### 要約

**DuoLauncher は個人データを収集・アップロード・共有しません。** アカウント、ログイン、広告、解析、クラッシュレポート、トラッキング SDK、Google Play Services や Firebase などのプロプライエタリなコンポーネントは一切ありません。

### 端末内の保存データ

設定は端末内の JSON ファイル 1 つと言語設定 1 項に保存されます。内容はホーム画面のレイアウトと並び順、サイドドック、カテゴリと非表示アプリ、壁紙・外観設定、ジェスチャー、バッジ設定、表示言語、任意の更新元 URL、最終確認日時、最後に確認したバージョンです。これらは自分の設定を復元するためだけに使われ、**端末外に送信されません**。アンインストールで削除され、Android の設定 → アプリ → DuoLauncher → データを消去 からいつでも消せます。

### ネットワークアクセス

唯一の通信は**任意の「更新確認」**（設定 → 更新）です。GitHub Releases API（既定 `https://api.github.com/repos/d3ja7u/DuoLauncher/releases/latest`、利用不可時は `releases.atom` へ自動フォールバック）、または利用者が指定した URL に通常の HTTPS GET を 1 回送ります。リクエストには端末識別子、アカウント、位置情報、インストール済みアプリ一覧、端末内設定は**含まれません**。レスポンスからはバージョン番号、更新内容、APK のファイル名とサイズのみを読み取ります。

- 確認は「更新を確認」のタップで実行されます。自動確認を有効にした場合も間隔が制限され、設定から無効化できます。
- アプリが **APK をダウンロード・インストール・実行することは一切なく**、不明アプリのインストール権限も要求しません。新バージョン時はダイアログを出すのみで、ページを開く／リンクをコピーするかは利用者の明示的な操作が必要です。
- 更新元は空にすることも任意の URL に変更することもできます。第三者サービスに変更した場合はそのサービスのポリシーが適用されます。
- GitHub はプロプライエタリなサービスのため、F-Droid 系リポジトリでは `NonFreeNet` アンチフィーチャーが付きます。

これ以外の通信はなく、バックグラウンド同期もありません。

### 権限

| 権限・機能 | 用途 | 必須か |
|---|---|---|
| `QUERY_ALL_PACKAGES` | アプリライブラリ・文字インデックス・検索のため全アプリを列挙 | ランチャーの中核機能。一覧は端末内のみ |
| 通知アクセス（`BIND_NOTIFICATION_LISTENER_SERVICE`） | 未読数をバッジに表示 | 任意。端末内処理のみ、本文の送信なし |
| アクセシビリティサービス（`BIND_ACCESSIBILITY_SERVICE`） | ダブルタップでスリープ（システムのロック操作を呼ぶだけ） | 任意。`canRetrieveWindowContent=false` で画面内容・入力を読み取らず、自律動作もしない |
| `EXPAND_STATUS_BAR` | 下スワイプで通知欄／クイック設定 | 任意。未許可時は代替動作 |
| `CAMERA` / `FLASHLIGHT` | 任意の懐中電灯アクション | 任意。撮影・録画はしない |
| `ACCESS_WIFI_STATE` / `ACCESS_NETWORK_STATE` | 独自ステータスバーの Wi-Fi・電波表示 | 端末内のシステム状態のみ |
| `INTERNET` | 上記の任意の更新確認 | 任意。無効時は業務通信なし |
| `VIBRATE` | 触覚フィードバック | 任意 |
| `REQUEST_DELETE_PACKAGES` | アイコンメニューのアンインストール（システム確認あり） | 任意 |
| `BIND_APPWIDGET` | ホーム画面ウィジェット | 任意 |
| デバイス管理（`BIND_DEVICE_ADMIN`） | 一部 ROM での代替スリープ | 任意。自動有効化せず、システム設定で無効化可能 |

任意の権限は Android の設定からいつでも取り消せます。対応機能は停止しますが、ランチャー本体は動作します。

### サードパーティ製コンポーネント

AndroidX / Jetpack Compose / Kotlin などの公式オープンソースライブラリ（Apache-2.0）のみ。広告・解析・クラッシュレポート・プッシュの SDK やクローズドソースのバイナリ依存はありません。

### 子どもとデータ共有

特定の年齢層を対象とせず、登録も不要で、利用者を識別しません。データ収集がないため、第三者共有・販売・越境移転・自動意思決定も発生しません。

### 権利と連絡先

アプリデータの消去、権限の取り消し、アンインストール、設定ファイルの管理はいつでも可能です。プライバシーに関する質問は https://github.com/d3ja7u/DuoLauncher/issues へ Issue を投稿してください。

### 変更

上記を変える機能を将来追加する場合は、まず本ファイルを更新し、リリースノートにも記載します。
