# 样式开关梳理与优化方案

本文只做两件事：把当前「哪些开关控制什么、谁又被谁门控」讲清楚，然后给出一个可以分批落地的优化方案。所有结论都标了源码位置，可以直接跳过去核对。

> **阅读提示**：一、二两节是**改造前**的快照，其中的行号与代码引文都指向改造前的版本，保留下来是为了让「为什么值得改」有据可查；方案落地后的实际结果、以及三处与本文提案的偏离，记录在第六节。想直接看最终状态就跳到第六节。

---

## 一、当前的开关全景

### 1.1 三层结构

| 层 | 文件 | 职责 |
| --- | --- | --- |
| 存储 / 默认 / 界限 | `app/src/main/java/io/github/yixing233/hyperduo/Prefs.java` | 25 个在用键（另有 1 个只读 legacy 键 `show_mobile_type`）、全部 `DEF_*` 默认与上下限，两侧共用的唯一真源 |
| 快照 | `app/src/main/java/io/github/yixing233/hyperduo/TrioSettings.java` | 25 个 public 字段、`from`/`copy`/`applyKeyFrom`/`fromBundle`/`toBundle`，clamp 在读侧发生 |
| 写入漏斗 | `app/src/main/java/io/github/yixing233/hyperduo/ui/SettingsRepository.kt` | 20 个 setter，每个都「本地 commit → push 到远程 preferences → 广播」 |
| 渲染 | `app/src/main/java/io/github/yixing233/hyperduo/TrioRenderer.java` | 只读 `TrioSettings`，画圆环或矩形 |
| Hook | `app/src/main/java/io/github/yixing233/hyperduo/TrioHooks.java` | 开关折叠槽位、交还原生图标、挂环外标签 |
| 界面 | `app/src/main/java/io/github/yixing233/hyperduo/ui/SettingsScreen.kt` | 4 个分页、20 个可写项、33 处 `enabled =` |

链路是单向的：`SettingsScreen`（唯一写入口 `update`，`SettingsScreen.kt:287-290`）→ `SettingsRepository` → SharedPreferences + 远程 preferences + 广播 → `TrioConfig` 快照 → `TrioRenderer` / `TrioHooks`。**渲染侧从不回写**，这条边界是干净的，问题全部出在「开关之间的语义」上。

### 1.2 开关清单与真实归宿

| 开关 | 键 | 设置页行 | 渲染侧读取点 | Hook 侧读取点 |
| --- | --- | --- | --- | --- |
| 总开关 | `enabled` | `:626-632` | 从不读 | **11 处**（`TrioHooks.java:149/333/477/493/721/793/794/841/849/1050/1723`）|
| 三合一样式 | `trio_style` | `:642-654` | `TrioRenderer.java:115` 唯一 | 不读 |
| 显示 Wi-Fi 弧 | `show_wifi` | `:655-662` | `:214`、`:534` | `:153` |
| 显示移动信号点 | `show_mobile` | `:663-670` | `:217`、`:251` | `:156` |
| 显示电量数字 | `show_value` | `:671-678` | `:178`、`:189`、`:260`、`:271` | `:477`、`:793`、`:841` |
| 充电时显示闪电 | `show_bolt` | `:686-695` | `:178`、`:260` | `:477`、`:793`、`:841`、`:880` |
| 网络类型位置 | `mobile_type_mode` | `:709-717` | `:182`、`:261` | `:1723`、`:1830` |
| 电量数字居中 | `swap_wifi_value` | `:723-735` | `:172` **唯一，且只在圆环分支** | 不读 |
| 状态颜色 | `role_colors` | `:881-888` | `:389` 唯一 | 不读 |
| 三色 ×2（深/浅） | 六个 `color_*` | `:906-922` | `:394/397/400` | 不读 |
| 低电量阈值 | `low_threshold` | `:896-905` | `:396` 唯一 | 不读 |
| 环线粗细 | `ring_stroke` | `:748-756` | `:307`(矩形条)、`:409`、`:562/584/600` | 仅日志 `:346` |
| 弧线粗细 | `arc_stroke` | `:757-765` | `:337`(矩形位移)、`:482`、`:497` | 仅日志 `:346` |
| 数字字号 / 字重 | `value_size` / `value_weight` | `:766-774` / `:779-793` | `:371/561`、`:372/566` | 不读 |
| 圆环内类型字号 | `type_size` | `:805-815` | `:360`、`:583`、`:599` | 不读 |
| 环外字号 | `out_type_size` | `:823-836` | 不读 | `:1801`、`:1939-1944` |
| 网络类型字重 | `type_weight` | `:842-853` | `:361`、`:585`、`:601` | `:1946`（环外标签用它）|
| 底纹浓度 | `track_alpha` | `:854-862` | `:285`、`:311`、`:411`、`:518` | 不读 |
| 调试日志 | `debug_log` | `:950-958` | 不读 | `:80-82` |

**第一个整体印象**：25 个在用键的归属可以干净地分成三类——

- **两侧都读（9 个）**：`show_wifi`、`show_mobile`、`show_value`、`show_bolt`、`mobile_type_mode`、`ring_stroke`、`arc_stroke`、`value_size`、`type_weight`。
- **只有渲染侧读（13 个）**：`trio_style`、`swap_wifi_value`、`track_alpha`、`value_weight`、`type_size`、`role_colors`、六个 `color_*`、`low_threshold`。
- **只有 Hook 侧读（3 个）**：`enabled`、`out_type_size`、`debug_log`（`TrioConfig.debugLog()`，`TrioHooks.java:80-81`）。

