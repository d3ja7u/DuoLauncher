# DuoLauncher R8 保留规则
#
# AGP 的默认规则（proguard-android-optimize.txt + aapt 生成的规则）已经会保留
# AndroidManifest.xml 里声明的 Activity / Service / Receiver，所以这里只列额外需要照顾的部分。

# 无障碍服务、通知监听服务通过系统按类名回调，显式保留，避免被 R8 改名或裁掉
-keep class com.duolauncher.service.** { *; }
-keep class com.duolauncher.DuoDeviceAdmin { *; }

# 反射调用StatusBarManager.expandNotificationsPanel() / expandSettingsPanel()
# 访问的是系统框架类（hidden API），不是本应用内的类，不需要 keep。
# 保留调用处字符串常量，便于在部分 ROM 上按方法名查找到对应重载。
-keepnames class android.app.StatusBarManager { *; }

# Kotlin / Compose 的默认规则由 AGP 与各库自带的 consumer rules 提供。
# 项目内的数据持久化全部使用 org.json 手工读写，不依赖反射序列化，无需额外 keep。
