# HyperDuo 开发文档

面向二次开发与问题定位。用户向的安装、设置说明见 [README](../README.md)。

## 项目构成

| 项 | 值 |
| --- | --- |
| `namespace` / `applicationId` | `com.hyperduo.trio` |
| `versionCode` / `versionName` | 源码里 2 / 1.1；发布时由 `release.ps1 -Version` 注入（见「发布流水线」） |
| `minSdk` / `targetSdk` / `compileSdk` | 29 / 36 / 37 |
| Java / Kotlin JVM target | 21（不是选择，见「构建」） |
| 框架接口 | libxposed **API 102**（`minApiVersion=102`、`targetApiVersion=102`、`staticScope=true`） |
| 作用域 | `com.android.systemui`（`app/src/main/resources/META-INF/xposed/scope.list`） |
| 入口 | `com.hyperduo.trio.HyperDuoModule`（`java_init.list`） |

应用侧有两个入口：`.ui.MainActivity` 带 `de.robv.android.xposed.category.MODULE_SETTINGS`
分类（LSPosed 管理器里点击模块进入），`.ui.MainActivityAlias` 是桌面 LAUNCHER 图标。

依赖：`compileOnly io.github.libxposed:api:102.0.0`、`implementation io.github.libxposed:service:102.0.0`、
`androidx.core:core-ktx:1.19.0`、`androidx.activity:activity-compose:1.13.0`、
`org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0`（更新器要在主线程外做网络、
回到主线程报状态，Compose 本身没有调度原语）、`kotlinx-serialization-json:1.11.0`（miuix-nav 的
路由要 `@Serializable`）、Miuix **0.9.4**（`miuix-ui-android` / `miuix-preference-android` /
`miuix-icons-android` / `miuix-blur-android` / `miuix-shader-android` / `miuix-nav-android`）。
`miuix-blur` 声明 minSdk 33，本模块用 `<uses-sdk tools:overrideLibrary>` 把它压回 29 ——
它内部每个效果都由 `isRuntimeShaderSupported()` 门控，33 以下是退化成普通绘制而不是崩溃。

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