而 9 个「两侧都读」里，`ring_stroke`/`arc_stroke`/`value_size` 在 Hook 侧只出现在 `TrioHooks.java:346-347` 的 debug 日志字符串里、不参与任何逻辑。**真正需要两个进程就同一份规则达成一致的只有 7 个**：6 个结构开关（`enabled` 由 Hook 侧独占但语义上凌驾全部）加 `type_weight`（环外标签按它取字重，见 2.6）。恰恰就是这几个，把复杂度撑起来了。

---

## 二、六个具体的「乱」及其证据

### 2.1 同一条规则被逐字写了 5 遍，加界面第 6 遍

「模块画闪电时，原生闪电必须让路」这条规则写成 `showBolt && showValue`，然后在两处根据所在侧补上各自的前缀（Hook 侧补 `enabled`，渲染侧补 `charging`）：

| 位置 | 原文 |
| --- | --- |
| `TrioRenderer.java:178`（圆环分支）| `final boolean bolt = charging && cfg.showBolt && cfg.showValue;` |
| `TrioRenderer.java:260`（矩形分支）| 同一行，逐字复制 |
| `TrioHooks.java:477`（`applyMeterText`）| `final boolean glyphBolt = cfg.enabled && cfg.showBolt && cfg.showValue;` |
| `TrioHooks.java:793`（`onBatteryStyleChanged`）| 同一行，逐字复制 |
| `TrioHooks.java:841`（`updateChargeAndText`）| `if (cfg.enabled && cfg.showBolt && cfg.showValue) {` |

第 6 处是设置页 `SettingsScreen.kt:693` 的门禁 `gated && settings.showValue`——它写的不是同一个表达式，但表达的正是同一个约定的另一半（这个开关只在数字开着时才有意义）。

五处代码里任何一处改了、别处没跟上，就会出现「设置页说能开、渲染不画」或者「渲染画了、原生也没让路，两个闪电叠在一起」。`TrioHooks.java:790-792` 的注释甚至反过来引用 `TrioRenderer` 来说明自己为什么要这么写（`// which per TrioRenderer is showBolt && showValue`）——这本身就是耦合信号：一处规则要靠注释去提醒另一处同步。

顺带指出 `TrioRenderer.java:178` 与 `:260` 是同一表达式的**两次复制**（圆环与矩形各一份），而不是抽成一个函数再调用两次。

### 2.2 「数字居中」有两个来源，只有一个开关

`TrioRenderer.java:190`：

```java
final boolean valueInCentre = hasValue && (centred || (!wifi && !typeInRing));
```

`centred` 是用户的 `valueCentred` 开关，而 `(!wifi && !typeInRing)` 意味着**没有 Wi-Fi 弧、也没有环内网络类型时，数字自己就跑到圆心了——和「电量数字居中」这个开关毫无关系**。

结果就是设置页 `:733` 的文案（`value_centre_summary`「电量数字放大到圆环中间并始终优先占据圆心」）对用户描述的是一件由开关控制的事，用户实际看到的却是「关掉 Wi-Fi 后数字也会跳中间」。默认外观（圆环、无网络类型）本身就长期处于这个隐式居中状态，反而让这个开关看起来「开了没变化」。

### 2.3 「三合一样式」选了矩形之后，一批设置默默换了含义或直接失效

`TrioRenderer.java:243-246` 自己写得很清楚：

> the bar takes its thickness from `ringStroke`, the arcs still take their own, and `valueCentred` is ignored outright

对照 `TrioSettings.java:40-47` 关于 `trioStyle` 的文档断言——两种排布共享其余全部设置，只有几何与绘制顺序不同——**这两段话互相矛盾**。实际情况是：

| 设置 | 圆环下 | 矩形下 |
| --- | --- | --- |
| `valueCentred` | 完整语义（数字进圆心、弧进缺口） | **完全不读**（`drawRectLayout` 248-274 无任何查询）|
| `ring_stroke`（文案「环线粗细 / 电池圆环的描边宽度」）| 环描边宽度 `:409` | 变成电量条厚度 + 上半圆角半径 `:307` |
| `arc_stroke`（文案「Wi-Fi 弧线的描边宽度」）| 弧线描边 `:497` | 弧线描边**加上**整组 Wi-Fi 的 y 位移 `:337` |
| `type_size` | 环内 120×120 设计空间（`RECT_TYPE_SCALE` 不参与）| 乘 `RECT_TYPE_SCALE = 1.26` `:360` |
| `value_size` | 原值 `:561` | 乘 `RECT_VALUE_SCALE = 1.38` `:371` |
| 顶端槽 | 数字/类型/弧可并存 | 三选一 else-if，bolts > Wi-Fi > 类型 `:263-269` |

设置页 `:733` 的 `value_centred` 行**没有 trioStyle 门禁**（只查 `gated && showWifi && showValue`），而 `geometryTab`（`:741-865`）在注释 `:794-799` 里明确声明「不做 trioStyle 分支」。于是**矩形样式下，设置页会出现一个完全没有任何效果的开关，和两个文案与行为不符的滑块**。

### 2.4 门禁的表达方式不统一，而且原因看不见

33 处 `enabled =` 里，20 个可写项的依赖关系有 5 种：仅总开关（14 个）、`showValue`（2 个）、`mobileTypeMode`（3 个）、`roleColors`（4 个）、`showWifi+showValue`（1 个），另有 1 个无门禁（调试日志）。

被禁用的原因**只能长按 Tooltip 看到**（`gateHint` `:1668-1672` → `TooltipBox`），行本身是灰的、没有任何文字说明。而且 `gateHint` 只报第一个未满足的依赖，`show_mobile_type_title`（「网络类型」）这个标题被三处不同含义的缺依赖提示反复借用（`:803`、`:821`、`:840`）。

