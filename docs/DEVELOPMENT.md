# HyperDuo 开发文档

面向二次开发与问题定位。用户向的安装、设置说明见 [README](../README.md)。

## 项目构成

| 项 | 值 |
| --- | --- |
| `namespace` / `applicationId` | `com.hyperduo.trio` |
| `versionCode` / `versionName` | 源码里 2 / 1.1；发布时由 `release.ps1 -Version` 注入（见「发布流水线」） |
| `minSdk` / `targetSdk` / `compileSdk` | 29 / 36 / 37 |
| Java | 17 |
| 框架接口 | libxposed **API 102**（`minApiVersion=102`、`targetApiVersion=102`、`staticScope=true`） |
| 作用域 | `com.android.systemui`（`app/src/main/resources/META-INF/xposed/scope.list`） |
| 入口 | `com.hyperduo.trio.HyperDuoModule`（`java_init.list`） |

应用侧有两个入口：`.ui.MainActivity` 带 `de.robv.android.xposed.category.MODULE_SETTINGS`
分类（LSPosed 管理器里点击模块进入），`.ui.MainActivityAlias` 是桌面 LAUNCHER 图标。

依赖：`compileOnly io.github.libxposed:api:102.0.0`、`implementation io.github.libxposed:service:102.0.0`、
`androidx.core:core-ktx:1.19.0`、`androidx.activity:activity-compose:1.13.0`、
`org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0`（更新器要在主线程外做网络、
回到主线程报状态，Compose 本身没有调度原语）、Miuix 0.9.3
（`miuix-ui-android` / `miuix-preference-android` / `miuix-icons-android`）。

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
   ├─ SettingsScreen.kt       全部设置项 + 预览卡片 + 颜色弹窗 + 更新卡片
   ├─ SettingsRepository.kt   读写本地 prefs 并写穿到 remote preferences
   └─ UpdateController.kt     检查 / 下载 / 调起安装器（GitHub Releases）
```

## 构建

需要 JDK 17、Android SDK（platform 37 + build-tools 37.0.0）与 Gradle 9.8。本仓库把整套工具链
与依赖都放在 `.tools\`，并且依赖已经本地化为可离线仓库，构建**无需联网**：

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
  的本地副本），排在 `google()` / `mavenCentral()` 之前，所以 `--offline` 也能解析。
  `dependencyResolutionManagement` 用的是 `FAIL_ON_PROJECT_REPOS`，因此**不要在模块里写
  `repositories {}`**。
- `android.useAndroidX=true`（Compose 必需）。Compose 编译器是
  `org.jetbrains.kotlin.plugin.compose` 2.3.21，与 Kotlin 2.3.21 同版本。
- `gradle.properties` 中 `android.suppressUnsupportedCompileSdk=37`、
  `org.gradle.configuration-cache=false`、`org.gradle.jvmargs=-Xmx3072m -Dfile.encoding=UTF-8`。
- 手写的 `build.ps1`（javac/d8/aapt2 离线管线）**已弃用**，保留仅供查阅：它无法编译
  Kotlin/Compose 与 `res/`，这正是迁移到 Gradle 的原因。
- 本仓库**没有 Gradle wrapper**（工具链是 vendored 的），本地用 `.tools\gradle\gradle-9.8.0`。

### 发布构建

```powershell
& "$T\gradle\gradle-9.8.0\bin\gradle.bat" --project-dir C:\code\HyperDuo :app:assembleRelease `
  -PhyperduoVersionName=1.2 -PhyperduoVersionCode=10200 --no-daemon --console=plain
```

`app\build.gradle.kts` 顶部读取 `hyperduoVersionName` / `hyperduoVersionCode` 两个 project
property，缺省时回落到 `1.1` / `2`。**发布构建不能加 `--offline`**：R8 需要一个未 vendored 的
`org.jetbrains.kotlin:compose-group-mapping`，离线会以
`Execution failed for task ':app:produceReleaseComposeMapping'` 失败。

release 开启 `isMinifyEnabled` + `isShrinkResources`，但 `proguard-rules.pro` 里
`-keep class com.hyperduo.trio.** { *; }` 保证了 hook 侧与更新器都不被剥离 —— 这是必须的，
因为 hook 类由框架按 `java_init.list` 反射实例化，R8 看不到这条引用。