需要 JDK 21、Android SDK（platform 37 + build-tools 37.0.0）与 Gradle 9.8。本仓库把整套工具链
与依赖都放在 `.tools\`：

```powershell
$T='C:\code\HyperDuo\.tools'
$env:JAVA_HOME="$T\jdk\jdk-21.0.12.1+1"
$env:GRADLE_USER_HOME="$T\gradle-home"
$env:ANDROID_HOME="$T\sdk"
& "$T\gradle\gradle-9.8.0\bin\gradle.bat" --project-dir C:\code\HyperDuo :app:assembleDebug --no-daemon --console=plain
```

产物：`app\build\outputs\apk\debug\app-debug.apk`。

**JDK 21 是硬性要求，不是偏好。** Miuix 0.9.4 的全部产物都是 Java 21 字节码（class 文件
major 65），而 `miuix-nav` 的入口 `rememberNavController` / `entry` 是 inline composable：
Kotlin 拒绝把 21 的目标 inline 进 17 的输出，报

```
Cannot inline bytecode built with JVM target 21 into bytecode that is being
built with JVM target 17. Specify proper '-jvm-target' option.
```

而且 AGP 会硬性校验两侧一致（`Inconsistent JVM targets between Java and Kotlin
compile tasks: 17 and 21.`），所以 `compileOptions` 与 `kotlin.compilerOptions.jvmTarget`
必须同时是 21，也就必须有一个真能产出 21 class 的 `javac`——只把 Kotlin 单独升到 21
在 JDK 17 上编不过（`无效的源发行版：21`）。`build.ps1` 的离线管线仍然用
`-source 8 -target 8`，JDK 21 接受这两个值（有 deprecation 警告），只是共用同一个 JDK。

**构建需要联网。** `io.github.libxposed` 已 vendored 到 `.tools\maven`，但 miuix 0.9.4
与 miuix-nav 不在本地 Gradle 缓存里，`--offline` 会解析失败。

要点：

- `settings.gradle.kts` 把 `.tools\maven` 作为**第一个**仓库（承载 `io.github.libxposed:api/service`
  的本地副本），排在 `google()` / `mavenCentral()` 之前，所以 libxposed 部分不依赖网络
  （miuix 部分仍然依赖，见上）。
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

#### 改 `release.ps1` 时的两个坑

**必须保留 UTF-8 BOM**（`release.ps1` 是 UTF-8 **with BOM**）。这个 shell 是 Windows
PowerShell **5.1**，它读无 BOM 的文件时用 ANSI 代码页（本机是 GBK/936）。中文注释被解成乱码后，
某个多字节序列会**吞掉后面的引号**，于是报出一堆语法错误（`Missing expression after ','`、
`The string is missing the terminator`），而那些行本身完全正确。用 `read` 工具或编辑器看到的
内容是对的，所以这类报错极易被误诊。给文件加回 BOM 即可：

```powershell
$p='C:\code\HyperDuo\release.ps1'
$t=[System.IO.File]::ReadAllText($p,[System.Text.Encoding]::UTF8)
[System.IO.File]::WriteAllText($p,$t,(New-Object System.Text.UTF8Encoding($true)))
```

**不要用 `& git … 2>&1`**。`$ErrorActionPreference='Stop'` 会把原生程序写到 stderr 的正常输出
（git 的 `Everything up-to-date`、gradle 的弃用提示）升级成**终止错误**，一个成功的步骤就会中止
整个发布。脚本里的 `Invoke-Native` 临时把 `$ErrorActionPreference` 放宽到 `Continue`，只用 exit
code 判成败 —— 调外部程序一律走它。

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

这条约束对重启确认弹窗同样成立：它用的就是 `OverlayDialog`，所以也必须留在 `Scaffold` 的 content
lambda 内。`window.*` 下的组件（`WindowDialog`、`WindowBottomSheet` 等）**不受这条约束**：它们自己开一个
真正的 `androidx.compose.ui.window.Dialog`，与 `Scaffold` 的弹窗宿主无关，因此不依赖任何父级。本项目
没有用它们：多引入一种弹窗形态不如复用颜色选择弹窗已有的那种。

### 重启系统界面

顶栏右侧的「重启系统界面」按钮需要 root。它执行的是 `su -c "killall com.android.systemui"`，与
README 里给的手工命令一致——用 `su -c` 而不是往裸 `su` 里喂命令，是为了让退出码属于真正要执行的那条
命令：授权被拒绝与 `killall` 本身失败都表现为非 0，不必从输出里去猜。stdout 与 stderr 合并后**先读完
再 `waitFor`**，否则 shell 写满无人读的管道会一直阻塞，`waitFor` 永不返回。

状态机在 `ui/RestartController.kt`（`Idle` / `Running` / `Done` / `Failed`）。它和 `UpdateController`
一样被提到 screen 级持有：确认弹窗被关掉后 kill 可能仍在进行，root 授权弹窗也不一定随着弹窗消失。

确认弹窗复用颜色选择弹窗那套 `OverlayDialog`，因此和它一样必须写在 `Scaffold` 的 content lambda 内
（见上一节）。kill 进行中把 `enabled` 置为 `false` 并让 `onDismissRequest` 直接返回，此时取消/确认按钮
都不可点、点击外部与返回键也都不关闭——用户可能正对着 root 授权弹窗，此时关掉确认弹窗会让结果无处可报。
失败文案沿用更新卡片「本地化标题 · 原始诊断文本」的拼接方式，诊断文本不翻译。**这条通路不需要新增
`Prefs` 键**，也不经过配置通道。

### 颜色恢复默认的确认

颜色页的「恢复默认」同样先弹确认。它和重启确认共用同一套 `OverlayDialog` 外壳（`ResetColorsDialog`），
所以同样必须在 `Scaffold` 的 content lambda 内。理由是这条操作会一次性丢掉六种自定义颜色（三种状态 ×
深/浅背景）且没有撤销，误触的代价与重启同一个量级。

弹窗打开期间只翻转一个 `resettingColors` 布尔值，**不在打开时就重置**：确认与取消走的是同一条 `update`
漏斗，写操作只发生在确认回调里。该行此前没有 `enabled` 守卫，顺带补上 `gated && settings.roleColors`——
在状态颜色总开关关闭时它重置的是一组不生效的值，和上面几行保持一致的灰显更合理。

### 顶栏模糊

顶栏用 `miuix-blur` 的 `Modifier.textureBlur` 采样页面内容做磨砂玻璃，而不是画一层不透明底色。
两个修饰符缺一不可：页面 `LazyColumn` 挂 `Modifier.layerBackdrop(backdrop)` 把自己录进一个
graphics layer，顶栏再通过同一个 `backdrop` 采样它——捕获源与采样面是 `Scaffold` 里两个不同的兄弟槽位，
库内部靠 `layerCoordinates.localPositionOf()` 对齐全局坐标，不需要嵌套。

- **必须自己门控，不能只依赖库**：库在 `isRuntimeShaderSupported()` 为假时会跳过特效本身
  （`DrawBackdropNode.draw()` 首行 `if (!enabled) { drawContent(); return }`），但这对本项目不够——
  模糊顶替的是顶栏自己的背景，而 `TopAppBar` 把传入的 `modifier` 应用在自身 `background(color)`
  **之前**，所以未门控的老设备上会得到一个完全没有背景的顶栏，列表会直接从标题上滚过去。
  因此调用点用 `if (barBlurSupported)` 同时切换 `modifier` 与 `color`（支持时 `Color.Transparent`，
  否则 `surfaceColor`）。
- **捕获层必须先铺不透明底**：页面留白与卡片间隙是透明的，直接模糊会把邻格颜色横着拖开一道。
  `rememberLayerBackdrop { drawRect(surfaceColor); drawContent() }` 先铺一层 `surface`。
- `rememberLayerBackdrop` 的 `onDraw` 经 `rememberUpdatedState` 读取，所以每帧新建的 lambda 不会重建
  backdrop、不会重置坐标；但 `remember` 本身必须**无条件**调用，条件化会破坏 slot table。
- `BarBlurRadius = 40f`，远高于库默认的 20dp：顶栏有状态栏 + 大标题那么高，半径太小时底下文字仍可辨，
  观感只是半透明蒙层而非磨砂。
- 依赖侧：`miuix-blur-android` 的 aar 自带 `minSdkVersion="33"`，而本项目 `minSdk = 29`；靠
  `app/src/main/AndroidManifest.xml` 里的 `<uses-sdk tools:overrideLibrary="top.yukonga.miuix.kmp.blur" />`
  保住 29（已验证 merge 后仍是 `minSdkVersion="29"`）。`miuix-shader-android` 虽由 blur 传递引入，
  但代码直接 import 了它的 `isRuntimeShaderSupported()`，所以也显式声明。

### 提示文案：Snackbar 与 Tooltip

- 反馈改用 Miuix `SnackbarHost`（`Scaffold(snackbarHost = ...)`）。用它是因为它与页面同一个
  surface、跟随主题、且随页面销毁而消失。时长选 `SnackbarDuration.Short`（4000ms）而不是
  `Long`（10000ms）——两个数字取自反编译的 `SnackbarKt.toMillis`，不是猜的：这几条消息都是一句
  话，十秒太长，而提示条本身还可以划走。所有弹出都收敛到一个 `showMessage: (String) -> Unit`，
  并用 `remember(scope, snackbarHostState)` 包住，避免每个接收它的行都被不稳定 lambda 拖着重绘。
  （1.0 的两条反馈文案是常驻行内文字，不是 Toast；git 历史里从未出现过 `android.widget.Toast`。）
- 被复合门控灰显的行（例如需要先开总开关再开子开关）加 `TooltipBox` 长按提示，文案来自
  单条格式串 `R.string.gate_hint`（`需要先打开「%1$s」` / `Turn on "%1$s" first`），
  由 `gateHint(vararg gates: Pair<Boolean, Int>)` 取第一个未满足的开关名。提示通过
  `enabled = hint != null` 关闭——`Tooltip.kt` 里 `tooltipGestures` 在 `enabled = false` 时退化成
  普通 Modifier，所以灰显行只有在确实存在未打开的前置开关时才响应长按。
- **仅被总开关拦下的行刻意不加提示**：总开关就在同一屏上，提示是噪音。

### 导航：miuix-nav

设置页的四个分区由 `miuix-nav` 的 `NavDisplay` 承载，四个标签就是栈上的四个目的地。

- 路由是 `@Serializable sealed interface Route : NavKey` 下的四个 **data object**。必须可序列化
  是因为 nav 把 back stack 放在 `rememberSaveable` 里、用 serializer 重建；必须是 object 是因为
  nav 用路由自己的 `toString()` 给每个条目的 saved state 做命名空间，object 的 `toString()`
  是类名（跨进程稳定），而普通类的默认实现会打印 identity hash（进程重启后复位）。
- `rememberNavController<Route>(Route.General)` 的**超类型必须显式写出来**：reified 参数否则会
  从实参推断成 `Route.General`，之后 push 其它三个子类型时保存/恢复会序列化失败。
- 切分区走 `selectTab`：落在已经在栈上的路由就 `popUntil` 回退过去，不在栈上才 `push`。
  这样栈是一条路径而不是点击流水账，也顺手满足 nav 文档要求的 push 幂等（重复 key 会被拒绝，
  而标签条允许点得比转场更快）。
- **页面级状态必须传对象而不是值。** `NavDisplay` 内部是
  `val provider = remember(content) { entryProvider(content) }`——DSL lambda 被记忆，provider
  只在 lambda 实例变化时重建。所以 entry 里若捕获 `settings` 的**值**，之后设置变化时它会一直
  渲染那个快照。因此 `SettingsScreen` 保留 `settingsState`/`serviceState` 两个 `MutableState`
  对象并把它俩传给 entry，让每次读取都发生在目的地自己的组合里（真实 snapshot read）。
- **`preview` + 标签条留在每个目的地的 `LazyColumn` 里，没有提到 `NavDisplay` 之上。**
  它是页面的一部分，要跟着内容滚走：顶栏的收起依赖这个滚动，预览卡也正因如此才能在不滚回
  顶部的情况下与设置项对照。提到上层会把它钉在屏幕顶部，两件事同时坏掉。
  四个目的地各带一份（`sectionHeader()`，`SettingsScreen.kt:314-330`），所以它随各自的列表
  滚动。看起来是四份重复，实际不会看出差别：两份是同样的像素、同一个位置，
  转场时互相淡入淡出落在完全相同的内容上。
  位置也验过是等价的——把 header 放进列表只改了它的归属，没改它的坐标：改动前后两次
  `uiautomator dump` 里预览卡与四个标签的 `bounds` 逐字相同。
- 起先放在那条唯一 `LazyColumn` 上的 `.layerBackdrop(barBackdrop)` 与
  `.nestedScroll(scrollBehavior.nestedScrollConnection)` 上移到 `NavDisplay`：模糊要采样整页，
  滚动行为要听到现在发生在目的地内部的滚动。
- **切标签一律回到列表顶部，不恢复上次的滚动位置。** 这里换过两次方向，最终以用户的
  「不要记录页面位置」为准。中间那版曾把「每个目的地各留各的偏移」当成返回语义的正确形态：
  四个分区各有一个 `listState`，走回去就回到原处。问题在于目的地被覆盖时**仍然组合着**
  （见下文交叉淡入的可见窗口），所以「回到原处」不是恢复一个冻结的快照，而是读者刚在标签条上
  选了一个分区、却被丢进它的中段。滚动偏移属于这一次访问，不属于这个分区，因此不跨访问携带。
  实现：`SectionList` 多收一个 `isTop`（`SettingsScreen.kt:572-587`），
  `LaunchedEffect(isTop) { if (isTop) listState.scrollToItem(0) }`；四个 entry 各自传
  `isTop = nav.backStack.lastOrNull() == Route.Xxx`（`SettingsScreen.kt:467/475/483/497`）。**键取 `isTop` 而不是
  `LaunchedEffect(Unit)`**：同一个已组合的列表会被反复覆盖又揭开，重置必须发生在每次「成为栈顶」
  时，而不是当初创建它的那次组合里。
- 对话框（取色、重启、重置确认）**刻意不做成路由**：它们是浮在页面上的提示，推成路由会让
  返回手势的含义从「回上一个分区」变成「取消」。
- **转场不能用库的预设，必须换成一个零位移的交叉淡入。** `NavTransitions.MiuixDefault` 的动作是
  「到达一个新页面」：进入的层从右缘整幅推入（`NavTransitions.kt:43-57` 里 `d <= 0f` 分支的
  `translationX` 最大等于页宽），被它覆盖的层视差左移 1/4 页宽并降到 0.9 alpha，外层再由
  `NavDisplayEffects.dimAmount = 0.5f` 压一层灰。四个标签是同一页的四个视图，横推过去看着像
  把页面撕成两半。`SectionTransition`（`SettingsScreen.kt:195-228`）因此
  只动 alpha：`relativeDepth` 为 0 是停在顶层、-1 是完全退出到上层之上、+1 是被上层盖住，所以
  `alpha = if (d <= 0f) (1f + d) else 1f` 一个式子就同时管往前往后两个方向，被盖住的那层保持
  全不透明直到真的被盖住。
  **挑 alpha 而不是位移动画，也是因为 header 就在被动画的层里**：两份 header 是同一批像素，
  淡入淡出看不出来；一旦改成横推或缩放，屏幕上就会出现两套错开的预览卡与标签条。
- **被覆盖分支也必须显式写 `alpha`。** 这个 block 跑在 `Modifier.graphicsLayer { }` 里，而
  graphicsLayer 是跨帧保留的：某个属性这一帧不再被赋值，它就沿用上一帧的值。只在 `d <= 0f`
  时赋值会让层冻结在它上一次进入动画的 alpha 上，而不是正常显示为不透明。
- 时长取 220 ms 而非预设的 500 ms，`NavDriverSpec.PROGRAMMATIC_DURATION_MILLIS` 是为整页推入调
  的曲线。另外只有**静止起步的整步**才会走 `programmatic` 曲线
  （`runtime\NavDriver.kt` 的 `usesProgrammaticCurve = velocity == 0f && abs(distance) >= 0.999f`），
  所以这个 Tween 实际只作用于点标签；返回手势中途松手带着速度，仍会落到 `commit` 的 spring 上。
- `SectionEffects`（`SettingsScreen.kt:235-238`）关掉 `enableCornerClip`、把 `dimAmount` 设为 0：
  这里从没有层叠在另一层之上，裁角和压暗都没有对象。
- **每个目的地的 `LazyColumn` 必须自己铺底色（`Modifier.background(MiuixTheme.colorScheme.surface)`，
  `SettingsScreen.kt:606`）。** 交叉淡入要求被覆盖的那层在转场期间继续组合、继续绘制——
  visibility window 是 `-1 < d <= opaqueDepth`（`runtime\NavPresentation.kt:104`），
  `opaqueDepth = 1f` 正是为了留住这一层。问题是两个列表尺寸位置完全相同，被覆盖层**只能被上层
  实际画出的像素遮住**：卡片之间有 12dp 间隙、较短的分区结束后还有大片空白，透明列表在这些地方
  什么都盖不住，于是上一个分区的行直接透出来，看起来像旧标签页垫在新标签页底下（用户报的原话是
  「不同标签页会覆盖上一个标签页的内容在底层」）。`Scaffold` 只画一层底色（`containerColor =
  MiuixTheme.colorScheme.surface`，`.tools\tmp-src\miuix-sources\…\basic\Scaffold.kt:88`），
  在只有一个列表的年代够用，现在得由列表自己负责。用同一个 surface 色是刻意的：静止态因此
  与改动前逐像素一致。

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

`batteryRing(Canvas, from, to, gapStart, gapEnd)` 的 `from` / `to` 是**可见弧**上的进度，不是整条
路径的进度：缺口本身不出墨，也就不该占用电量。设缺口为 `[m0, m1]`，可见弧长
`drawn = m0 + (1 - m1)`，那么电量 `f` 到达的可见位置是 `u = f * drawn`，左段画
`[0, min(u, m0)]`、右段画 `[m1, m1 + max(0, u - m0)]`。于是 `drawn = 0.65` 时 50% 恰好铺满左半环
（`u = 0.325 = m0`）、右半环为空，50%–100% 全部落在右半环上。

早期实现把 `level/100` 当作整条路径的进度直接用（`from`/`to` 不乘 `drawn`），缺口宽度没有补偿，
症状是电量在缺口整段宽度内**原地不动**：45% 就已填满左半环，而右半环要等电量爬过 67% 才起弧。
端帽（`STROKE` 是 `Paint.Cap.ROUND`，半宽 `stroke/2`）会让每段末端向外多渗一点墨：`stroke = 14`
时约 `7 / 218.078 ≈ 0.032` 路径进度（7.79°），**校验像素时必须先扣掉这个渗出量**，否则正确渲染
也会被判成越界。

### 电量数字居中

设置里这一项叫「电量数字居中」，存储键却是 `swap_wifi_value` —— **键名冻结，不要改**。它是个布尔，
已装用户可能已经打开过；换新键名会让那部分人的选择在升级后静默丢失（框架不做类型/名称迁移）。
只有**标签**改过名：旧文案「Wi-Fi 与数字换位」描述的是机制，而用户要的是「数字居中」。

打开后，`drawInto` 不再做「两个槽位互换」，而是一套**优先级**：电量数字永远第一顺位占圆心，
其余符号依次让位。

| | 关（默认，行为未变） | 开 |
| --- | --- | --- |
| 圆心 | Wi-Fi 弧；无 Wi-Fi 且未选环内类型时是电量数字 | 电量数字（放大 `CENTRE_SIZE_RATIO` 倍）——只要 `show_value` 且电量已知就必定是它 |
| 顶部缺口 | 电量数字 / 闪电 | Wi-Fi 弧（缩小到 `GAP_WIFI_SCALE`）/ 充电时的小闪电 / 让位出来的环内网络类型 |

三条让位规则都只作用于 `centred == true`：

1. **闪电恒落缺口**。`boltInCentre` / `boltInGap` 这对变量已删除，`drawBolt(Canvas c, int color)`
   只剩一个落点，固定用 `boltOffsetX/Y` + `BOLT_SCALE`（原居中几何 `BOLT_CENTRE_SCALE`、
   `boltCentreOffsetX/Y` 已从 `TrioGeometry` 移除）。闪电占缺口时**整组 Wi-Fi 弧不画**，
   门是 `cfg.showWifi && (!centred || wifiInGap)` —— 不能只把 `wifiInGap` 置假，否则
   `drawWifi(..., false)` 会把弧画回圆心压在数字上。
2. **缺口归闪电时用窄嘴**。`gapUsed` 的门与 `GAP_START/END_CHARGE` 的选择只看 `bolt`，
   不再看闪电落在哪个槽位。
3. **环内网络类型让位**。`valueInCentre` 生效时数字占圆心，类型改由新增的
   `drawGapType(Canvas c, String type, int fg, TrioSettings cfg)` 画进缺口（`gapClearWidth` +
   `gapBaseline`，用 `cfg.typeSize` / `cfg.typeWeight`）；圆心空出来时才回到 `drawCentreType`。

Wi-Fi 搬进缺口仍是 canvas 变换（`translate` + `scale`）完成的，几何常量仍是原始那套绝对值，
在 `save()`/`restoreToCount()` 里做。

`centred == false` 时这套式子化简回改动前的判定（`wifiInGap` 与 `typeInGap` 恒为假），
`work\slotcheck\verify.ps1` 用 12 个非居中状态在新旧两版之间逐像素对照，专门锁住这一点。

代码侧的符号（`Prefs.KEY_VALUE_CENTRED`、`TrioSettings.valueCentred`、`TrioRenderer` 里的 `centred`）
都跟着标签改成了「居中」语义，只有那个字符串字面量保持 `"swap_wifi_value"`。

### 网络类型：环内与环外

`mobile_type_mode` 三档：`0` 关闭、`1` 环内、`2` 环外。**选项顺序即存储值**。

- **环内**（`1`）走渲染器：`TrioRenderer.drawInto` 的决策块先判 `typeInRing =
  cfg.mobileTypeMode == Prefs.MOBILE_TYPE_IN_RING && mobileType != null && !mobileType.isEmpty()`，
  再按圆心是否已被电量数字占用分流：`typeInCentre = typeInRing && !wifi && !valueInCentre`，
  `typeInGap = typeInRing && valueInCentre && !wifi && !bolt`。即**数字优先**，类型退到顶部缺口；
  没有数字可展示时才回到圆心。它**不查 `show_value`** —— 与闪电（`showBolt && showValue`）不同。
- **环外**（`2`）不是 canvas 绘制。宿主画布只有 `battery_meter_width = 28dp` ×
  `status_bar_icon_height = 20dp`，装不下环外的字，所以模块**自己往电池容器里新增一个
  `TextView`**：`TrioHooks.OutTypeLabel`（`TrioHooks.java` 的 `out-of-ring type label` 区块）。
  挂点是 `batteryContainerOf(host)` 返回的 `MiuiStatusBatteryContainer` —— 选它是因为
  `MiuiStatusBatteryContainer.onMeasure/onLayout` 只量/摆自己那几个具名字段、
  **从不遍历 `childAt`**，因此第 4 个子视图不会被量也不会被摆，手工 `layout()` 的位置能保住；
  容器还带 `clipChildren="false"`，越界也画得出来。

**绝不能挂到 `MiuiStatusIconContainer`**：它的 `onLayout` 第一遍把所有 child 的 x 归零，第二遍
又把每个 child 强转 `StatusIconDisplayable`（外来视图直接 `ClassCastException`）。同理也不能用
`WeakHashMap<View, TextView>` 缓存 label —— value 里的 `View.getParent()` 强引用回 key，条目永远
回收不掉；这里改用「子视图 `instanceof OutTypeLabel`」当标记。

挂载/摘除只发生在 posted 路径（`applyConfigChange`、`registerHost`、`invalidateHosts`），因为
`settle()` 是从 `onLayout` 里调的，在那儿 `addView` 会触发
`requestLayout() improperly called during layout`。`settle` 里只调 `refreshOutTypeLabel`，它发现
文本/字号需要变时**只排队一次 posted sync**（`setText`/`setTextSize` 会 re-measure → 调度布局，
同样不能在 `onLayout` 内做），否则只更新颜色与位置。

字号用 `setTextSize(TypedValue.COMPLEX_UNIT_PX, cfg.typeSize * TrioRenderer.inkScale(w, h))`：
**必须显式 `COMPLEX_UNIT_PX`**，单参 `setTextSize(float)` 默认按 SP 解释。`inkScale` 是
`TrioRenderer` 里为环外标签抽出的包级 helper（`TrioRenderer.java:425`），与画布同一套 120×120
设计空间缩放，所以环外的字和环内的字视觉大小一致。宿主尚未测量（`inkScale <= 0`）时跳过挂载，
下一次 posted sync 自愈。文本为空时置 `GONE` 而非移除 —— 网络类型随 modem 来去，每次布局
add/remove 太吵。

`typeSize` / `typeWeight` 两个尺寸项**两种模式共用**（设置页在 `mobileTypeMode == 0` 时置灰）。
`show_mobile_type` 是废弃的旧布尔键，仅用于迁移读取（见配置项参考）。

已知风险：原生 `mobile_type_single` 是 mobile 槽组的子级，而 `foldedSlots()` 不包含
`mobile_type`，所以「关闭显示移动信号点 + 环外」时可能同时看到原生与自建两个标签。环内模式不会
冲突 —— 它一定伴随 mobile 槽折叠。

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
（`TrioRenderer.java:426`）的注释：*bolt fill; amber while the battery reports quick charge*。

### 预览与状态栏共用几何

`TrioPreviewView`（设置界面里 58dp 的方块）直接调用
`TrioRenderer.drawInto(..., clearWhenDone=false)`，与状态栏走同一份代码与同一个 `TrioSettings`
快照。`clearWhenDone=false` 是必须的 —— UI 预览若清画布会把 Activity 背景擦成透明黑。

### 原生图标抑制

两条通道叠加，两者都**按子开关**决定折叠哪些槽位（`TrioHooks.foldedSlots()`：`show_wifi` → `wifi`；
`show_mobile` → `mobile` + `stacked_mobile`；总开关关闭时为空集）。

1. `MiuiStatusIconContainer` 的 `ignoredSlots` 列表 —— 让容器在 `onMeasure` 时把它们排除出
   `measureViews`。`syncSlots()` 让该列表与子开关**双向**对齐：补上缺失的，并**移除**不再需要的。
   移除才是「交还」的关键：MIUI 会为所有未被忽略的槽位重新布局并定位。
2. 仅靠 (1) 不够：`MiuiStatusIconContainer.onLayout` 第一趟会把**每个**孩子放在容器局部 `x=0`，
   后续趟只重定位「可见且未 blocked 且不在 `ignoredSlots`」的孩子，被忽略的孩子永远停在容器
   左边缘 —— 而状态栏容器因为 `MiuiNotificationStatusContainer.onMeasure` 的半屏测量，左边缘
   正好在**屏幕中线**，于是留下游离的「5G」。所以在 `onLayout` 之后遍历孩子，凡 `getSlot()`
   命中折叠集合的，就 `layout(0,0,0,0)`（每趟重施，`layout()` 不调度新布局故不成环）并
   `setVisibility(GONE)`（只做一次，`COLLAPSED` 去重）。

`ModernStatusBarView.isIconVisible()` 只由 binding 与动画标志决定，与 `getVisibility()` 无关，
所以 GONE 单独用是无效的 —— 必须配合 `ignoredSlots`，反之亦然。

**交还**（子开关关闭时）必须把上面两步都撤销：(1) 从 `ignoredSlots` 移除该槽，
(2) 把 `COLLAPSED` 里登记过的孩子恢复 `VISIBLE`，然后 `requestLayout()` 让 MIUI 重排。
`settle()` 与 `restoreNative()` 都只对**自己登记过**（`COLLAPSED`）的孩子恢复可见性：MIUI 自己
隐藏的图标（无 SIM、关 Wi-Fi、飞行模式）从不登记，绝不能被复活。至于折叠期留下的 `0×0` 布局，
不必手工纠正 —— 槽位一旦不在 `ignoredSlots` 里，MIUI 就会重新测量并布局它。

`system_icons.xml` 被 7 个布局 include（状态栏 / 锁屏 / 控制中心 / 两个 QS 头部 / CC fake），
每个都膨胀出独立实例，所以这条规则挂在 `MiuiStatusIconContainer` **类**上而不是某个实例上，
一次覆盖全部宿主；`insets` 变化时 `MiuiPhoneStatusBarView.updateCutoutLocation` 会用
`setIgnoredSlots(RIGHT_BLOCK_LIST)` 清空列表，故在 `onLayout` 每趟用 `ensureFolded` 补齐。
`ensureFolded` 同样是双向的，并且**只在确有差异时才调用 `syncSlots`**：`addIgnoredSlots` 结尾
无条件 `requestLayout()`，每趟盲目追加会造成无限布局循环。

### 充电闪电的交还

闪电不是状态栏槽位，而是 `MiuiBatteryMeterView` 的孩子，所以它走另一条路。

模块把电池样式钉成 0（见 `hyperduo-style`），而 MIUI 的原生闪电**只在 style 1/2 下被测量**：
`onMeasure` 里那句 `measureChildWithMargins(mBatteryChargingView, ...)` 带 style 条件，所以
style 0 时该视图宽高恒为 0 —— 单纯把它设成 `VISIBLE` 也不会显示。`updateChargeAndText` 同样
按 style 决定闪电可见性，`onLayout` 也按 style 决定是否把它排在电池视图之后。

因此交还的做法是**用反射直接写 `mBatteryStyle = BOLT_STYLE(1)`**，而不是调用
`onBatteryStyleChanged(1)`。原因是后者 style-1 分支会 `mBatteryIconView.setVisibility(8)` ——
而 `mBatteryIconView` 正是字形绘制的宿主，走那条路等于用「交还闪电」换掉字形。直接写字段则同时
点亮 `onMeasure`（测量闪电）、`updateChargeAndText`（按电量状态显示闪电）与 `onLayout`（排到
电池视图右侧，即系统默认位置）三条路径，而完全不碰宿主可见性。全固件只有构造函数与
`onBatteryStyleChanged` 会给 `mBatteryStyle` 赋值，所以这次覆写不会被别处悄悄改回去。

谓词是 `enabled && showBolt && showValue`（`TrioRenderer` 画闪电的条件），即**当且仅当字形真的
画出闪电时才压制原生闪电**。实现分两处，都必须用同一规则，否则会把刚交还的闪电又藏回去：
`hyperduo-style`（style 变更时）与 `hyperduo-charge-text`（`updateChargeAndText` 每次重跑时）。
设置变更时由 `applyMeterText()` 补齐，并按 `showValue`/总开关分别处理闪电与百分比容器。

**总开关关闭时必须先把 `mBatteryStyle` 清成 `-1`**（构造函数自己的初值），再透传真实样式。
否则会卡住 MIUI 自己的还原：`onBatteryStyleChanged` 的主体在 `if (mBatteryStyle != i3)` 门内，
而 `handBackBolt` 之前已把字段写成 1；若真实样式恰好也是 1，这道判断为假，主体被跳过，
`:641/:642`（普通图标 VISIBLE、hollow GONE）不执行 —— 可 `handBackBolt` 留下的正是这个组合，
本该是 style 1 的「hollow 开、普通图标关」，于是 hollow 电池轮廓永远回不来。清成 `-1` 是唯一能
让那道门通过的状态。

百分比容器（`mBatteryPercentContainer`）只在 style 3 下被测量和显示，而模块从不请求 style 3，
所以模块开着时它只能是隐藏的。

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
| `hyperduo-icon-layout` | `MiuiStatusIconContainer.onLayout` | 按子开关同步 slot + 压制原生信号视图（覆盖全部宿主）+ 采样 Wi-Fi 有无 |
| `hyperduo-draw` | `MiuiBatteryMeterIconView.onDraw` | 清画布 + 绘制字形 |
| `hyperduo-detach` | `MiuiBatteryMeterIconView.onDetachedFromWindow` | 注销宿主 |
| `hyperduo-style` | `MiuiBatteryMeterView.onBatteryStyleChanged` | 强制样式 0，还原 `mStoreRealStyle`；需要时交还原生闪电 |
| `hyperduo-charge-text` | `MiuiBatteryMeterView.updateChargeAndText` | 按 `show_bolt`/`show_value` 隐藏原生充电/百分比视图 |
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
| `mobile_type_mode` | `0`（关闭） | 0 – 2（关闭 / 环内 / 环外） |
| `show_mobile_type` | `false` | 已废弃，只读用于迁移 |
| `swap_wifi_value` | `false` | 「电量数字居中」的存储键；名字是历史遗留，**冻结不改** |
| `role_colors` | `true` | — |
| `color_critical_on_dark` / `color_critical_on_light` | `0xFFFF3B30` | — |
| `color_charging_on_dark` / `color_charging_on_light` | `0xFF34C759` / `0xFF1F8F3D` | — |
| `color_low_on_dark` / `color_low_on_light` | `0xFFF2B900` / `0xFFC99700` | — |
| `low_threshold` | `20` | 5 – 50 |
| `ring_stroke` | `14` | 4 – 16 |
| `arc_stroke` | `12` | 3 – 16 |
| `value_size` | `36` | 16 – 44 |
| `value_weight` | `700` | 100 – 900 |
| `type_size` | `32` | 16 – 44（只用于环内） |
| `out_type_size` | `32` | 16 – 64（只用于环外；上界高于 `type_size`，见 `Prefs.java` 的说明） |
| `type_weight` | `700` | 100 – 900（环内/环外共用） |
| `track_alpha` | `56` | 0 – 255 |
| `debug_log` | `false` | — |

## 已知限制

- 仅在 HyperOS 4 / 小米 14（`CP2A.260605.016`，SDK 37，`OS4.0.0.27.XNCCNXM`）上验证过；其他
  固件可能类名或字段名不同，届时会退化为对应 hook 组打日志跳过。
- 折叠集合按类名/slot 字符串硬编码（`wifi` / `mobile` / `stacked_mobile`）。若后续固件引入新的
  移动槽位名，需要在 `TrioHooks.MANAGED_SLOTS` 里补一项，并在 `foldedSlots()` 里归属到对应的
  子开关；`DEBUG_DUMP = true` 可以打印出实际 slot 名。
- 子开关的「交还」依赖 `MiuiStatusIconContainer.ignoredSlots` 可读可写（反射）：读不到该字段时
  `syncSlots` 退化为只追加，于是子开关只能压制、不能交还（等同旧行为）。
- 原生充电闪电的交还依赖 `mBatteryStyle` 被直接改写：这在 MIUI 自身不重新派发 style 变更时成立。
  若用户在系统设置里改了电池样式，`hyperduo-style` 会重跑并重新按子开关决定是否交还。
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
work\gapcheck\                         电量环缺口像素校验与前后对照图（不入库）
work\preview\                          离线 JVM 预览工装（不入库）
work\overview\                         全部支持样式的状态总览图（不入库）
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

另有 `work\gapcheck\verify.ps1`（不入库）专门盯**电量环缺口**：它把同一份 `GapProbe` 跑两遍 —— 一遍
编工作树的 `TrioRenderer`，一遍把 `batteryRing` 单独换回旧算法（其余代码不动，免得测到另一个程序）。
`GapProbe` 用反射直接驱动真渲染器并沿环心线回读像素，按「可见弧」区间模型逐档断言 0–100% 的左右末端、
缺口内是否无墨，再走 `drawInto` 的槽位决策做端到端确认。固定版必须 `exit 0`、旧算法必须
`exit 1`（旧算法在 45/55/60/70/75% 上共 42 项不符）——**两边都跑才说明探针真的在测这个 bug**；
只跑通过的那一边等于没有反例。探针同时产出 `work\gapcheck\out\mouth-{old,new}.png` 对照图。

另有 `work\slotcheck\verify.ps1`（不入库）盯**居中模式的槽位优先级**。它把同一份 `SlotProbe` 跑两遍
（工作树一份、`git show 312ae00:` 重建的旧渲染器一份，旧源必须先断言含 `boltCentreOffsetX`、不含
`drawGapType`）。**基线必须是写死的修订号，不能是 `HEAD`**：修复未提交时 `HEAD` 正好是旧版，
提交之后 `HEAD` 就是修复本身，探针会拿自己和自己比、然后「证明」一件它没测过的事——本脚本在
提交后确实这样假绿过一次，是被那句 `boltCentreOffsetX` 断言拦下的，所以那道断言不是多余的。
判定用**无色不变量**：圆心圆盘（`CENTER_CY` 处半径 18 设计单位）内的像素在「有闪电」与「无闪电」
两种渲染下必须逐像素相同——低电量角色色 `0xFFF2B900` 与快充琥珀色 `0xFFFFBA28` 太近，
按颜色计数不可靠，所以只比像素。渲染时置 `cfg.roleColors = false`，否则数字颜色会随充电标志变化而
掩盖结论。另有对照组防空洞：未居中 + Wi-Fi 3 与居中 + Wi-Fi 3 的圆心盘哈希必须不同，否则「有/无闪电
相同」什么都没测。新版必须 `exit 0`、旧版必须 `exit 1`（旧版 6 项不符：闪电落在圆心、缺口无琥珀、
Wi-Fi 0..3 四个哈希各不同、环内类型扰动圆心、缺口以下也被改动），同时要求 12 个非居中状态在新旧
两版之间哈希逐行相等，锁住「本改动只影响居中模式」。探针产出 `work\slotcheck\out\slots-{old,new}.png`
六格对照图，格下说明走 `caption` 逐字换行并对超宽/超高一并 `throw`（同样是「宁可失败也别裁字」）。

另有 `work\overview\run.ps1`（不入库）：把渲染器**实际支持的每一个样式**画成一张状态总览图
`work\overview\out\overview.png`（1788×2248，3 组共 25 格）。它复用 `work\preview` 的桌面垫片，
每格都以 `TrioSettings.defaults()` 为底再叠加该格的单项 tweak，因此代表的始终是出厂外观。用途有二：
一是改渲染器后一眼看全所有样式的回归（新样式没进这张图就等于没被清点），二是给用户/文档出图。
出图脚本会打印被编译的 `TrioRenderer.java` 的 SHA256，便于确认图对应哪份代码。

**当前支持样式的清单**（即总览图的三组，也是渲染器能力的边界）：

- 电池（顶部）10 格：充电中 / 快速充电 / 用电中（数字）/ 低电量 / 低电量模式 / 危险电量 /
  已充满 / 只显示圆环 / 无 Wi-Fi 时数字居中 / 无 Wi-Fi 时充电且数字居中。
- Wi-Fi（中部）7 格：已连接 3 格 / 2 格 / 1 格 / Wi-Fi 开启未关联 / Wi-Fi 关闭或不可用 /
  电量数字居中 / 居中后充电（闪电在缺口）。

注意两个「居中」不是一回事，别混：电池组里的「**无 Wi-Fi 时**数字居中」是**自动**行为
（`wifiInk` 为假时数字自己掉进圆心，没有开关），Wi-Fi 组里的「电量数字居中」才是那个**设置项**
（`swap_wifi_value`，用户手动打开）。
- 移动信号与网络类型 8 格：信号 4 格 / 2 格 / 无信号 / 网络类型环内 4G / 环内 5G / 环内 5GA /
  类型关闭 / 类型环外。**环外类型不由本 Canvas 绘制** —— 它由 `TrioHooks` 另建的
  `OutTypeLabel extends TextView` 画在电池表左侧，所以那一格是手绘标签示意，不是渲染器输出。

**造型参数不入图**：环线／弧线粗细、数字／类型字号、底纹浓度、状态颜色开关这些是外观旋钮，
不是图标状态，因此总览图不再单列「外观与几何」一组。要看这些的边界值就改设置界面的滑块；
它们不构成需要清点的能力面。

参考的 macOS 版总览图里还有两组本模块**没有实现**，不要误画：**蓝牙音频**（4 格）与**音量**（7 格）
在 `app\src\main\java\com\hyperduo\trio` 下搜 `volume|bluetooth|audio|headset|earbud` 无任何匹配。
此外参考图的「已连接电源，未充电」也无对应状态 —— 渲染器只有充电 / 未充电两态，不区分插电未充。

## 验证记录

- 几何离线校验（渲染）：`work\geocheck\geocheck.png` 通过。
- 信号名解析校验：112 个资源名逐条判定正确。
- 上机（小米 14 / HyperOS 4）：9 个 hook 全部安装（日志 `HyperDuo installed, hooks=9`），无
  `AndroidRuntime:E`；状态栏 / 锁屏 / 控制中心的原生 Wi-Fi、移动（含 `stacked_mobile`）、电池
  图标均被抑制；屏幕中央不再残留游离的「5G」；Wi-Fi 关闭时不画弧；深色背景下前景色取样正确；
  切换设置不重启 SystemUI 即生效。
- 居中开关（存储键 `swap_wifi_value`）上机双向验证（小米 14 / houji / Android 17 / HyperOS 4 / KernelSU，
  `versionCode=10300` / `versionName=1.3`，`adb install -r` Success、SystemUI 重启后
  `HyperDuo installed, hooks=9 enabled=true`、无 `FATAL EXCEPTION`）——三态各取一帧，8x 放大目视：
  - `work\ondevice\v13-ring-8x.png`（充电 + Wi-Fi 关闭，真实 100%）：缺口里是**小闪电**，
    圆心是 **100**——即「数字优先级最高」，不是旧版的大闪电占圆心。
  - `work\ondevice\v13-charge-wifi-8x.png`（充电 + **Wi-Fi 已关联** `OpenWrt` / RSSI `-38`）：
    缺口只有小闪电，**两条 Wi-Fi 弧整组不画**（验证 `cfg.showWifi && (!centred || wifiInGap)` 这道门）。
  - `work\ondevice\v13-wifi-8x.png`（`dumpsys battery unplug` + Wi-Fi 已关联）：**弧缩进顶部缺口**、
    数字仍在圆心。
  截图 `v13-charging.png` / `v13-charge-wifi.png` / `v13-wifi.png`，裁图用
  `& "$env:JAVA_HOME\bin\java.exe" -cp work\overview\classes Crop <src> <dst> 1055 20 90 85 8`。
  取完把 Wi-Fi 关回 `off`、`dumpsys battery reset` 已确认（`USB powered: true` / `level: 100` 为真实状态）。
  按字节复核「设备上跑的就是这一份」：`adb pull` 设备 `base.apk` 与
  `app\build\outputs\apk\debug\app-debug.apk` SHA256 逐字相同
  （`8F951FD65CC7B3625AA881FE021C96D3E701A7D64162BFB5829BA953FDA177C8`，33019398 B）。
  **修正记录**：早期实现让「居中 + 充电」时大闪电占圆心、数字缩到缺口，与「数字在居中模式下优先级最高」
  的语义相反；1.2 上机截图正是这个错误形态。`work\slotcheck\verify.ps1` 用同一探针在新旧两版之间对照
  （新版 exit 0 / 旧版 exit 1，6 项不符），并把 12 个非居中状态锁成逐像素不变。
  **探针基线必须写死修订号**：它原先按 `HEAD` 重建旧版，修复提交后 `HEAD` 就是修复本身，
  会自己跟自己比却照样打印 `VERIFIED`——现已固定为 `$BaseRev = '312ae00'`。
- 更新器上机验证：无 Release 时点「检查更新」显示「作者尚未发布任何正式版本」（404 视为正常
  空答案而非失败），界面不卡死、不误报。
- 更新器端到端验证（真机）：把 debug 包故意装成 0.9(900)，走完「检查更新 → 下载更新 → 安装」，
  版本变为 1.0(10000)。设备到 github.com 时快时慢（同一 asset 在 1.5s 与 15s 超时之间摇摆），
  故下载先打 `api.github.com/repos/…/releases/assets/<id>`（配 `Accept: application/octet-stream`，
  实测 10/10 成功），失败再退到 `browser_download_url`，每个 URL 重试 3 次。
- 安装被拒的两条路径分开处理：`InstallResult.NeedsPermission`（缺「安装未知应用」授权）弹出提示
  并跳到该设置页；`InstallResult.NoInstaller`（设备上没有能处理安装 intent 的应用）只弹提示，
  不跳设置页 —— 授权已经给出时把用户送去设置页会让他面对一个无处可改的界面。
- release 构建注入验证：`-PhyperduoVersionName=1.2 -PhyperduoVersionCode=10200` 产出的 APK
  经 `aapt2 dump badging` 确认 `versionCode='10200' versionName='1.2'`；解包后 `dexdump` 确认
  hook 侧与更新器全部类均未被 R8 剥离。
- 重启按钮：`assembleDebug` 通过，`aapt2 dump resources` 确认 `restart_title` / `restart_summary` /
  `restart_confirm` / `restart_running` / `restart_done` / `restart_failed` / `cancel` 七个串在中英
  两个 locale 都已打包（`values` 与 `values-en` 均命中）。上机（小米 14 / HyperOS 4 / Android 17 /
  KernelSU）确认：顶栏右侧 `Refresh` 图标按钮渲染正常、与状态栏图标不冲突；点开后确认弹窗按
  `OverlayDialog` 形态渲染（标题、正文、灰「取消」+ 蓝「重启」双按钮）；`am force-stop` 后冷启动
  不误触，`restarting` 初值正确。root 通路单独验证通过：`su -c 'killall com.android.systemui'`
  退出码 0，SystemUI 随后重新起来。**弹窗落在屏幕底部而非居中**——这是 Miuix 的限制不是本模块的
  bug：`DialogContentLayout` 只在窗口宽 ≥ 840dp 时才用 `Alignment.Center`，手机上恒为
  `BottomCenter`，且 `OverlayDialog` / `WindowDialog` 都没有暴露 alignment 参数。**仍待补**：
  「拒绝授权 → 弹窗留在原位并显示失败」「kill 进行中取消/确认按钮均不可点，点击外部与返回键都不
  关闭弹窗」两条交互路径。
- 颜色恢复默认确认：`assembleDebug` 通过，`aapt2 dump resources` 确认 `color_reset_dialog_summary` /
  `color_reset_confirm` 两个新串在中英两个 locale 都已打包。上机（小米 14 / Android 17 / KernelSU）
  确认颜色页「恢复默认」行渲染正常；**弹窗本身的上机截图未取到**——验证过程中设备 USB 掉线
  （`adb devices` 变空），点击「恢复默认」后的那一步没能截到图。同样的 `OverlayDialog` + 取消/确认
  双按钮形态已在重启弹窗上截到过（见上一条），但这两条确认通路各自仍需一次真机点击确认。
- 顶栏模糊：`assembleDebug` 通过；merge 后的 manifest（`processDebugMainManifest` 与
  `processDebugManifestForPackage` 两处）确认 `<uses-sdk android:minSdkVersion="29"
  android:targetSdkVersion="36" />`，即 `tools:overrideLibrary` 生效、minSdk 未被 blur 的 33 顶上去。
  上机（小米 14 / Android 17 / API 37）静置与滚动两态截图确认：顶栏后是页面内容被磨砂糊开
  （预览卡片图标透出为色块），不再是纯色底；滚动时卡片内容从标题下穿过并保持模糊。
  **未在 API 29–32 设备上验证**——该分支只能确认编译与 manifest，实际分支是否退化成不透明底依赖
  真机或模拟器。
- 门控提示（Tooltip）：语义层用 `uiautomator dump` 验证——总开关关闭时恰好 3 个
  `long-clickable="true"` 节点（充电时显示闪电 / 显示网络类型 / 电量数字居中），打开后为 0，
  与「只有确实存在未打开前置开关的行才响应长按」的预期一致。**提示气泡本身未截到图**：
  取图前设备掉线，长按路径未走通；`gateHint` 的文案拼接由代码路径保证。
- 应用内提示条：`assembleDebug` / `compileDebugKotlin --rerun-tasks` 均通过且无警告。
  **提示条本身未截到图**——它只在更新安装失败或重启完成时弹出，前者需要远端存在新 Release，
  后者会真的重启 SystemUI，都不适合为取图而触发。时长常量由反编译 Miuix 确认
  （`SnackbarDuration.Short` = 4000ms、`Long` = 10000ms，见 `SnackbarKt.toMillis`）。
- miuix-nav 接入：`assembleDebug` 通过（JDK 21 + JVM target 21）。解包 APK 后按 dex 字符串确认
  三件事都进了包：`miuix/kmp/nav/core/NavDisplayKt` 与 `NavController`、`NavBackStackKt`
  （`classes6.dex`），以及本模块的 `Route$General`、`SettingsPage`（`classes4.dex`）。
  已 `adb install -r` 装机成功（小米 14，设备在线）。
  **导航的运行时行为未做视觉验证**——按用户要求本轮不截图、不重启 SystemUI。因此以下各条
  只有编译期与打包期的证据，实际观感待补：分区切换的转场动画、系统/预测返回手势的分区回退、
  顶栏模糊在 `NavDisplay` 改为内容宿主后是否仍正确采样、
  以及标签条高亮与栈顶路由是否始终一致。（转场后来返工成零位移交叉淡入，见下一条；
  「切标签后落在哪里」后来由用户定成一律回到顶部，见第四次返工。）
- 导航转场（第一次上机后返工）：首版直接用了库预设 `NavTransitions.MiuixDefault`，用户截图
  判定「问题太大了」——整页被横推、被覆盖分区残留在左边缘。改为 `SectionTransition`
  原地交叉淡入 + `SectionEffects`（无裁角、无 scrim），`compileDebugKotlin` / `assembleDebug`
  均通过且零警告，已 `adb install -r` 装机。**这次改动同样只有编译期证据**：`adb shell input
  tap`/`keyevent` 与 `adb shell monkey -f` 在本机都被 MIUI 在 OS 层拦下，逐字报错
  `java.lang.SecurityException: Injecting input events requires the caller (or the source of the
  instrumentation, if any) to have the INJECT_EVENTS permission`（`monkey` 另外还要求 COUNT 位置
  参数，缺了报 `** Error: Count not specified`），所以「点一下标签看转场」这一步无法由我完成，
  最终观感由用户目视确认。
- 导航布局（第二次返工）：用户指出「做简单的导航动画即可,不要影响界面布局了」，即转场不得
  以改变页面结构为代价。于是把上一轮为了「只动标签页以下部分」而钉在 `NavDisplay` 之外的
  `preview` + 标签条收回每个目的地自己的 `LazyColumn`（`sectionHeader()`），页面恢复成
  「一条列表，前两行是预览卡与标签条」，与引入导航之前同形。`NavDisplay` 因此直接
  `fillMaxSize()` 并承接 `.layerBackdrop(barBackdrop)` / `.nestedScroll(...)`——原来挂在
  中间那层 `Column` 上的两个修饰符随该 `Column` 一并删除。`sectionPadding.top` 从 `12.dp`
  改为 `padding.calculateTopPadding() + 12.dp`：状态栏内边距原先由 Scaffold 施加在外层
  `Column` 上，现在必须由列表自己让开。
  验证：`compileDebugKotlin` / `assembleDebug` 通过且零警告；APK SHA256
  `EB5552BF…8CE63B`（上一版 `8A0CE291…E4E00B6`），`adb install -r` Success；冷启动
  `mCurrentFocus` 落在 `MainActivityAlias`、`pidof` 有进程、logcat 无 `FATAL EXCEPTION` /
  `No entry` / `Duplicate contentKey`。**静止态布局与改动前逐像素一致**（见上文两次 dump 的
  `bounds` 对比），这一条是本轮唯一可自证的验收点。转场观感同前，仍待用户目视。
- 导航覆盖穿透（第三次返工）：用户报告「不同标签页会覆盖上一个标签页的内容在底层」。
  根因是**交叉淡入与透明列表不相容**：`opaqueDepth = 1f` 刻意让被覆盖层继续组合并绘制
  （窗口 `-1 < d <= opaqueDepth`），而两个 `LazyColumn` 尺寸位置完全相同，被覆盖层只能被上层
  **真正画出的像素**遮住——卡片间 12dp 间隙与较短分区尾部的大片空白什么都盖不住，
  上一分区于是从那里透出。`Scaffold` 只提供一层底色，在只有一个列表的年代够用。
  修复：给 `SectionList` 的 `LazyColumn` 加 `.background(MiuixTheme.colorScheme.surface)`
  （`SettingsScreen.kt:606`），与 `Scaffold` 同色，静止态逐像素不变。
  验证：`assembleDebug` 通过且零警告；APK SHA256 `3CFDDE1E…0605`（上一版 `EB5552BF…8CE63B`），
  `adb install -r` Success；`javap -c` 确认 `BackgroundKt` / `background` / `getSurface` 已进字节码
  （排除「没重新编译」）；冷启动 `mCurrentFocus` 落在 `MainActivityAlias`、`pidof` 有进程、
  logcat 无崩溃；dump `v1.xml` 31613 B 与修复前 `ui4.xml` 完全同尺寸（General 静止态节点未变）。
  **装机版本已核对**：`adb pull` 出设备上的 `/data/app/…/base.apk`，SHA256 与本地 APK 逐字相同
  （都是 `3CFDDE1E…0605`），即设备上跑的确实是含本次修复的构建。
  注意：事后一次「最终重编」失败，报 `SettingsRepository.kt:91/133 Unresolved reference 'KEY_TYPE_SIZE'`
  与 `SettingsScreen.kt:796 Unresolved reference 'setOutTypeSize'`——那是**并发的另一个 DSH session**
  正在改 `Prefs.java` / `TrioSettings.java` / `SettingsRepository.kt` 中途留下的未完成状态，
  与本轮修复无关（我的 `.background(...)` 在 `SettingsScreen.kt:606` 仍在）。已安装的 APK 是
  那次失败构建**之前**的产物，因此设备上的版本不受影响；在此仓库与他人并发写文件时，
  任何时刻的 `assembleDebug` 结果都要先确认不是别人改到一半的状态。
  **判据是库契约而非截图**：`isVisibleAt(1f, 1f)` 为 true，所以被覆盖层在**静止态也一直组合、
  一直绘制**（不只是转场期间）——这正是「点完另一个标签后旧内容长驻底层」这种报告的原因。
  转场之外我无法自证：切标签需要注入输入，而本机 `input` 只能在当前前台窗口生效（见下一条更正），
  不能在用户可能正用手机时调用。
- **更正上文关于输入注入的结论。** 之前记录「`adb shell input tap` / `keyevent` / `monkey` 在本机
  全被 MIUI 拦下」只对**非 root** 成立：本机 `su` 可用（`/system/bin/su`，`uid=0(root) … context=u:r:ksu:s0`），
  `adb shell "su -c 'input tap X Y'"` 能成功注入。但它**无法指定目标窗口**，只会打到当时的
  前台窗口——我试过一次，当时前台是用户的 QQ（`com.tencent.mobileqq/…SplashActivity`，
  稍后变成 `…av.ui.AVActivity`），那一击落在了聊天界面上。**因此它不能用于本 app 的定向验证，
  除非先确认焦点在自己 app 上；不应在用户可能正在使用手机时调用。** 已验证过、也仍然安全的
  观察手段是：`dumpsys window | Select-String mCurrentFocus`、`pidof`、`logcat -d`、
  `uiautomator dump` + `adb pull`、`screencap -p` 到文件再 `pull`（`exec-out screencap` 会损坏 PNG）。
- 导航滚动位置（第四次返工）：用户一句「不要记录页面位置」。上一轮刚把「每个目的地各留各的偏移」
  写成了正确语义（见上文条目），这一轮按用户口径推翻：切标签一律回到列表顶部。
  改动只在 `SectionList`（`SettingsScreen.kt:572-587`）：多收一个 `isTop: Boolean`，
  加 `LaunchedEffect(isTop) { if (isTop) listState.scrollToItem(0) }`；四个 entry
  （`SettingsScreen.kt:467/475/483/497`）各自传 `isTop = nav.backStack.lastOrNull() == Route.Xxx`。
  **键必须是 `isTop` 而不是 `Unit`**：条目被覆盖时仍在组合，`LaunchedEffect(Unit)` 只在创建它的
  那次组合里跑一次，之后成为栈顶不会再触发。
  验证：`assembleDebug` BUILD SUCCESSFUL 且零警告；`javap -c` 里出现
  `LazyListStateKt.rememberLazyListState` 与 `SettingsScreenKt$SectionList$1$1.<init>:(Z…Continuation;)V`
  （那个 `Z` 就是 `isTop`，证明 lambda 确实捕获了它而不是常量）；`SectionList` 签名变为
  `(PaddingValues, boolean, Function1<LazyListScope, Unit>, Composer, int)`。
  APK 33359674 B，SHA256 `BD2C2E96E7DBB196D6E4F004C9F05E100A0471C8E52445F9DD9FF1A605DD933C`
  （上一版 `3CFDDE1E…0605`；体积变大是因为**并发的另一个 DSH session** 的「外环字号」功能
  同时进了这一版，其 `Prefs.java` / `TrioSettings.java` / `SettingsRepository.kt` 已改完并通过编译）。
  `adb install -r` Success；冷启动 `mCurrentFocus` 落在 `MainActivityAlias`、`pidof` = 2675、
  logcat 干净。**装机版本已核对**：`adb pull` 设备 base.apk 到 `.tmp\installed2.apk`，
  SHA256 与本地同为 `BD2C2E96…933C`。
  「回到顶部」这一步的实际手感仍需用户目视——切换标签要注入输入，无法自证。
- **构建环境变更**：`.tools\jdk\jdk-21.0.12.1+1`（Temurin 21.0.12.1+1，从 Adoptium 下载，195.6 MB）
  是新增的，`env.ps1` / `build.ps1` / `release.ps1` / 本文档的 `JAVA_HOME` 都已从 JDK 17 改为它。
  JDK 17 目录**保留未删**，但已不被任何脚本引用。
- 电量环缺口（本轮修复）：用户报告「电量环在中间断开后，应该是左右两半各 50%，现在的逻辑不对，
  会把中间显示为其他图标的位置也算作电量区域了」。根因是 `TrioRenderer.batteryRing` 里
  `level/100` 被当成**整条路径**的进度直接用，缺口宽度没有从 `to` 里补偿回来。
  改为按可见弧长换算（见「几何」一节）。验证走 `work\gapcheck\verify.ps1`：
  同一份 `GapProbe` 跑两遍，固定版 `exit 0`（63 档扫描 + 12 项端到端全绿），
  只把 `batteryRing` 换回旧算法的对照版 `exit 1`（42 项不符，45% 就已填满左半环、
  55/60/65% 右半环完全不起弧）——**反例成立**才说明探针在测这个 bug 而不是恒真。
  `javac -source 8 -target 8 -bootclasspath android.jar` 编 10 个 hook 源 `exit 0`；
  `gradle --offline assembleDebug` `exit 0`。**探针第一版曾因端帽误判**（见下条教训）。
- 教训（端帽）：探针第一版按手工取点分类左右段，在 45%/50% 上报了大量假失败，原因是
  `STROKE` 是 `Paint.Cap.ROUND`，每段末端向外多渗半个笔画宽（`stroke = 14` 时约 0.032 路径进度、
  7.79°）。凡是「看着渲染器明明正确、断言却失败」的像素校验，**先怀疑端帽**，改成区间模型并
  从点亮末端扣掉 `cap` 再比。这条同样适用于 `centreClearWidth` / `gapClearWidth` 之外的任何
  像素级判定。
- 电量环缺口**上机验证**（小米 14 / houji / Android 17 / HyperOS 4 / KernelSU）：`install.ps1
  -InstallOnly` Success，`adb pull` 设备 `base.apk` 与本地 SHA256 逐字相同
  （`E9FB1EDF5918048C974988DA254A675CDF798FBFDE905D81A23C3EE97AF814B0`，33359674 B），
  且 APK (18:25:42) 晚于 `TrioRenderer.java` (17:19:26)——设备上跑的确含本次修复。
  SystemUI 重启后 `HyperDuo installed, hooks=9 enabled=true`，无 `FATAL EXCEPTION`。
  两帧定量测量（`work\ondevice\measure.ps1` / `m60b.ps1`：按颜色分类像素 → 代数拟合圆心半径 →
  映射到路径进度 `t` → 输出连续区间；`TrioGeometry.B_START=148.69008689281117`、
  `B_SWEEP=242.6198262143777`）：
  - **52%**（真实电量，充电中）：绿 `0.002..0.398` 与 `0.666..0.744`，灰含 `0.445..0.999`。
    `u = 0.52 × 0.65 = 0.338`，左段应到 `min(u, m0=0.325)`、右段应从 `0.675` 起画
    `0.013` 残段——两段都出现，缺口内部无绿。
  - **60%**（`dumpsys battery set level 60` 强制值，判别性帧）：绿 `0.001..0.325`（左，止于
    `m0`）+ **右半环绿 0.675..0.783 共 96 个采样**，灰 `0.384..0.623` 等。固定版预测
    `u = 0.39` → 右段 `0.675..0.740`（含端帽到 0.772），实测吻合。**这一帧是判别性的**：
    旧算法 `to = 0.60 < m1 = 0.675` 时右半环一个像素都不画，与实测的 96 个绿采样互斥。
    目视见 `work\ondevice\lv60-fixed-8x.png`（8x）：左半环绿满、右半环从缺口右缘起一小截绿、
    其余为灰轨道。缺口内部仅 3 个采样落在 `0.357..0.643`（495 个中的抗锯齿边缘像素）。
  **强制电量必须还原**：`set level` 是全局持久状态，要写成
  `dumpsys battery set level 60; sleep 2; screencap -p /sdcard/x.png; dumpsys battery reset`
  **同一条 `adb shell`**，否则中途掉线/中断会把手机卡在假电量上（本轮真发生过一次，
  重插 USB 后 `reset` 才清掉，前后读回都是 `level: 47`）。
  拟合圆心时另有两条坑：只用绿像素拟合会退化（绿只有两段短弧），而把「中性灰」阈值放宽到
  `mx >= 90` 会把背景 `#656563` 一起算成轨道（13144 px，圆心被拖到背景里）。正确做法是先按
  目视取粗略圆心做环形预筛（`18 < d < 34`）再拟合，且背景灰度必须排除。