`enabledCount`（`:1779-1787`）统计的 5 项里不含 `enabled`、`roleColors`、`valueCentred`、`trioStyle`，所以总开关关掉之后，关于页仍然会显示「已开启显示项 5」——**这个数字和「实际画了几个东西」不是一回事**。

### 2.5 尺寸页把 8 个互不相干的滑块平铺在一张卡里

`geometryTab`（`:741-865`）没有 `SectionTitle`（全仓库唯一的组标题是常规页的 `group_appearance`，`SettingsScreen.kt:623`），8 个滑块一个 Card：

1. 环线粗细 ← 只在圆环样式有意义
2. 弧线粗细 ← 两种样式都有意义但语义不同
3. 数字字号 ← 数字关掉就没用
4. 数字字重 ← 数字关掉就没用
5. 圆环内类型字号 ← 只在 `mobileTypeMode == IN_RING` 有意义
6. 环外字号 ← 只在 `mobileTypeMode == OUT_RING` 有意义
7. 网络类型字重 ← 只在 `mobileTypeMode != 0` 有意义
8. 底纹浓度 ← 总是有意义

也就是说 8 行里只有 1 行是「无条件有效」的，其余 7 行各自挂在一个用户可能还没做的选择上，但它们在视觉上是平级的、顺序也不反映任何分组。

### 2.6 预览盖不住所有东西，写入路径有三条

预览（`PreviewCard` `:1163-1196`）走 `TrioPreviewView` → `TrioRenderer.drawInto`，和状态栏共用同一份渲染代码，这点是好的。但它有盲区：**环外网络类型不是 `TrioRenderer` 画的**，而是 `TrioHooks` 挂的一个 `OutTypeLabel` TextView（`TrioHooks.java:1697-1704`）。所以 `out_type_size` 这个滑块在设置页里**永远没有任何可见反馈**，用户只能切回状态栏看。

写入路径有三条而不是一条：标准 `writeBoolean`/`writeInt`（`SettingsRepository.kt:147-157`）、手写的 `resetRoleColors`（`:66-84`，6 个键 commit 一遍再统一 push + 广播）、以及整表推送的 `syncAllToFramework`（`:111-145`，服务绑定后把本地全部值推给框架）。三条都必须记得「push → notifyModule」，而 `notifyModule`（`:173-180`）的载荷恒为全量按键打包。

顺带两个更小的坑：`strings.xml:55` 的 `color_preview`（「预览」）零引用，是死资源；`Prefs.java:85` 的 `KEY_VALUE_CENTRED = "swap_wifi_value"` 键名与含义永久不符（这个**必须保持不动**，注释 `:78-84` 已经说明改名会静默重置老用户的选择）。

---

## 三、优化方案

总的方向：**把「开关之间的规则」从散落在 6 个文件里的 if 组合，收敛成一个可以被两侧共用的、有名字的纯函数；再让界面从同一份声明驱动。**

分三级落地，P0 是不改行为、只收敛和修正的；P1 会动到键和界面结构；P2 是补齐性工作。

### P0-1（核心）：抽出「有效外观」纯函数 `TrioAppearance`

在 `TrioSettings` 旁边加一个不可变的值对象和唯一一个构造函数：

```java
final class TrioAppearance {
    final boolean drawGlyph;        // enabled
    final boolean wifi, mobile;     // showWifi / showMobile
    final boolean bolt;             // enabled && showBolt && showValue
    final boolean value;            // showValue
    final boolean valueCentred;     // enabled && showValue && valueCentred && style == RING
    final boolean typeInRing;       // enabled && mode == IN_RING
    final boolean typeOutOfRing;    // enabled && mode == OUT_RING
    final boolean roleColors;
    final int     ringStroke, barStroke, arcStroke, trackAlpha, lowThreshold;
    final float   valueSize, valueWeight, typeSize, typeWeight, outTypeSize;
    final int     trioStyle;
}

static TrioAppearance appearance(TrioSettings cfg) { /* 规则只在这里写一次 */ }
```

收益：

- 5 处逐字相同的 `enabled && showBolt && showValue` → 1 处。
- **`valueCentred` 在矩形下自动为 `false`**（把 `trioStyle != RING` 写进规则），渲染侧不必再「记得忽略」，`TrioSettings.java:40-47` 与 `TrioRenderer.java:243-246` 的那处文档矛盾也随之消失。
- `barStroke` 与 `ringStroke` 在同一步拆成两个字段（默认相等），为 P1 的独立键留好位置。
- `TrioHooks.java:1050`（settle 短路）、`:721`（字形绘制）、`:1723`（环外标签）统一读 `a.drawGlyph` / `a.typeOutOfRing`。
- `TrioRenderer.drawRingLayout` / `drawRectLayout` 里那些逐字重复的谓词消失，两条分支的公共前缀收敛到一处。

关键点：**这个函数是纯函数、不依赖 Android，因此 `TrioHooks`（SystemUI 进程）和设置页都能调**——设置页就能用同一份规则算「这一行现在到底有没有效果」。

### P0-2：设置页按规则驱动 `enabled`，不再手写条件

有了 `appearance()`，设置页每一行的门禁不再是散落的表达式，而是查表：

- 有 `appearance` 就直接用（`show_bolt` ← `a.bolt` 的依赖项、`value_centred` ← `a.valueCentred` 是否可能为真）。
- 「这一行在当前样式/模式下有没有效果」用一个显式枚举表达：`Scope.ALWAYS / RING_ONLY / RECT_ONLY / TYPE_IN_RING / TYPE_OUT_RING / TYPE_ANY`。