### 签名

`signingConfigs.create("hyperduo")` 使用 `rootProject.file(".tools/debug.keystore")`，
`storePassword` / `keyPassword` 均为 `android`，`keyAlias` 为 `hyperduo`。debug 与 release
共用同一个签名，目的是让就地升级覆盖已安装的模块时能继续保持模块授权。

`.tools/debug.keystore` **是入库的**（`.gitignore` 显式放行），因此发布不需要任何 secret 就能
产出与本地 debug 包、与应用内更新互相覆盖的 APK。

### 发布流水线

发布是**一条本地命令**，没有 CI：

```powershell
.\release.ps1 -Version 1.0 -NotesFile .\work\notes.md
.\release.ps1 -Version 1.0 -DryRun      # 只构建，不打 tag、不建 Release
```

`release.ps1` 按顺序做四件事，任何一步失败都立刻停下：

1. 校验版本号（`major` / `major.minor` / `major.minor.patch`，缺段按 0 计），算出
   `versionCode = major*10000 + minor*100 + patch`。
2. 不带动 `--offline` 地跑 `:app:assembleRelease`，把版本号用 `-PhyperduoVersionName`
   / `-PhyperduoVersionCode` 注入。
3. 复制成 `dist\HyperDuo-<version>.apk`，再用 `aapt2 dump badging` **回读 APK 里的版本**，
   与预期不符就中止 —— 版本注入写错时这一步会立刻暴露，而不是等用户装上才发现。
4. 打并推送 `v<version>` tag，创建 GitHub Release（`git credential fill` 取凭据，不存明文
   token），最后上传 APK asset。

`.tools\debug.keystore` **是入库的**（`.gitignore` 显式放行），所以发布不需要任何 secret，
产出的 APK 与本地 debug 包、与应用内更新互相覆盖。

**文件名是契约的一部分**：更新器取 Release 的第一个 `.apk` asset，这个名字就是设备上的下载
文件名。

**为什么一个版本必须带 APK**：应用内更新器读的是 `/releases/latest`。如果一个 Release 存在却
没有 APK asset，用户会看到一个装不上的「新版本」。所以宁可失败也不要发空 Release。

**为什么不用 GitHub Actions**：最初的 `release.yml` 在 `android-actions/setup-android@v3` 这
一步就失败了（`platforms;android-37.0` 是 `compileSdk 37` 这种很新的版本化平台，托管 runner 上
的 setup-android 拿不到），后续步骤全部跳过。修这个要有权限调试别人的 action，而本地产出 APK
只需要几十秒且工具链已经 vendored，所以改成 `release.ps1`。发布脚本与本地构建共用同一套命令，
不会出现「CI 能过、本地过不了」的分叉。

### 安装与观测

```powershell
& C:\code\HyperDuo\install.ps1              # 安装 + 打开设置界面 + 提示重启 SystemUI
& C:\code\HyperDuo\install.ps1 -InstallOnly
& C:\code\HyperDuo\install.ps1 -LogOnly     # 只跟日志
```

脚本内硬编码了 `$Root='C:\code\HyperDuo'`、`$Adb='C:\Program Files\UotanToolbox\Bin\platform-tools\adb.exe'`、
`$Package='com.hyperduo.trio'`；日志用 `adb logcat -v time -s LSPosedFramework:* AndroidRuntime:E *:S`。

手动启动设置界面：

```powershell
adb shell am start -n com.hyperduo.trio/.ui.MainActivity
```

日志 tag 是 **`LSPosedFramework`**（框架代打的），**不是** `HyperDuo`；行形如
`(com.android.systemui)[com.hyperduo.trio,HyperDuo,<id>,0,1] <msg>`。启动时会打印一行
`HyperDuo installed, hooks=9`；固件改了方法名时会打 `skip <id>: method not found`。

需要重新定位某个容器时，把 `TrioHooks.DEBUG_DUMP` 改成 `true` 再构建，即可打印容器的屏幕
坐标、尺寸与全部子视图（含 slot / 宽高 / alpha / visibility）。

## 配置通道

设置走 libxposed 的 remote preferences，组名 / 文件名同为 `Prefs.NAME = "hyperduo_settings"`；
键与默认值集中在 `Prefs.java`，上下界也在那里（共 21 个键）。