- **全量样式总览图**（对照 macOS 版 `Status Trio` 总览图出的图）：`work\overview\run.ps1`
  `exit 0`，`work\overview\out\overview.png`，`1788×2248`，3 组共 **25 格**，
  `renderer = 92f6733e0b52a58409b17d26270b6a6fc0fa3152978c2cd62d2c5262bb9bb420`
  （即出图时 `TrioRenderer.java` 的 SHA256，用来确认图对应哪份代码）。放大目视核对
  `crop-battery.png`(2x) / `crop-wifi.png`(2x) / `crop-mobile.png`(2x) / `crop-footer.png`(2x) 逐格正确；
  Wi-Fi 组末格「居中后充电」现在画的是**缺口里的小闪电 + 圆心数字**，与改动前的「闪电居中」相反。
  **页脚长这样是有守卫的**：`footerLine` 用 `FontMetrics.stringWidth` 量宽并 `throw`，宁可出图失败
  也不让文字被裁掉——本页新增的居中说明行第一次就超宽 76px 被它拦下，改为更短的句子才通过。
  **清点结论**：参考图的「蓝牙音频」4 格与「音量」7 格在本模块无任何实现
  （`volume|bluetooth|audio|headset|earbud` 零匹配），「已连接电源，未充电」也没有对应状态
  （渲染器只有充电 / 未充电两态），这三类都不应画进图里 —— 详见「测试」一节的样式清单。
  原图曾另有一组「外观与几何」12 格，后按要求删除：那是外观旋钮而非图标状态。