顺带修掉 `enabledCount`（`:1779-1787`）——改成统计 `appearance()` 里为真的绘制项，或者干脆把它挪到常规页顶部、只统计「画了什么」。

### P0-3：几何页加分组标题（不改任何行为）

`geometryTab`（`:741-865`）拆成同页多张 Card，每张一个 `SectionTitle`：

- **圆环**（`Scope.RING_ONLY`）：环线粗细、弧线粗细
- **矩形**（`Scope.RECT_ONLY`）：电量条粗细、Wi-Fi 弧粗细
- **文字**：数字字号、数字字重、网络类型字重
- **网络类型**：圆环内字号（`TYPE_IN_RING`）、环外字号（`TYPE_OUT_RING`）
- **底纹**：底纹浓度

这一步是纯界面结构改动，风险最低，收益最直观（用户能看出「这些只对圆环生效」）。

### P0-4：三个文案/死资源修正

- `strings.xml:61` `stroke_summary`「电池圆环的描边宽度」→ 按样式取两条：圆环下「电池圆环的描边宽度」，矩形下「底部电量条的粗细」。
- `strings.xml:64` `arc_stroke_summary`「Wi-Fi 弧线的描边宽度」→ 补上矩形下同时决定 Wi-Fi 弧纵向位置（或随 P1 拆键后自然消失）。
- `strings.xml:75` `out_type_size_title`「环外字号」→「环外网络类型字号」。
- 删 `strings.xml:55` `color_preview`（零引用）。

英文侧对应行号相同：`values-en/strings.xml:61`（`Stroke width of the battery ring.`）、`:64`（`Stroke width of the Wi-Fi arcs.`）、`:75`（`Out-of-ring size`）、`:55`（`Preview`），需要同步改。

### P1-1：键元数据表（唯一声明，四处派生）

在 `Prefs.java` 里加一张声明表，把现在散在四个地方的信息合并：

```java
record KeyDef(String key, Kind kind, int def, int min, int max,
              Gate gate, Scope scope, int titleRes) {}
```

由这张表派生：

1. `TrioSettings.from(SharedPreferences)` / `fromBundle` 的 clamp（今天 `TrioSettings.java:118-168` 手写 19 次）。
2. `applyKeyFrom`（`TrioSettings.java:260-292`）的 25 分支 switch → 表驱动（键名 → 字段的映射仍要显式写，但类型/范围不再重复）。
3. 设置页的行、门禁与分组（`Gate`/`Scope` 直接变成 Compose 的 `enabled`）。
4. 文档里的配置表可以写一个小校验脚本对着表核。

`Gate` 用枚举而不是自由表达式，例如 `Gate.NONE / SHOW_VALUE / SHOW_WIFI_AND_VALUE / TYPE_IN_RING / TYPE_OUT_RING / TYPE_ANY / ROLE_COLORS` —— 这样 `gateHint` 要报的那一行标题也就有出处了（消除 `show_mobile_type_title` 三处借用）。`Scope` 是另一个正交维度（`ALWAYS / RING_ONLY / RECT_ONLY`），它不决定行能不能点，只决定「点了在当前样式下有没有效果」。

### P1-2：把「为什么点不动」搬到行内

现在只有长按 Tooltip。建议在 `summary` 下面加一行小字或一个依赖徽标：

```
数字字号
电量百分比文字的尺寸。
需要先打开「显示电量数字」        ← 灰色小字，与 summary 同色阶但更淡
```

实现上把 `gateHint` 的结果从 `TooltipBox(text=...)` 改成同时作为一段行内文本传入，Tooltip 保留（两条路并存不冲突）。这一改动不涉及任何渲染逻辑。

### P1-3：矩形电量条粗细拆出独立键

按 2.3 的表，`ring_stroke` 在矩形下已经承担了另一件事。新增 `bar_stroke`（`Scope.RECT_ONLY`），**缺键时回落 `ring_stroke`**，这样老安装升级后矩形外观不变；用户一旦动过这个新滑块，两条样式就各自独立了。**必须遵守仓库既有的迁移规矩**：`Prefs.java:8-13` 说明框架不做类型转换、每个键终生一种类型；`KEY_MOBILE_TYPE_MODE` 的迁移就是先例（`TrioSettings.java:180-208` 读新 int 键、缺失则回落旧 boolean 键）。

同一批可以视情况给矩形拆 `rect_arc_offset`，但收益不如 `bar_stroke` 直接，可以推迟。

### P2-1：预览补齐环外类型

`out_type_size` 在今天没有任何可见反馈（见 2.6）。最小做法：在预览末尾加第 7 格，画「电池环 + 右侧一个 5G 文本」，字号取 `outTypeSize`、字重取 `typeWeight`。**关键约束**：它必须在预览里用与 `TrioHooks.updateOutTypeLabel`（`:1936-1948`）相同的取值口径（字号 `outTypeSize`、字重 `typeWeight`、PX 而非 SP），否则预览又开始骗人。

### P2-2：`resetAllDefaults` 与写入路径收敛

- 加一个与 `resetRoleColors`（`SettingsRepository.kt:66-84`）对称的「恢复全部默认」。
- `resetRoleColors` 改走统一写路径。
- 中期把 20 个 setter 收敛成 `fun writeInt/writeBoolean/writeString(key, value)` + 由 P1-1 的表生成的薄封装，消除「新增一个设置要改 10 处」的成本：`Prefs`（键、默认、上下限）、`TrioSettings`（字段、`defaults`、`from`、`fromBundle`、`toBundle`、`copy`、`applyKeyFrom`）、`SettingsRepository`（setter）、`SettingsScreen`（行）。