- **写侧**（设置 App）：`HyperDuoApp.onServiceBind` 拿到 `XposedService`，之后每次改动都
  `getRemotePreferences(NAME).edit().putX(...).commit()`，并把同一份写进本应用的
  `SharedPreferences`（即 `shared_prefs\hyperduo_settings.xml`）。服务是异步绑定、也可能随时
  死亡，所以 `SettingsRepository` 每次写入都实时取 `HyperDuoApp.xposedService`，**不缓存
  service**；绑定成功时还会 `syncAllToFramework()` 全量重放 21 个键，补齐框架缺席期间的改动。
  **每新增一个键都必须同时加到 `syncAllToFramework()`**，否则该键在框架重连后不会下发。
- **读侧**（hook 进程）：remote `SharedPreferences` 是**只读**的，但支持
  `registerOnSharedPreferenceChangeListener`，框架会实时投递变更。`TrioConfig` 注册一个监听器，
  每次回调重新读成一份 `TrioSettings` 快照并 `invalidateHosts()` 重绘宿主 ⇒ **改设置不需要
  重启 SystemUI，也不需要热重载**。
- 用 `commit()` 而非 `apply()`：值必须在对下一帧绘制可见。

### Compose 注意事项

任何 `OverlayDialog`（本项目的颜色选择弹窗）都必须写在 `Scaffold` 的 content lambda **内部**。
Miuix 的 `MiuixPopupHost` 从 `LocalRootDialogStates` / `LocalDialogStates` 读取待渲染的弹窗列表，
而这两个 CompositionLocal 只由 `Scaffold` 提供；写在外部会注册到一个没人渲染的孤儿列表，
弹窗**静默不出现且不报错**。

## 实现要点

### 注入点

`MiuiBatteryMeterIconView.onDraw(Canvas)` 之后。该视图本身就被测量为 **28dp × 20dp**
（`battery_meter_width` × `status_bar_icon_height`），画布够用，无需改动视图树。注意固件的
**旧式分支不清画布**（`onDraw` L536 在 `super.onDraw` 后直接 `return`），所以我们在
`proceed()` 之后无条件 `drawColor(0, CLEAR)` 再绘制字形，避免原生 drawable 透出来。

### 几何

120×120 设计空间，常量集中在 `TrioGeometry.java`。电池环圆心 `(59.5, 61.487)`、半径 51.5、
描边 8、起始角 148.69°、扫过 242.62°（缺口在**底部**）；顶部缺口留给数字（进度 0.325–0.675）
或闪电（0.345–0.655，比数字缺口**更窄**）。Wi-Fi 半径 31 / 18.5，描边 7，等级 0–3；点阵 4 颗
半径 5.5，等级 0–4。环线粗细 / 弧线粗细 / 数字字号 / 轨道透明度由 `TrioSettings` 覆盖，未改动
时可复现上面这些原始值。

缺口是**有内容才开**的：`gapStart == gapEnd == 0`（`GAP_NONE`）时 `batteryRing` 的两段会合并成
一整圈。判断顶部缺口是否被占用，以及谁占圆心，都在 `TrioRenderer.drawInto` 的决策块里
（`TrioRenderer.java:118` 起）；圆心与缺口的字号、基线、清空宽度分别由
`TrioGeometry.centreSize/centerBaseline/centreClearWidth` 与 `gapBaseline/gapClearWidth` 给出。

### Wi-Fi 与数字换位

`swap_wifi_value` 打开后，`drawInto` 的两个「槽位」互换：

| | 关（默认） | 开 |
| --- | --- | --- |
| 圆心 | Wi-Fi 弧 | 电量数字（放大 `CENTRE_SIZE_RATIO` 倍）/ 网络类型 / 充电闪电 |
| 顶部缺口 | 电量数字 / 闪电 | Wi-Fi 弧（缩小到 `GAP_WIFI_SCALE`） |

Wi-Fi 搬进缺口是靠 canvas 变换（`translate` + `scale`）完成的，几何常量仍是原始那套绝对值；
闪电居中同理（`boltCentreOffsetX/Y` + `BOLT_CENTRE_SCALE`）。两者都在 `save()`/`restoreToCount()`
里做变换，和 `drawBolt` 的既有写法一致。

