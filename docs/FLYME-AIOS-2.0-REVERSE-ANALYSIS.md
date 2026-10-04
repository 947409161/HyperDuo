# Flyme AIOS 2.0 SystemUI Hook 移植评估

## 结论

`系统界面_16.26.06.23_base.apk` 的 DEX 中确认了 Flyme AIOS 的状态栏实现。模块的 LSPosed 入口仍可匹配 `com.android.systemui`，但当前功能实现绑定在 MIUI 私有类上，不能据此认为现有 Hook 已能在 Flyme 工作。移植需要新增 Flyme 适配路径，不能只改目标包名。

## APK 中确认的实现

APK 含 `classes.dex` 至 `classes5.dex`。DEX 字符串与类型表包含以下实现：

| 功能 | Flyme / AOSP 类或入口 | 观察结果 |
| --- | --- | --- |
| 电池状态栏视图 | `com.flyme.statusbar.battery.FlymeBatteryMeterView` | Flyme 自有电池视图；包含 `onDraw`、`onDarkChanged`、`onBatteryLevelChanged` 等方法名 |
| 电池绘制 | `com.flyme.statusbar.battery.BatteryMeterDrawable` | Flyme 自有 Drawable，与当前模块假设的 MIUI `MiuiBatteryMeterIconView` 不同 |
| Wi‑Fi 视图 | `com.flyme.systemui.statusbar.net.wifi.FlymeStatusBarWifiView` | 自有 Wi‑Fi View；DEX 中可见 `getSlot`、`setVisibleState`、`updateState` 等入口 |
| 移动信号视图 | `com.flyme.systemui.statusbar.net.mobile.ui.view.FlymeModernStatusBarMobileView` | Flyme 对现代移动信号 View 的实现 |
| 移动信号绑定 | `com.flyme.systemui.statusbar.net.mobile.ui.binder.FlymeMobileIconBinder` | 通过新的 binder/pipeline 更新视图 |
| 图标容器 | `com.android.systemui.statusbar.phone.StatusIconContainer` | 使用 AOSP 命名的容器，而不是 `MiuiStatusIconContainer` |

因此这个版本同时有 AOSP `com.android.systemui.battery.BatteryMeterView`、现代状态栏 pipeline 和 Flyme 自定义视图。不同容器可能分别服务状态栏、锁屏或下拉面板，移植时必须先确认真正的状态栏宿主和 slot 所有权。

## 与当前模块的差异

当前 [`TrioHooks.java`](../app/src/main/java/io/github/yixing233/hyperduo/TrioHooks.java) 的十组 Hook 分别查找 `MiuiPhoneStatusBarView`、`MiuiBatteryMeterIconView`、`MiuiBatteryMeterView`、`MiuiStatusIconContainer`、`MiuiStatusBarIconViewHelper`、`MobileTypeDrawable`、`MobileSignalAnimatorContainer` 和 `MiuiStatusBatteryContainer` 等类。APK 中对应的关键宿主换成了上表的 Flyme 类；即使入口按 `com.android.systemui` 命中，这些 MIUI 类解析仍会失败，电池绘制、信号采样/抑制与充电状态同步都不会自动迁移。

## 当前适配

检测到 Flyme 电池视图和 Wi‑Fi 视图后，入口现在会安装独立的 `FlymeHooks`，保留现有 MIUI Hook 路径。适配器复用 `FlymeBatteryMeterView.onDraw(Canvas)` 绘制、从 `mLastLevel` / `mCharging` / `mQuickCharging` / `mLowPowerMode` 读取电池状态；Flyme 将 `mLastLevel` 初始化为 `-1` 时，通过 `BatteryManager.BATTERY_PROPERTY_CAPACITY` 取当前电量作为回退；颜色从 `onDarkChanged` 读取。Wi‑Fi 状态优先读取 `FlymeStatusBarWifiView.mState.resId`，图标资源未能分类时再通过 `WifiManager` 采样；移动信号变化通过 `SignalDrawable.onLevelChange` 触发双卡状态刷新。主状态栏容器从 `PhoneStatusBarView.mSystemIconArea` 下查找，并基于 slot 保存和恢复 Flyme 原生视图可见性。

这一步完成了 Hook 适配的首条路径，但还不是功能完全对齐：网络类型标签和环外信号布局仍走不到 Flyme 的 binder 表示；Flyme 没有按当前证据确认的 MIUI 充电超级岛对应入口。容器定位也只处理主状态栏的 `mSystemIconArea`，不会假定锁屏或控制中心使用同一个容器。

## 证据范围与待验证项

结论来自 `系统界面_16.26.06.23_base.apk` 的目录、DEX 类型/字段/方法表和当前 Hook 源码对照。静态检查确认了这里使用的类、字段和方法名，但不能确认真实设备上的布局实例、slot 运行值、颜色参数语义、第三方框架兼容性或各开关的视觉结果。还需在对应 AIOS 2.0 设备上检查模块加载日志，并验证总开关恢复、Wi‑Fi/移动图标让位、双卡、充电、暗色状态栏与面板展开。

## 证据范围

本结论来自仓库中的 `系统界面_16.26.06.23_base.apk` 的 APK 目录、DEX 字符串/类型/方法名静态检查，以及与当前 Hook 源码的对照。没有从该静态证据推断出未核实的 View 字段布局或 binder 参数值；在完成这些确认之前，不应宣称移植已完成。