### P2-3：文档同步

`README.md:82-141` 的设置表、`docs/DEVELOPMENT.md:659-684` 的配置项参考、`:686-703` 的已知限制都要跟着改；`TrioSettings.java:40-47` 关于「两种排布共享其余全部设置」的断言必须重写（它和 `TrioRenderer.java:243-246` 直接冲突）。

---

## 四、建议的落地顺序与验收点

| 批次 | 内容 | 风险 | 验收 |
| --- | --- | --- | --- |
| 1 | P0-1 抽 `appearance()`，替换 5 处重复谓词 | 低（纯重构） | 圆环/矩形 × 闪电开/关 四种组合的绘制结果与改前逐像素一致 |
| 2 | P0-4 文案与死资源 | 极低 | 编译通过、资源无未引用 |
| 3 | P0-3 几何页分组 + P0-2 门禁改由 `appearance` 驱动 | 低 | 每行的 enabled 与改前逐一对照，只允许「矩形下 value_centred 变不可用」这一处有意的行为变化 |
| 4 | P1-2 行内原因 | 低 | 灰显行能看到原因 |
| 5 | P1-1 键元数据表 | 中 | clamp 边界逐个复核（尤其 `out_type_size` 16–64）|
| 6 | P1-3 `bar_stroke` + 迁移 | 中 | 老安装升级后矩形外观不变 |
| 7 | P2 预览/写入/文档 | 中 | 预览与状态栏对照；全量设置改动后状态栏一致 |

**注意**：仓库目前**没有任何自动化测试**（`docs/DEVELOPMENT.md:729`，`app/src` 下只有 `main`）。P0-1 这种「行为必须完全不变」的重构，最划算的投入是先补一个纯 JVM 单测（`app/src/test`）来锁 `appearance()` 的真值表——它不依赖 Android，写起来最便宜，而且正好是这个重构的核心。

---

## 五、一句话总结

乱的根源不是开关太多——25 个键里有 16 个只归一侧所有，归属本身是清楚的；乱的是**那几个跨进程的结构开关：它们的组合规则被逐字复制在 5 个地方，而且其中一条规则（矩形样式）与文档声明相矛盾、另一条规则（无 Wi-Fi 时数字自动居中）根本没有开关**。先把规则收敛成一个 `appearance()` 纯函数，界面分组与文案修正就没有阻力了；这块收敛同时也让「加一个设置要改 10 处」变成「改 4 处」。

---

## 六、落地结果（改造已完成，本节为事后回填）

改造按第三、四节的方案执行完毕，批次 1–3 与 P2-1 已落地，P1-1/P1-2/P1-3、P2-2 未做（理由见下）。这一节记录**实际做成了什么**，上面几节的旧行号随之作废。

### 6.1 新增的文件与核心类型

`app/src/main/java/io/github/yixing233/hyperduo/TrioAppearance.java`（新文件，296 行）是全场唯一的外观规则源。它刻意**不含任何 Android 类型、不含静态状态**，所以设置页进程与 SystemUI 进程都能构造同一份。

- 公开 final 字段（`:31-81`，后又加 `signalMode`/`signalInRing`/`signalOutOfRing`/`stackedSignal`/`dataSimOnly`）：`style`、`rect`、`glyph`（= `enabled`）、`wifi`、`mobile`、`dualSim`、`value`、`boltWanted`、`centreValue`、`typeMode`、`typeInRing`、`typeOutOfRing`、`roleColors`、六个颜色、`lowThreshold`、`ringStroke`、`arcStroke`、`trackAlpha`、`valueSize`、`valueWeight`、`typeSize`、`outTypeSize`、`typeWeight`。
- 入口 `TrioAppearance.of(TrioSettings)`（`:116-119`，`null` → 默认值）；`TrioConfig.appearance()` 把它包了一层，避免调用点各自手抄规则。
- 两个派生谓词：`drawsBolt() = boltWanted && value`、`hidesNativeBolt() = glyph && drawsBolt()`（`:132-142`）——前者**不含** `glyph`，因为设置页预览在总开关关闭时仍要渲染所选样式；后者才是 Hook 侧「原生闪电该不该让路」的判据。后续新增的三个谓词沿用同一套写法：`signalDots() = mobile && signalInRing`、`stackedOut() = glyph && mobile && signalOutOfRing && stackedSignal`、`foldsMobile() = signalDots() || stackedOut()`（`:201-231`）。
- `typeAnywhere()`（`:172-174`）、`wifiInk(int)`（`:145-147`）、`dualSimRows(boolean wifiInk, boolean charging, int sims)`（`:167-169`）。
- 两个内嵌布局决策对象：`Ring`（`:199-250`）与 `Rect`（`:266-295`），把「哪个元素占圆心、哪个进缺口」这类槽位仲裁从渲染器里搬了出来。渲染器现在只读它们的最终布尔值。

### 6.2 与本文提案的三处偏离（重要）