- 教训（出图也会溢出）：总览图页脚前两版都**超出右边界被静默裁掉**，肉眼在图缩略图上根本看不出。
  改成 `footerLine(...)` 先 `getFontMetrics().stringWidth` 量宽、超宽直接 `throw`，
  立刻报出 `footer line overflows the page by 49px`。凡是定宽排版出图，都要让"画不下"变成
  异常而不是裁剪。
- 教训（PowerShell 5.1 与 BOM）：工作树里的 `release.ps1` 丢过 UTF-8 BOM，PS 5.1 于是按 GBK
  解码中文注释，多字节序列吞掉后面的引号，报出 8 个**指向完全正确行**的 parse error。凡在
  PowerShell 5.1 下跑、且含中文的 `.ps1`，都要确认首字节是 `239,187,191`。已逐个按「解码后
  是否出现乱码」判定：`release.ps1`（517 个汉字，**必须**带 BOM）、`install.ps1`（`install.ps1:66`
  的 `Duo 三合一状态栏` 会被解成 `Duo 涓夊悎涓€鐘舵€佹爮`，已补 BOM，len 3140→3143）；
  `env.ps1` / `build.ps1` 一个汉字都没有，无 BOM 也无所谓，保持原样。
  BOM 有无只看 **parse error 是不够的**——`install.ps1` 在无 BOM 时 parse-errors 仍然是 0，
  错的是运行时打印出来的字。