### 配色

角色优先序在 `TrioRenderer.roleColor(TrioSettings cfg, int level, boolean charging, boolean powerSave, boolean low, int fg)`
（`TrioRenderer.java:169`）：

1. `critical` —— `level >= 0 && level < TrioGeometry.CRITICAL_LEVEL`（`CRITICAL_LEVEL = 20`，
   `TrioGeometry.java:208`），`TrioRenderer.java:175`；
2. `lowPower` —— `powerSave || low || (level >= 0 && level <= cfg.lowThreshold)`，`TrioRenderer.java:178`；
3. `charging` —— `TrioRenderer.java:181-182`；
4. 否则回落到前景 tint。

轨道与未激活点用前景色（默认 22%，可调）透明度；数字与闪电始终用前景 tint。深/浅底由前景色的
sRGB 亮度判断，三组角色色各有深/浅两套。前景 tint 自身仍取自图标视图的
`mUseTint / mTintColor / mLightColor / mDarkColor`。

快充由 `TrioState` 反射读 `mQuickCharging`（与 `mCharging`）得到，**只改闪电颜色**（琥珀色
`TrioGeometry.QUICK_CHARGE`），不改形状或大小 —— 见 `TrioRenderer.drawBolt(Canvas c, int color)`
（`TrioRenderer.java:357`）的注释：*bolt fill; amber while the battery reports quick charge*。

### 预览与状态栏共用几何

`TrioPreviewView`（设置界面里 58dp 的方块）直接调用
`TrioRenderer.drawInto(..., clearWhenDone=false)`，与状态栏走同一份代码与同一个 `TrioSettings`
快照。`clearWhenDone=false` 是必须的 —— UI 预览若清画布会把 Activity 背景擦成透明黑。

### 原生图标抑制

两条通道叠加。

1. `MiuiStatusIconContainer.addIgnoredSlots` 追加 `"wifi"` / `"mobile"` / `"stacked_mobile"`
   —— 让容器在 `onMeasure` 时把它们排除出 `measureViews`。
2. 仅靠 (1) 不够：`MiuiStatusIconContainer.onLayout` 第一趟会把**每个**孩子放在容器局部 `x=0`，
   后续趟只重定位「可见且未 blocked 且不在 `ignoredSlots`」的孩子，被忽略的孩子永远停在容器
   左边缘 —— 而状态栏容器因为 `MiuiNotificationStatusContainer.onMeasure` 的半屏测量，左边缘
   正好在**屏幕中线**，于是留下游离的「5G」。所以在 `onLayout` 之后遍历孩子，凡 `getSlot()`
   命中折叠集合的，就 `layout(0,0,0,0)`（每趟重施，`layout()` 不调度新布局故不成环）并
   `setVisibility(GONE)`（只做一次，`COLLAPSED` 去重）。

`ModernStatusBarView.isIconVisible()` 只由 binding 与动画标志决定，与 `getVisibility()` 无关，
所以 GONE 单独用是无效的 —— 必须配合 `ignoredSlots`，反之亦然。

`system_icons.xml` 被 7 个布局 include（状态栏 / 锁屏 / 控制中心 / 两个 QS 头部 / CC fake），
每个都膨胀出独立实例，所以这条规则挂在 `MiuiStatusIconContainer` **类**上而不是某个实例上，
一次覆盖全部宿主；`insets` 变化时 `MiuiPhoneStatusBarView.updateCutoutLocation` 会用
`setIgnoredSlots(RIGHT_BLOCK_LIST)` 清空列表，故在 `onLayout` 每趟用 `ensureFolded` 补齐
（只在缺项时才调 `addIgnoredSlots`，因为它结尾无条件 `requestLayout()`）。

### 只压制「自己的」容器

`isOwned()` 从绘制宿主沿视图树向上找到它所属的 `MiuiStatusBatteryContainer`（按类名匹配，
不走 classloader —— 宿主被 Compose/Factory 包装后 classloader 拿不到 SystemUI 类），只有真正
在画字形的容器才隐藏原生图标；电池视图本身被隐藏的容器（island / 极简模式 / 控制中心折叠态）
保留自己的信号图标。

### 实时刷新