1. **命名**：提案里叫 `appearance()`/`drawGlyph`/`bolt`，落地为 `TrioAppearance.of(...)`/`glyph`/`boltWanted` + `drawsBolt()`。语义相同，`boltWanted` 特意与 `drawsBolt()` 区分开：前者是「用户想不想在充电时看闪电」，后者是「这一帧到底画不画」。
2. **P1-3 的 `bar_stroke` 新键被弃用**。提案想给矩形电量条拆一个独立厚度键；实际做法是**让它继续读 `ringStroke`**，改由设置页在矩形样式下把这一行改称「电量条粗细」（`stroke_title_rect` / `stroke_summary_rect`）。理由是加第二个键只会多出一个在另一种样式下完全惰性的滑块，而用户真正需要的是「知道这一行在这里管什么」，不是再多一个正交旋钮。这条决定写在 `TrioRenderer.drawRectBatteryBar` 的 javadoc（`TrioRenderer.java:300-303`）里。
3. **P0-2 的 `Scope` 枚举没有引入**。提案打算用 `Scope.ALWAYS / RING_ONLY / ...` 显式表达「这一行在当前样式下有没有效果」；实际是让每个 tab 在 `Card` 开头取 `val a = TrioAppearance.of(settings)`，然后**直接写 `a.*` 表达式**当 `enabled`。少一层间接，规则仍然只有一处真源；代价是个别行的条件看起来长一些（例如 value_centred 那行）。

### 6.3 设置页改造结果（`app/src/main/java/io/github/yixing233/hyperduo/ui/SettingsScreen.kt`）

- **签名变化**：`generalTab` / `geometryTab` / `colorsTab` 三个 tab 的函数都**去掉了 `gated` 参数**，改为自己从 `settings` 推出 `val a = TrioAppearance.of(settings)`。这正是「规则只有一处真源」的直接体现：不再把总开关当作一个横传的布尔值往下发。四个 `entry<Route.*>` 调用点同步不再传 `gated`。
- **三个 tab 的全部门禁改为 `a.*`**，文件里 `gated` 一词只剩五处英文注释里的普通用词（`SettingsScreen.kt:375`、`:642`、`:676`、`:726`、`:1746`），**零处代码**——`gated` 这个参数名随之从这个文件里消失。
- `generalTab`：master Switch 恒可点（不加 `enabled`）；trio_style 下拉 `enabled = a.glyph` / `selectedIndex = a.style`；showWifi、showMobile、showValue、typeModes 下拉、signal_mode 下拉均只门禁 `a.glyph`；双卡行 `enabled = a.glyph && a.mobile`；stacked_signal 行 `enabled = a.glyph && a.signalOutOfRing`；data_sim_only 行 `enabled = a.glyph && a.signalOutOfRing && a.stackedSignal`；showBolt 行 `enabled = a.glyph && a.value`，其提示链用 `a.value to show_value_title`。（**此处曾在落地时写成 `a.glyph && a.drawsBolt()` 并造成自锁，见 6.7。**）
- `geometryTab`：拆成四组、同页多张 Card，每组一个 `SectionTitle`——`group_stroke`（环线/弧线粗细）、`group_text`（数字字号/字重）、`group_type`（环内类型字号/环外字号/类型字重）、`group_track`（底纹浓度）。落地的分组与提案 P0-3 的**五项并不同**：提案里的「圆环」「矩形」两组没有采用，因为两种样式共用同一批滑块、并不存在只对矩形生效的滑块（正是 6.2 第 2 条把 `bar_stroke` 砍掉的结果），把它们拆成两张卡只会让人以为有两套尺寸。改为按**量纲**分组（描边 / 文字 / 网络类型 / 底纹）。
- 矩形样式下的文案切换：`stroke_title` → `stroke_title_rect`「电量条粗细」、`stroke_summary` → `stroke_summary_rect`、`arc_stroke_summary` → `arc_stroke_summary_rect`（补上「同时决定这组弧线在顶部纵向位置」），全部由 `if (a.rect)` 选择。这兑现了 `TrioRenderer.drawRectBatteryBar` javadoc 里「设置页会把它改称 bar thickness」的承诺。
- `colorsTab`：roleColors 开关 `enabled = a.glyph`；低电量阈值、六行颜色、颜色页「恢复默认」全部 `enabled = a.glyph && a.roleColors`。
- **`enabledCount` 重写**（关于页「已开启显示项」）：旧实现统计 6 个原始布尔、不含总开关，于是总开关关掉后它仍然报出「5」，与「实际画了几个东西」不是一回事。新实现从规则派生：`if (!a.glyph) return 0; listOf(a.wifi, a.mobile, a.dualSim && a.signalDots(), a.value, a.drawsBolt(), a.typeAnywhere()).count { it }`。第二轮改动把第二排那一项由 `a.dualSim && a.mobile` 改成 `a.dualSim && a.signalDots()` —— 双卡两排**只在环内存在**，环外底部什么都不画，所以环外时它不该算第二个东西；`a.mobile` 本身仍计入，因为环外堆叠时读数由 `OutSignalView` 画出来，只是换了位置。
- 门禁提示（`gateHint`）机制不变，但**仅被总开关拦下的行刻意不给提示**——总开关就在同一屏上，提示是噪音。这条规则与 `docs/DEVELOPMENT.md:295` 一致。

### 6.4 预览的两格「补上可见反馈」

2.6 指出的盲区（`out_type_size` 在设置页永远没有反馈）已按 P2-1 补齐，但落地的形态与提案的「电池环 + 右侧 5G 文本」不同：**新格单独占一格，并且不画电池环，只画那个环外标签**。