- **1.1 发布**（`release.ps1 -Version 1.1 -NotesFile work\notes-1.1.md`，`exit 0`）：tag `v1.1`
  → `03b36b5f5a0f95709255dbf85705da724da4cfb3`（与 `main` 同一提交，即构建的确实是已提交的代码）；
  Release `HyperDuo 1.1` 非 draft / 非 prerelease，body 808 字符；asset `HyperDuo-1.1.apk`
  3038076 B。**发布产物按字节复核**：从 `api.github.com/repos/…/releases/assets/605602727`
  下载回来 SHA256 `9E024FA1A8988947DA9D6E694AC83B9A737FE7C2EC85C32CDB406C8414FBA6DC`
  与本地 `dist\HyperDuo-1.1.apk` 逐字相同。签名 `CN=HyperDuo` 与设备上原装 APK 同一证书
  （SHA-256 `b4e3a12d…8c41f`），所以能 `-r` 覆盖安装而不冲突。
- **1.1 release 包上机验证**（这一步不可省：R8 会剥离反射用到的类）。`adb install -r` Success，
  `versionCode=10100` / `versionName=1.1`、`pkgFlags` 里 **没有 `DEBUGGABLE`**（release 构建）。
  类名在 dex 里是**斜杠描述符**（`Lcom/hyperduo/trio/TrioHooks;`），用点号搜会全部假阴性 ——
  `TrioHooks` / `TrioRenderer` / `TrioConfig` / `Prefs` / `TrioSettings` / `HyperDuoModule` /
  `ui/RestartController` 全部在 `classes.dex` 里，未被剥离。SystemUI 重启后
  `HyperDuo installed, hooks=9 enabled=true`、`reload receiver registered`，无 `FATAL EXCEPTION`。
  截图 `work\ondevice\release-1.1-statusbar.png` 目视确认：三合一图标正常（绿色充电弧 + `49` +
  闪电 + 底部信号点），环外网络类型 `5G` 也画出来了。日志中对应
  `out type: "5G" size=45.0 label=60x60 at 341,14 anchor=407..491 container=491x88`。