等级来自 `transformResId`，但**指示器消失时那个方法不再被调用** —— MIUI 关掉 Wi-Fi 后不再重绑
该视图，`sWifiLevel` 会永久停在最后一次的值，弧线就一直在屏幕上（这正是「关了 Wi-Fi 弧还在」
的成因）。所以 Wi-Fi 的**有无**改由实时视图树采样：在 `MiuiStatusIconContainer.onLayout` 之后
遍历孩子，对 `getSlot()` 为 `"wifi"` 的读 `ModernStatusBarView.isIconVisible()`（读 binding，
与 `getVisibility()` 无关），结果喂给 `TrioState.setWifiPresent()`，只在**边沿**变化时重绘宿主。

注意不能靠「子视图是否存在」判断：Wi-Fi 关闭时 MIUI **不移除** `slot=wifi` 的孩子，只是停止
绑定它，孩子数量恒为 1。移动信号不做此反馈 —— `isIconVisible()` 在已淡出的移动视图上仍为 true，
无法区分开关，所以点阵仍由最后一次 resId 驱动。

采样只认权威状态栏容器（`isStatusBarContainer`：等于捕获到的 `mStatusBarStatusIcons`，或祖先
类名为 `MiuiPhoneStatusBarView`），否则控制中心/QS 头部的容器会污染全局状态。

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
| `hyperduo-mobile-type` | `MobileTypeDrawable.measure` | 读 `mMobileType`（网络类型 3G/4G/5G…）并触发重绘 |

共 9 个 hook。每个 hook 组独立容错：固件重命名某个方法只会让该组打日志跳过，不影响其余。
设置通道不占 hook —— `TrioConfig` 是注册在 remote `SharedPreferences` 上的变更监听器。

`hyperduo-mobile-type` 必须在 `chain.proceed()` **之后**再读字段：`measure()` 会把 `"5G++"`
就地改写成 `"5G"` 并另置一个 double-plus 标志（`MobileTypeDrawable.java:69`），提前读会拿到
未规范化的原值。网络类型绝不自行推断 —— `5GA` 是 MIUI 按运营商配置
（`OperatorConfig.support5GADisplay`）决定的，模块只如实显示系统给的字符串。

## 配置项参考

键名、默认值与上下界的唯一真源是 `Prefs.java`（`app/src/main/java/com/hyperduo/trio/Prefs.java`）。

| 键 | 默认 | 范围 |
| --- | --- | --- |
| `enabled` | `true` | — |
| `show_wifi` / `show_mobile` / `show_value` / `show_bolt` | `true` | — |
| `show_mobile_type` | `false` | — |
| `swap_wifi_value` | `false` | — |
| `role_colors` | `true` | — |
| `color_critical_on_dark` / `color_critical_on_light` | `0xFFFF3B30` | — |
| `color_charging_on_dark` / `color_charging_on_light` | `0xFF34C759` / `0xFF1F8F3D` | — |
| `color_low_on_dark` / `color_low_on_light` | `0xFFF2B900` / `0xFFC99700` | — |
| `low_threshold` | `20` | 5 – 50 |
| `ring_stroke` | `14` | 4 – 16 |
| `arc_stroke` | `12` | 3 – 16 |
| `value_size` | `36` | 16 – 44 |
| `value_weight` | `700` | 100 – 900 |
| `type_size` | `32` | 16 – 44 |
| `type_weight` | `700` | 100 – 900 |
| `track_alpha` | `56` | 0 – 255 |
| `debug_log` | `false` | — |

## 已知限制

- 仅在 HyperOS 4 / 小米 14（`CP2A.260605.016`，SDK 37，`OS4.0.0.27.XNCCNXM`）上验证过；其他
  固件可能类名或字段名不同，届时会退化为对应 hook 组打日志跳过。
- 折叠集合按类名/slot 字符串硬编码（`wifi` / `mobile` / `stacked_mobile`）。若后续固件引入新的
  移动槽位名，需要在 `TrioHooks.FOLDED_SLOTS` 里补一项；`DEBUG_DUMP = true` 可以打印出实际
  slot 名。
- Wi-Fi 与移动信号等级来自 `MiuiStatusBarIconViewHelper.transformResId` 的原始 resId
  （`getResourceEntryName` 解析尾位数字）；若固件改走别的绑定路径，环内会退化为不显示对应
  弧/点（不会崩溃）。Wi-Fi 的**有无**另有实时采样兜底，不受此限制。