- `TrioPreviewView`（`app/src/main/java/io/github/yixing233/hyperduo/TrioPreviewView.java`）新增 `outTypeOnly` 开关与 `drawOutTypeLabel(...)`；`HOST_ICON_HEIGHT_DP = 20f` 对应宿主 `status_bar_icon_height`，因为环外标签的尺寸只有相对这个 20dp 图标盒才有意义（宿主画布详见 `docs/DEVELOPMENT.md:502-503`）。
- **口径必须与 `TrioHooks.updateOutTypeLabel` 完全一致**：字号 `outTypeSize`、字重 `typeWeight`、**px 而非 sp**；再把宿主 20dp 盒按预览自身高度等比换算。提案里写的「在预览里画电池环再配文本」会引入渲染器与 Hook 两套口径混用的风险，这是刻意避开的。
- `TrioRenderer` 为此抽出了 `inkScale(int w, int h, boolean rect)`（转发到私有四参重载），让预览拿到与 `drawInto` 逐位相同的缩放系数；这个系数随样式而变，正是预览不能自己算的原因。
- `PREVIEW_SIZE` 由 `52.dp` 缩到 `46.dp` 以容纳 7 格；文案 `preview_out_type`「环外类型」/「Out type」两语言各一条。
- 收尾时补了一处宽字串处理：状态栏里标签是 wrap-content、宽了向右伸不会被裁，预览格却是正方形，`"5GA"` 会顶边。`drawOutTypeLabel` 因此只在**文本宽度超出格宽**时用 `setTextScaleX` 做水平压缩，不动字号 —— `outTypeSize` 设定的是高度，那一维必须精确。
- **第二轮再加第 8 格**「环外信号」（`preview_out_signal`）：环外时 `glyph` 里没有任何移动信号墨迹（这正是环外 + 堆叠关要交还系统的那一格），若没有这一格，`signal_mode`、`stacked_signal`、`data_sim_only` 三个开关在设置页就没有任何可见反馈。模式与第 7 格同构：`setOutSignalOnly(true)` → `onDraw` 里 `drawOutSignal(...)`，按 `HOST_ICON_HEIGHT_DP × density` 算出宿主高度、再按预览格缩放，所以两个环外格与状态栏保持同一比例。`dataSlot` 故意传 `-1`：哪个 SIM 是上网卡是设置页从未采样过的运行时状态，猜一个会把开关效果画在错卡上；传 `-1` 时 `dataSimOnly` 退回第一张有读数的卡。
- 八格一行需要 `PREVIEW_SIZE` 从 `46.dp` 再降到 `39.dp`（8×39 + 7×4 = 340dp < 344dp 内容宽）。降得动是因为不是 glyph 的那两格没有需要保住的固定纵横比。

### 6.5 未做的部分及理由

- **P1-1 键元数据表**：收益是真金白银（clamp 等 19 处手写、`applyKeyFrom` 的 25 分支 switch），但它会把 `Prefs`、`TrioSettings`、`SettingsRepository`、`SettingsScreen` 四层同时翻掉，且没有自动化测试兜底。改造当时的目标是「让用户更快调到想要的样式」，这一步属于开发者侧收益，风险与收益不成比例，缓做。
- **P1-2 行内原因**：与 `gateHint` 的 Tooltip 机制不冲突，是一段独立的展示改动；当前 Tooltip 已能表达原因，行内小字属于体验加分项而非功能缺失。
- **P2-2 写入路径收敛**：同上，属重构而非本次目标。

### 6.6 验证方式

仓库**没有任何自动化测试**（`app/src` 下只有 `main`），所以验证分两层：

