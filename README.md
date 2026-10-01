# HyperDuo

一个 LSPosed 模块（libxposed **API 102**），把 HyperOS / MIUI 状态栏的电池图标替换成
"三合一"字形：**电池环 + Wi-Fi 弧线 + 移动信号点阵 + 电量数字/充电闪电**，视觉参考
[iPhone Duo / status-trio](https://github.com/lingyired/status-trio)。

作用域（scope）：`com.android.systemui`。设置界面参考
[HyperCopy](https://github.com/1812z/HyperCopy)，用 Compose + [Miuix](https://github.com/miuix-kotlin-multiplatform/miuix) 写成 MIUI 风格。

## 当前状态

| 项 | 状态 |
| --- | --- |
| 模块源码 | 完成 |
| Gradle 构建（APK） | 完成 —— `app\build\outputs\apk\debug\app-debug.apk` |
| 设置 UI（Compose + Miuix） | 完成 —— 完整设置集，含 4 格状态预览 |
| 几何离线校验（渲染） | 通过 —— `work\geocheck\geocheck.png` |
| 信号名解析校验 | 通过 —— 112 个资源名逐条判定正确 |
| **上机安装 / 运行验证** | **通过** —— 小米 14 / HyperOS 4，状态栏、锁屏、控制中心均正常 |
| **设置实时生效（免重启）** | **通过** —— remote preferences 变更即重绘 |

上机确认项：

- 8 个 hook 全部安装（日志 `HyperDuo installed, hooks=8`），无 `AndroidRuntime:E`。
- 状态栏 / 锁屏 / 控制中心的原生 Wi-Fi、移动（含 `stacked_mobile`）、电池图标均被抑制，
  字形正常绘制；屏幕中央不再残留游离的「5G」。
- Wi-Fi 关闭时不画弧，打开后环内出现两段弧 + 中心点（按真实信号等级）。
- 深色背景下前景色取样正确（深色字 + 绿色环）；浅色背景已裁切验证取色链路，未做逐像素比对。
- 在设置界面切换「显示移动信号点」后**不重启 SystemUI**，状态栏点阵立即消失/恢复。

## 构建

需要 JDK 17、Android SDK（platform 37 + build-tools 37.0.0）与 Gradle 9.8，全部已就位于
`.tools\`，且依赖已本地化为可离线仓库，无需联网：

```powershell
$T='C:\code\HyperDuo\.tools'
$env:JAVA_HOME="$T\jdk\jdk-17.0.20.1+1"
$env:GRADLE_USER_HOME="$T\gradle-home"
$env:ANDROID_HOME="$T\sdk"
& "$T\gradle\gradle-9.8.0\bin\gradle.bat" --project-dir C:\code\HyperDuo :app:assembleDebug --offline --no-daemon --console=plain
```

产物：`app\build\outputs\apk\debug\app-debug.apk`。

要点：

- `settings.gradle.kts` 把 `.tools\maven` 作为**第一个**仓库（承载 `io.github.libxposed:api/service`
  的本地副本），并在 mavenCentral 之前，所以 `--offline` 也能解析。
  `dependencyResolutionManagement` 用的是 `FAIL_ON_PROJECT_REPOS`，因此**不要在模块里写
  `repositories {}`**。
- `android.useAndroidX=true`（Compose 必需）。Compose 编译器是
  `org.jetbrains.kotlin.plugin.compose` 2.3.21，与 Kotlin 2.3.21 同版本。
- 手写的 `build.ps1`（javac/d8/aapt2 离线管线）**已弃用**，保留仅供查阅：它无法编译
  Kotlin/Compose 与 `res/`，正是本次迁移的原因。

## 安装与验证

```powershell
& C:\code\HyperDuo\install.ps1              # 安装 + 打开设置界面 + 提示重启 SystemUI
& C:\code\HyperDuo\install.ps1 -InstallOnly
& C:\code\HyperDuo\install.ps1 -LogOnly     # 只跟日志
```

装好后需在 LSPosed 管理器里启用模块并勾选 `com.android.systemui`。

打开设置界面（桌面图标亦可）：

```powershell
adb shell am start -n com.hyperduo.trio/.ui.MainActivity
```

观测日志：

```powershell
adb logcat -v time -s LSPosedFramework:* | Select-String HyperDuo
```

- 日志 tag 是 **`LSPosedFramework`**（框架代打的），**不是** `HyperDuo`；行形如
  `(com.android.systemui)[com.hyperduo.trio,HyperDuo,<id>,0,1] <msg>`。
- 启动时会打印一行 `HyperDuo installed, hooks=8`；固件改了方法名时会打
  `skip <id>: method not found`。
- 需要重新定位某个容器时，把 `TrioHooks.DEBUG_DUMP` 改成 `true` 再构建，即可打印
  容器的屏幕坐标、尺寸与全部子视图（含 slot / 宽高 / alpha / visibility）。

## 设置与实时配置

设置界面（`ui\SettingsScreen.kt`）包含：总开关、Wi-Fi 弧、移动信号点、电量数字、充电闪电
五个开关；环线粗细 / 弧线粗细 / 数字字号 / 轨道透明度四个滑杆；低电量阈值与
低电量·充电·危险三组颜色（每组各含深/浅背景两套，用 `ColorPalette` 选取）；调试日志开关
与「恢复默认」。

**配置通道**：libxposed 的 remote preferences。

- 组名 / 文件名同为 `Prefs.NAME = "hyperduo_settings"`；键与默认值集中在 `Prefs.java`。
- 写侧（设置 App）：`HyperDuoApp.onServiceBind` 拿到 `XposedService`，之后每次改动都
  `getRemotePreferences(NAME).edit().putX(...).commit()` 并把同一份写进本应用的
  `SharedPreferences`（即 `shared_prefs\hyperduo_settings.xml`）。服务是异步绑定、
  也可能随时死亡，所以 `SettingsRepository` 每次写入都实时取 `HyperDuoApp.xposedService`，
  **不缓存 service**；绑定成功时还会 `syncAllToFramework()` 全量重放 18 个键，补齐框架缺席
  期间的改动。
- 读侧（hook 进程）：remote `SharedPreferences` 是**只读**的，但支持
  `registerOnSharedPreferenceChangeListener`，框架会实时投递变更。`TrioConfig` 注册一个
  监听器，每次回调重新读成一份 `TrioSettings` 快照并 `invalidateHosts()` 重绘宿主 ⇒
  **改设置不需要重启 SystemUI，也不需要热重载**。
- 用 `commit()` 而非 `apply()`：值必须在对下一帧绘制可见。

**Compose 注意事项**：任何 `OverlayDialog`（本项目的颜色选择弹窗）都必须写在
`Scaffold` 的 content lambda **内部**。Miuix 的 `MiuixPopupHost` 从
`LocalRootDialogStates` / `LocalDialogStates` 读取待渲染的弹窗列表，而这两个
CompositionLocal 只由 `Scaffold` 提供；写在外部会注册到一个没人渲染的孤儿列表，
弹窗**静默不出现且不报错**。

## 设计要点

**注入点**：`MiuiBatteryMeterIconView.onDraw(Canvas)` 之后。该视图本身就被测量为
**28dp × 20dp**（`battery_meter_width` × `status_bar_icon_height`），画布够用，无需改动
视图树。注意固件的**旧式分支不清画布**（`onDraw` L536 在 `super.onDraw` 后直接 `return`），
所以我们在 `proceed()` 之后无条件 `drawColor(0, CLEAR)` 再绘制字形，避免原生 drawable
透出来。

**几何**：120×120 设计空间，常量集中在 `TrioGeometry.java`，数值逐条来自
`status-trio` 的 `status-menubar.svg` 与 `StatusIconGeometry.swift`。电池环圆心
`(59.5, 61.487)`、半径 51.5、描边 8、起始角 148.69°、扫过 242.62°（缺口在**底部**）；
顶部缺口留给数字（进度 0.353–0.647）或闪电（0.385–0.614）。Wi-Fi 半径 31/18.5，
描边 7，等级 0–3；点阵 4 颗半径 5.5，等级 0–4。环线粗细 / 弧线粗细 / 数字字号 /
轨道透明度由 `TrioSettings` 覆盖，未改动时可复现上面这些原始值。

**配色**：角色优先序 `critical`（电量 < 20，红）→ `lowPower` → `charging` → 前景
tint；轨道与未激活点用前景色（默认 22%，可调）透明度；数字与闪电始终用前景 tint。
深/浅底由前景色的 sRGB 亮度判断，三组角色色各有深/浅两套。前景 tint 自身仍取自图标
视图的 `mUseTint / mTintColor / mLightColor / mDarkColor`。

**预览与状态栏共用几何**：`TrioPreviewView`（设置界面里 58dp 的方块）直接调用
`TrioRenderer.drawInto(..., clearWhenDone=false)`，与状态栏走同一份代码与同一个
`TrioSettings` 快照。`clearWhenDone=false` 是必须的 —— UI 预览若清画布会把 Activity
背景擦成透明黑。

**原生图标抑制**：两条通道叠加。

1. `MiuiStatusIconContainer.addIgnoredSlots` 追加 `"wifi"` / `"mobile"` / `"stacked_mobile"`
   —— 让容器在 `onMeasure` 时把它们排除出 `measureViews`。
2. 仅靠 (1) 不够：`MiuiStatusIconContainer.onLayout` 第一趟会把**每个**孩子放在容器局部
   `x=0`，后续趟只重定位「可见且未 blocked 且不在 `ignoredSlots`」的孩子，被忽略的孩子
   永远停在容器左边缘 —— 而状态栏容器因为 `MiuiNotificationStatusContainer.onMeasure` 的
   半屏测量，左边缘正好在**屏幕中线**，于是留下游离的「5G」。所以在 `onLayout` 之后遍历
   孩子，凡 `getSlot()` 命中折叠集合的，就 `layout(0,0,0,0)`（每趟重施，`layout()` 不调度
   新布局故不成环）并 `setVisibility(GONE)`（只做一次，`COLLAPSED` 去重）。

`ModernStatusBarView.isIconVisible()` 只由 binding 与动画标志决定，与 `getVisibility()`
无关，所以 GONE 单独用是无效的 —— 必须配合 `ignoredSlots`，反之亦然。

`system_icons.xml` 被 7 个布局 include（状态栏 / 锁屏 / 控制中心 / 两个 QS 头部 / CC fake），
每个都膨胀出独立实例，所以这条规则挂在 `MiuiStatusIconContainer` **类**上而不是某个实例上，
一次覆盖全部宿主；`insets` 变化时 `MiuiPhoneStatusBarView.updateCutoutLocation` 会用
`setIgnoredSlots(RIGHT_BLOCK_LIST)` 清空列表，故在 `onLayout` 每趟用 `ensureFolded` 补齐
（只在缺项时才调 `addIgnoredSlots`，因为它结尾无条件 `requestLayout()`）。

**只压制「自己的」容器**：`isOwned()` 从绘制宿主沿视图树向上找到它所属的
`MiuiStatusBatteryContainer`（按类名匹配，不走 classloader —— 宿主被 Compose/Factory
包装后 classloader 拿不到 SystemUI 类），只有真正在画字形的容器才隐藏原生图标；电池视图
本身被隐藏的容器（island / 极简模式 / 控制中心折叠态）保留自己的信号图标。

**实时刷新**：等级来自 `transformResId`，但**指示器消失时那个方法不再被调用** ——
MIUI 关掉 Wi-Fi 后不再重绑该视图，`sWifiLevel` 会永久停在最后一次的值，弧线就一直在
屏幕上（这正是「关了 Wi-Fi 弧还在」的成因）。所以 Wi-Fi 的**有无**改由实时视图树采样：
在 `MiuiStatusIconContainer.onLayout` 之后遍历孩子，对 `getSlot()` 为 `"wifi"` 的读
`ModernStatusBarView.isIconVisible()`（读 binding，与 `getVisibility()` 无关），
结果喂给 `TrioState.setWifiPresent()`，只在**边沿**变化时重绘宿主。
注意不能靠「子视图是否存在」判断：Wi-Fi 关闭时 MIUI **不移除** `slot=wifi` 的孩子，
只是停止绑定它，孩子数量恒为 1。移动信号不做此反馈 —— `isIconVisible()` 在已淡出的
移动视图上仍为 true，无法区分开关，所以点阵仍由最后一次 resId 驱动。
采样只认权威状态栏容器（`isStatusBarContainer`：等于捕获到的 `mStatusBarStatusIcons`，
或祖先类名为 `MiuiPhoneStatusBarView`），否则控制中心/QS 头部的容器会污染全局状态。

## Hook 清单

| id | 目标 | 作用 |
| --- | --- | --- |
| `hyperduo-container` | `MiuiPhoneStatusBarView.onFinishInflate` | 捕获权威的状态栏图标容器 |
| `hyperduo-icon-layout` | `MiuiStatusIconContainer.onLayout` | 折叠 slot + 压制原生信号视图（覆盖全部宿主）+ 采样 Wi-Fi 有无 |
| `hyperduo-draw` | `MiuiBatteryMeterIconView.onDraw` | 清画布 + 绘制字形 |
| `hyperduo-detach` | `MiuiBatteryMeterIconView.onDetachedFromWindow` | 注销宿主 |
| `hyperduo-style` | `MiuiBatteryMeterView.onBatteryStyleChanged` | 强制样式 0，并还原 `mStoreRealStyle` |
| `hyperduo-charge-text` | `MiuiBatteryMeterView.updateChargeAndText` | 隐藏原生充电/百分比视图 |
| `hyperduo-cutout` | `MiuiPhoneStatusBarView.updateCutoutLocation` | 重新追加被 `setIgnoredSlots` 清掉的 slot |
| `hyperduo-signal` | `MiuiStatusBarIconViewHelper.transformResId` | 读取 Wi-Fi / 移动信号等级并触发重绘 |

共 8 个 hook。每个 hook 组独立容错：固件重命名某个方法只会让该组打日志跳过，不影响其余。
设置通道不占 hook —— `TrioConfig` 是注册在 remote `SharedPreferences` 上的变更监听器。

## 源码结构

```
com.hyperduo.trio
├─ HyperDuoModule.java   入口（extends XposedModule，按 META-INF/xposed/java_init.list 实例化）
├─ HyperDuoApp.kt        Application：注册 XposedServiceHelper 监听，持有 service
├─ Prefs.java            键名 / 默认值 / 上下界（两端唯一真源）
├─ TrioSettings.java     跨进程配置快照（值对象，刻意不依赖 Xposed API）
├─ TrioConfig.java       hook 侧：绑定 remote preferences 并热更新快照
├─ TrioHooks.java        全部 hook 安装与容器折叠逻辑
├─ TrioState.java        电量 / 信号等级 / 前景色等运行期状态
├─ TrioGeometry.java     120×120 设计空间的几何常量
├─ TrioRenderer.java     纯 Canvas 描边绘制（状态栏与预览共用）
├─ TrioPreviewView.java  设置界面里的预览 View
├─ Refl.java             反射小工具
└─ ui\
   ├─ MainActivity.kt         ComponentActivity + MiuixTheme
   ├─ SettingsScreen.kt       全部设置项 + 预览卡片 + 颜色弹窗
   └─ SettingsRepository.kt   读写本地 prefs 并写穿到 remote preferences
```

## 已知局限

- 仅在 HyperOS 4 / 小米 14（`CP2A.260605.016`，SDK 37，`OS4.0.0.27.XNCCNXM`）上验证过；
  其他固件可能类名或字段名不同，届时会退化为对应 hook 组打日志跳过。
- 折叠集合按类名/slot 字符串硬编码（`wifi` / `mobile` / `stacked_mobile`）。若后续固件
  引入新的移动槽位名，需要在 `TrioHooks.FOLDED_SLOTS` 里补一项；`DEBUG_DUMP = true`
  可以打印出实际 slot 名。
- Wi-Fi 与移动信号等级来自 `MiuiStatusBarIconViewHelper.transformResId` 的原始 resId
  （`getResourceEntryName` 解析尾位数字）；若固件改走别的绑定路径，环内会退化为不显示
  对应弧/点（不会崩溃）。Wi-Fi 的**有无**另有实时采样兜底，不受此限制。
- 移动信号开关无法实时反映（见上「实时刷新」）：`isIconVisible()` 在淡出的移动视图上
  仍为 true，故移动点只在 resId 变化时更新。
- 修改环线粗细 / 数字字号等几何参数会立即生效，但**不会重新测量**原生电池视图的
  28dp×20dp 尺寸；极端值下字形可能被裁切。

## 目录

```
app\build.gradle.kts                   Gradle 模块配置（namespace com.hyperduo.trio）
app\src\main\java\com\hyperduo\trio\   hook 源码（Java，8 个类）
app\src\main\java\com\hyperduo\trio\ui\ 设置界面（Kotlin + Compose + Miuix）
app\src\main\res\                      strings / themes / 图标
app\src\main\resources\META-INF\xposed\  java_init.list / scope.list / module.prop
build.gradle.kts / settings.gradle.kts / gradle.properties   构建配置
install.ps1                            安装 + 打开设置 + 日志
build.ps1                              旧离线构建（已弃用，保留查阅）
work\jadx-out\                         MiuiSystemUI 反编译源（分析用）
work\unpacked\                         MiuiSystemUI 解包资源（分析用）
work\geocheck\                         离线段渲染与解析校验
.tools\                                JDK / Gradle / SDK / 本地 Maven 仓库
.ref\                                  status-trio 与 HyperCopy 等参考资料
```