- **1.3 发布**（`release.ps1 -Version 1.3 -NotesFile work\notes-1.3.md`，`exit 0`，构建耗时
  1m19s）：tag `v1.3` → `e97f73d6347f842a21511db03f37cde299732c48`（与 `HEAD`/`main`
  同一提交——`release.ps1` 构建工作树但给 `HEAD` 打 tag，所以**必须先提交干净再跑**）；
  Release `HyperDuo 1.3` 非 draft / 非 prerelease（`draft=False` / `prerelease=False`），
  body 496 字符，asset `HyperDuo-1.3.apk` 3038276 B。**发布产物按字节复核**：从
  `https://github.com/yixing233/HyperDuo/releases/download/v1.3/HyperDuo-1.3.apk` 下载回来
  SHA256 `2AAD1D6AA5920FBA4EA3F1E44B1349364752C81A9BA63675DA1D365EB2916EA1`
  与本地 `dist\HyperDuo-1.3.apk` 逐字相同（R8 产物体积不固定，每次构建字节都不同，
  所以「下载回来对哈希」这一步不能省）。`git ls-remote --tags` 确认远端有 `refs/tags/v1.3`。
  **`v1.2` 从未打过 tag 也从未发布**——`dist\HyperDuo-1.2.apk` 只是本地产物（它含改名后的串
  「电量数字居中」，但还不含居中的优先级修复）。所以 1.1 → 1.3 的用户一次性拿到改名 + 修复，
  1.3 的 release notes 只写这两件事，不提 1.2。