1. **构建**：`gradle -p C:\code\HyperDuo :app:assembleDebug --offline -q` 通过。
2. **五条离线工装**（不入库，`work\` 下）：`gapcheck`（电量弧算术）、`slotcheck`（居中槽位优先级，用「非居中布局逐像素不变」当不变量）、`dualsimcheck`（双卡判定规则，故意把 `charging` 换回 `bolt` 造一份反例构建）、`simcheck`（订阅号↔槽位映射、单排兜底、**以及读的是 MIUI 电平而不是 AOSP 电平**；三构建对照）、`outringcheck`（环内/环外与两个新开关的真值表 + 环内逐像素不变）。五条**全部 `exit 0`**，即固定版通过、反例版按预期失败。改动渲染器或 `TrioAppearance` 后这些都要重跑，机制记录在 `docs\DEVELOPMENT.md:805-862` 与「测试」一节。
3. **与参考图的数值化比对**（`work\outringcheck\OutSignalShot` + `compare.py`，同样不入库）：`OutRingProbe` 只能证明源码自洽（两边引用同一批常量，同时错也通过），所以另有出图工装把环外读数按**参考图 1 单位 = 1 像素**画出来，再由 `compare.py` 用**同一套量法分别量参考图与渲染结果**，逐项比列数、列距、柱宽、四根柱高、点行直径与柱底到点行的空带。它当场抓到 `STACK_DOT_GAP` 原写 10、实为 **9**（参考图无抗锯齿：柱底末行墨 237 即下边缘 238，点行首行墨 247，空带 `238..246`），`STACK_INK_H` 因此由 210 修为 209。

**唯一一处有意的行为变化**：矩形样式下「电量数字居中」由「静默无效」改为**不可用并给出提示**（`enabled = a.glyph && !a.rect && a.wifi && a.value`，提示链含 `!a.rect to trio_style_ring`）。这是 2.3 那条矛盾的正解：以前矩形下这个开关点得动但什么也不发生，现在它明说自己是圆环专属。

### 6.7 上机后由用户发现的三个问题

落地版装到真机（houji / 小米 14，Android 17）之后，用户报了三个问题，都是这次改造自己引入的，记录如下。

**一、「三合一样式」摘要里出现了「或参考图那样的矩形」（用户原话：怎么现在会写一个"或参考图那样的矩形"？？？？？能不能好好写了）**

- 根因：**「参考图」是设计对齐术语**，只在 `docs/DEVELOPMENT.md` 里跟 MIUI 参考截图逐项比对时才有意义（`docs/DEVELOPMENT.md:388-389`、`:477-478`、`:685`、`:928`、`:1139`、`:1202`）。它被写进了用户可见的摘要，而设置页里根本没有那张图，读起来就像一个没写完的占位符。
- 全仓库扫描后确认**只有这一处用户可见字符串**犯这个毛病（`app/src/main/res/values/strings.xml:21`）。
- 改为照实描述实码行为（依据 `app/src/main/java/io/github/yixing233/hyperduo/TrioRenderer.java:218-224` 的 javadoc、`:232-257 drawRectLayout`、`:303 drawRectBatteryBar`）：
  - `values/strings.xml:21`：「选择三合一的排布方式。圆环是默认样式；矩形改用左右两列信号点夹住中间内容，底部横贯一条电量条。」
  - `values-en/strings.xml:21`：`Which arrangement to draw. The ring is the default; the rectangular one puts a column of signal dots on each side of the centre and a battery bar across the bottom.`
- **教训**：文档里的设计对齐词汇（参考图、提案编号、一期/二期）不得进入 `strings.xml`。

**二、「充电时显示闪电」关掉后无法再打开（用户原话：为何现在的充电时显示闪电的条目无法开启了）**

- 根因：`SettingsScreen.kt` 里该行落成了 `enabled = a.glyph && a.drawsBolt()`，而 `TrioAppearance.drawsBolt()` 是 `boltWanted && value` —— **它折入了这一行自己的开关值**。于是关掉该行 → `drawsBolt()` 为 false → 该行被禁用 → 永远回不来。改造前它挂的是外部开关 `showValue`，本来是对的；落地时还配了一段注释把错误合理化（「行的门禁应跟随规则而非复述规则」），这段注释也一并删掉。
- 修复：提示链 `gateHint(a.glyph to master_title, a.value to show_value_title)`、`enabled = a.glyph && a.value`。
- **规则（写进注释）**：一行可以被**别的**开关门禁，但**永远不能被自己门禁**。推导门禁时若用的是把本行开关值折进去的判定函数（如 `drawsBolt()`），就会自锁；应当回溯到它所依赖的**上游**开关（这里是「显示电量数字」）。
- 全行审计：闪电行是**唯一**的自锁。其余门禁都挂在别的开关上——showWifi/showMobile/roleColors = `a.glyph`；双卡 = `a.glyph && a.mobile`（本行是 `dualSim`）；居中 = `a.glyph && !a.rect && a.wifi && a.value`（本行是 `valueCentred`）；字重 = `a.glyph && a.value`；typeSize/outTypeSize/typeWeight = `a.typeInRing`/`a.typeOutOfRing`/`a.typeAnywhere()`（本行分别是这三个网络的类型项，`typeAnywhere` 是它们的或，故不会自锁：关掉全部三个才会禁用本行，而那正是「三个都关」的状态，不是自锁）。
- **上机证据**（`192.168.1.148:44453`）：`uiautomator dump` 取到该行开关 `bounds="[963,1756][1110,1900]"`，节点 `enabled="true" clickable="true"`。`adb shell su -c "input tap 1036 1828"` 之后：
  - `show_bolt` 由 `true` 变 `false`；
  - 该节点变为 `checked="false"` 而**仍是 `clickable="true" enabled="true"`** —— 关闭后这一行依然可用，自锁消除。旧门禁下这里会是 `clickable="false" enabled="false"`。
- **取证陷阱**：不能用「预置 `show_bolt=false` 再启动 app」来验证——`SettingsRepository` 会在启动时全量同步并把 pref 回写成 `true`。必须走 UI 点击。本条与本改造同属「验证方法」教训。

**三、双卡时上下两排各少一格（用户原话：为啥 我单卡的时候底部那个信号是满格的,但是双卡显示的时候上下两个都是少一格信号啊?啥bug）**

- 根因：双排的每卡电平取自 `TrioState.levelOf` 的 `SignalStrength.getLevel()`（**AOSP 口径**），而 MIUI 状态栏选 `stat_sys_signal_N` 用的是 `getMiuiLevel()`（**MIUI 扩展**）。实机 `dumpsys telephony.registry` 一行里两个字段并存且不等：Xiaomi 14 / 5G NR 下两张卡都是 **`miuiLevel = 4`、`level = 3`**，系统画四格满格，双排照 AOSP 取就矮一格。单卡时那一排走的是图标链兜底（直接拿系统图标已解析的档位），所以看着是满格——两条路径口径不一致才是这个 bug 的形状。
- jadx 佐证 MIUI 自己的取法：`work\jadx-out\sources\com\android\systemui\statusbar\connectivity\MobileSignalController.java:495` = `miuiLevel = signalStrength2.getMiuiLevel();`（`:504` `mobileState2.level = miuiLevel;`）。
- 修复：`getMiuiLevel()` 不在公开 SDK（SDK 37 的 `android.jar` 里 `android.telephony.SignalStrength` 只有 `public int getLevel();`），所以新增 `TrioState.miuiLevel(SignalStrength)` 用 `Refl.callByName(strength, "getMiuiLevel")` 反射取，非 `Number` 才退回 `strength.getLevel()`；0..4 之外的拒绝规则不变。
- 防回归：`work\simcheck\verify.ps1` 升为**三构建**，第三个反例把 `final int level = miuiLevel(strength);` 换回 `strength.getLevel();`。固定版 `exit 0`（47 条），该反例 `exit 1` 且**恰好 4 条**不符（正是那 4 条 MIUI 断言）。
- **教训**：凡是「跟着系统状态栏读数」的地方，必须抄**系统实际用的那套口径**，不能照公开 SDK 的等价方法想当然——MIUI 有大量 `getMiuiXxx()` 扩展，公开 API 只是它的子集。