- 移动信号开关无法实时反映：`isIconVisible()` 在淡出的移动视图上仍为 true，故移动点只在
  resId 变化时更新。
- 修改环线粗细 / 数字字号等几何参数会立即生效，但**不会重新测量**原生电池视图的
  28dp×20dp 尺寸；极端值下字形可能被裁切。

## 目录

```
app\build.gradle.kts                   Gradle 模块配置（namespace com.hyperduo.trio）
app\src\main\java\com\hyperduo\trio\   hook 源码（Java）
app\src\main\java\com\hyperduo\trio\ui\ 设置界面（Kotlin + Compose + Miuix）
app\src\main\res\                      strings / themes / 图标
app\src\main\res\xml\file_paths.xml    FileProvider 路径（更新器下载目录）
app\src\main\resources\META-INF\xposed\  java_init.list / scope.list / module.prop
release.ps1                            构建 release APK 并上传到 GitHub Release
docs\                                  开发文档与 README 配图
build.gradle.kts / settings.gradle.kts / gradle.properties   构建配置
install.ps1                            安装 + 打开设置 + 日志
build.ps1                              旧离线构建（已弃用，保留查阅）
work\jadx-out\                         MiuiSystemUI 反编译源（分析用，不入库）
work\unpacked\                         MiuiSystemUI 解包资源（分析用，不入库）
work\geocheck\                         离线段渲染与解析校验（不入库）
work\preview\                          离线 JVM 预览工装（不入库）
.tools\                                JDK / Gradle / SDK / 本地 Maven 仓库
.ref\                                  参考模块与 libxposed 源码（分析用，不入库）
```

## 测试

仓库**目前没有任何自动化测试**（`app\src` 下只有 `main`，没有 `test` / `androidTest` source set）。
回归验证靠两条手工通道：

1. **离线 JVM 预览**（`work\preview\`，不入库）：一套只服务工装的 `android.*` 桌面垫片 + 入口
   `PreviewMain`，直接编译 `Prefs.java` / `TrioSettings.java` / `TrioGeometry.java` /
   `TrioRenderer.java` 出图，不需要设备就能看几何。工装内嵌的 `SharedPreferences` 替身**必须叫
   `FakePrefs`** —— 叫 `Prefs` 会遮蔽真正的 `com.hyperduo.trio.Prefs`，导致常量解析失败。
   垫片出图与真机不符时，**先怀疑垫片**（历史上 `Canvas.restoreToCount` 的语义错实现过一次，
   症状是画布变换泄漏到后续所有绘制）。
2. **上机验证**：`install.ps1` 装机后重启 SystemUI，看日志与状态栏实拍。

## 验证记录

- 几何离线校验（渲染）：`work\geocheck\geocheck.png` 通过。
- 信号名解析校验：112 个资源名逐条判定正确。
- 上机（小米 14 / HyperOS 4）：9 个 hook 全部安装（日志 `HyperDuo installed, hooks=9`），无
  `AndroidRuntime:E`；状态栏 / 锁屏 / 控制中心的原生 Wi-Fi、移动（含 `stacked_mobile`）、电池
  图标均被抑制；屏幕中央不再残留游离的「5G」；Wi-Fi 关闭时不画弧；深色背景下前景色取样正确；
  切换设置不重启 SystemUI 即生效。
- 换位开关（`swap_wifi_value`）上机双向验证：关机位为「小闪电在顶部缺口 + 数字在圆心」，开机位
  为「大闪电居中 + 数字缩到顶部缺口」，开 Wi-Fi 时 Wi-Fi 弧缩进缺口；切换即时生效，无需重启。
- 更新器上机验证：无 Release 时点「检查更新」显示「作者尚未发布任何正式版本」（404 视为正常
  空答案而非失败），界面不卡死、不误报。
- release 构建注入验证：`-PhyperduoVersionName=1.2 -PhyperduoVersionCode=10200` 产出的 APK
  经 `aapt2 dump badging` 确认 `versionCode='10200' versionName='1.2'`；解包后 `dexdump` 确认
  hook 侧与更新器全部类均未被 R8 剥离。
