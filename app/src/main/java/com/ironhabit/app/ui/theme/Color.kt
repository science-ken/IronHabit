package com.ironhabit.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.ironhabit.app.domain.model.Habit

/**
 * IronHabit M3 色板（浅色 / 深色两套）。
 *
 * ### 2026-09-19 配色改版：暖色全部退场
 * 之前是「暗金主色 + 暖灰中性阶」，中性阶每一档都带黄棕底，整屏读起来发闷、像蒙了一层纸。
 * 现在换成**真中性灰 + 单一强调色**，与交互原型 `prototype.html` 一致：
 *
 * - **中性阶**：纯灰，不含任何色相偏移（旧 WarmGray* / 暖 Neutral* 全部作废）。
 * - **primary = 深青 `#0B6E5B`**：行动 / 进行中 / 勾选 / 进度。对白底 **6.18:1**。
 * - **tertiary = 暗金 `#775A00`**：成就 / 连续 / 纪录。对白底 **6.47:1**。
 *   两个强调色刻意保留分工 —— 合成一个就会重演旧版「打卡成功与校验失败同色」的语义撞车。
 * - **error 不动**：`#BA1A1A` 是语义锚点，与青（hue 168）相距 143°、与金（hue 88）相距 63°。
 *
 * ⚠️ 本文件是**唯一**允许出现 `Color(0xFF...)` 字面量的位置（架构 §7.5）：
 * 组件内一律通过 `MaterialTheme.colorScheme.*` 取值，不得硬编码色值。
 *
 * 所有正文级组合的 WCAG 对比度均 ≥ 4.5:1（AA），逐条实测见下方注释。
 */

// =============================================================================
// 一、中性 Tonal Palette —— 纯灰，页面 / 容器 / 描边 / 正文全靠它
// =============================================================================
// 命名沿用 M3 tone 编号。与旧 WarmGray* 的最大区别：R/G/B 三者相等，不再偏黄。
private val Neutral0 = Color(0xFF000000)
private val Neutral4 = Color(0xFF0A0A0A)
private val Neutral6 = Color(0xFF111111)
private val Neutral10 = Color(0xFF141414)
private val Neutral12 = Color(0xFF1D1D1D)
private val Neutral17 = Color(0xFF2B2B2B)
private val Neutral20 = Color(0xFF333333)
private val Neutral22 = Color(0xFF3A3A3A)
private val Neutral24 = Color(0xFF3E3E3E)
private val Neutral30 = Color(0xFF4A4A4A)
private val Neutral40 = Color(0xFF6B6B6B)
private val Neutral50 = Color(0xFF8A8A8A)
private val Neutral60 = Color(0xFFA3A3A3)
private val Neutral70 = Color(0xFFBDBDBD)
private val Neutral80 = Color(0xFFDCDCDC)
private val Neutral87 = Color(0xFFE8E8E8)
private val Neutral90 = Color(0xFFE4E4E4)
private val Neutral95 = Color(0xFFF0F0F0)
private val Neutral96 = Color(0xFFF5F5F5)
private val Neutral98 = Color(0xFFFAFAFA)
private val Neutral99 = Color(0xFFFFFFFF)
private val Neutral100 = Color(0xFFFFFFFF)

// =============================================================================
// 二、强调色 —— 深青（primary：行动 / 勾选 / 进度）
// =============================================================================
private val Teal10 = Color(0xFF00201C)
private val Teal20 = Color(0xFF003732)
private val Teal30 = Color(0xFF005048)
private val Teal40 = Color(0xFF0B6E5B)
private val Teal70 = Color(0xFF68B9AD)
private val Teal80 = Color(0xFF84D5C8)
private val Teal90 = Color(0xFFD7EDE7)
private val Teal95 = Color(0xFFB4FFF2)

// =============================================================================
// 六、青的**中间明度档** —— 热力图密度表与饼图分类色共用这几档
// =============================================================================
// 这几档以前只以裸 hex 的形式活在 `HeatmapLevels*` 那两张表里。饼图要复用同一条
// 明度阶梯就得把 hex 再打一遍，于是同一支色出现两处真相 —— 正是本项目反复踩的
// "两边各写一份迟早漂"。提到这里之后，两张表都从同一批 val 组装。
private val TealMid56 = Color(0xFF3A9A80)
private val TealMid76 = Color(0xFF6FBFA9)
private val TealDeep52 = Color(0xFF2F6E60)
private val TealMid64 = Color(0xFF479985)

// =============================================================================
// 三、辅助强调色 —— 暗金（tertiary：成就 / 连续 / 纪录）
// =============================================================================
private val Gold10 = Color(0xFF251A00)
private val Gold20 = Color(0xFF3F2E00)
private val Gold30 = Color(0xFF5A4300)
private val Gold40 = Color(0xFF775A00)
private val Gold80 = Color(0xFFE9C266)
private val Gold90 = Color(0xFFFFDF99)

// =============================================================================
// 四、语义色：错误（红色保持不变 —— 它是语义锚点）
// =============================================================================
private val ErrorLight = Color(0xFFBA1A1A)
private val OnErrorLight = Color(0xFFFFFFFF)
private val ErrorContainerLight = Color(0xFFFFDAD6)
private val OnErrorContainerLight = Color(0xFF410002)
private val ErrorDark = Color(0xFFFFB4AB)
private val OnErrorDark = Color(0xFF690005)
private val ErrorContainerDark = Color(0xFF93000A)
private val OnErrorContainerDark = Color(0xFFFFDAD6)

// =============================================================================
// 五、习惯「主题色」预设板
// =============================================================================
/**
 * 习惯色板的六个预设色，从 `AddEditHabitScreen` 挪进来（架构 §7.5：颜色只在本文件决定）。
 *
 * ⚠️ 它们是 `String` 而不是 `Color`，因为要原样写进 `habits.color_hex` 并随备份往返 ——
 * **改一个已有条目等于改数据**，老库里那条习惯会指向一个不再存在的颜色。只能往末尾追加。
 *
 * ⚠️ 深色模式的真实短板和审查报告说的**相反**。报告称 `#009688`/`#2196F3` 压深底不足，
 * 实测底色是页面背景（圆点外面没有卡片）：对浅底 `#FFFFFF` / 对深底 `#111111`，非文本要求 3:1 ——
 * 蓝 3.12/6.04、绿 2.78/6.79、橙 2.16/8.76、粉 4.35/4.34、紫 6.30/2.99（深底这条不合格）、青 3.67/5.14。
 * 即深底上六个里只有紫色差一点，而浅底上橙、绿反而都不够 —— 报告把方向搞反了。
 * 治法是给圆点补一圈 `outline` 描边（见 `HabitColorPicker`），不是改 hex。
 *
 * 首项即新建习惯的默认色，直接取域层的 `Habit.DEFAULT_COLOR_HEX`（**唯一字面量**在那边，
 * 这里再写一个就是走查 #9 那种"两边各写一份迟早漂"）。
 */
val HABIT_COLOR_HEXES: List<String> = listOf(
    Habit.DEFAULT_COLOR_HEX,
    "#4CAF50",
    "#FF9800",
    "#E91E63",
    "#9C27B0",
    "#009688",
)

/** 浅色主题配色。 */
val IronHabitLightColorScheme = lightColorScheme(
    // 主色：深青（行动 / 勾选 / 进度）
    primary = Teal40,
    onPrimary = Neutral100,
    primaryContainer = Teal90,
    onPrimaryContainer = Teal20,
    inversePrimary = Teal80,

    // 辅助色：中性灰（芯片、次要按钮）
    secondary = Neutral40,
    onSecondary = Neutral100,
    secondaryContainer = Neutral95,
    onSecondaryContainer = Neutral12,

    // 强调色：暗金（成就 / 连续 / 纪录）
    tertiary = Gold40,
    onTertiary = Neutral100,
    tertiaryContainer = Gold90,
    onTertiaryContainer = Gold10,

    // 语义色：错误
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,

    // 页面与正文：18.42:1 / 5.33:1，均过 AA
    background = Neutral99,
    onBackground = Neutral10,
    surface = Neutral99,
    onSurface = Neutral10,
    surfaceVariant = Neutral90,
    onSurfaceVariant = Neutral40,

    // M3 容器阶梯：页面 → 卡片 → 浮层，逐级抬升（磁贴取 High，压暗的 hero 取 Highest）
    surfaceContainerLowest = Neutral100,
    surfaceContainerLow = Neutral98,
    surfaceContainer = Neutral96,
    surfaceContainerHigh = Neutral95,
    // 以前是 Neutral80（#DCDCDC）：从 High 的 #F0F0F0 一跳掉 3 档，今日页那块 hero 磁贴
    // 像被泼了块灰泥，压过了本该最醒目的 streak 数字。改成只深一档。
    // ⚠️ 连带影响：`TodayBento` 的进度条轨道也用这个 token，于是轨道也跟着变浅
    // （对磁贴底 1.20:1 → 1.12:1）。轨道本来就该安静、 filled 段是 primary，所以可接受。
    surfaceContainerHighest = Neutral90,
    surfaceDim = Neutral87,
    surfaceBright = Neutral98,

    // 描边与分隔线
    outline = Neutral50,
    outlineVariant = Neutral80,

    // 反色（Snackbar 等）
    inverseSurface = Neutral20,
    inverseOnSurface = Neutral98,
)

/** 深色主题配色：同一套中性阶翻转到暗底，强调色提亮到 tone 80。 */
val IronHabitDarkColorScheme = darkColorScheme(
    primary = Teal80,
    onPrimary = Teal20,
    primaryContainer = Teal30,
    onPrimaryContainer = Teal90,
    inversePrimary = Teal40,

    secondary = Neutral80,
    onSecondary = Neutral20,
    secondaryContainer = Neutral30,
    onSecondaryContainer = Neutral90,

    tertiary = Gold80,
    onTertiary = Gold20,
    tertiaryContainer = Gold30,
    onTertiaryContainer = Gold90,

    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,

    background = Neutral6,
    onBackground = Neutral90,
    surface = Neutral6,
    onSurface = Neutral90,
    surfaceVariant = Neutral30,
    onSurfaceVariant = Neutral70,

    surfaceContainerLowest = Neutral4,
    surfaceContainerLow = Neutral6,
    surfaceContainer = Neutral10,
    surfaceContainerHigh = Neutral12,
    surfaceContainerHighest = Neutral17,
    surfaceDim = Neutral6,
    surfaceBright = Neutral24,

    outline = Neutral60,
    outlineVariant = Neutral30,

    inverseSurface = Neutral90,
    inverseOnSurface = Neutral20,
)

/**
 * 热力图密度色阶 · 浅色（索引 = `HeatmapCell.level`，`0` = 当天没有打卡）。
 *
 * ⚠️ **不要用线性插值代替这张表。** 之前 `cellColor` 是在 `surfaceVariant → primary`
 * 之间按 `level / 4` 插值，结果 1 档只比 0 档深一点点（实测对磁贴底只有 1.13:1 与 1.27:1），
 * 肉眼上"有打卡"和"没打卡"几乎一样 —— 热力图就白画了。
 * 这里把相邻档的间距手动拉开：0→1 是最关键的一跳，而它靠的是**色相**（中性灰 → 青），
 * 不是明度 —— 明度比只有 1.37 与 1.56，谁按数字去"调对比度"都会把它调回一坨。
 *
 * 底色是**页面背景 `background`（浅底 `#FFFFFF`）**，不是磁贴 —— `HeatmapGrid` 直接铺在
 * 自律页/历史页的 Column 上，外层没有任何 Card（以前这张表按 `surfaceContainerHigh` 算，
 * 底选错了，数字全部偏低）。对背景的对比度依次 1.37 / 1.56 / 2.16 / 3.43 / 6.18。
 *
 * ⚠️ 审查报告 2.3 建议把 0 档再压深成 `#C9C9C9`（"空格几乎融进背景"）。**不采纳，因为它会倒序**：
 * `#C9C9C9` 对白底是 1.66:1，比 1 档的 1.56:1 还显眼 —— 第一跳（0→1，整张表最关键的一跳）
 * 直接反向，"没练"会比"练了一次"更抢眼。0 档要更响只能整表重排，不是改一格。
 */
private val HeatmapLevelsLight: List<Color> = listOf(
    Neutral80,        // 0 无数据：安静，但要能看出是一格
    Color(0xFFA7D9CC), // 1
    TealMid76,        // 2
    TealMid56,        // 3
    Teal40,           // 4 高密度：与浅色 primary 同色
)

/**
 * 热力图密度色阶 · 深色。**方向与浅色相反：越练越亮。**
 *
 * 深色底上"更深的颜色"等于"更看不见"，所以直接复用浅色那张表会把明暗关系整个倒过来 ——
 * 实测浅色的 0 档 `#DCDCDC` 对深底是 13.77:1（最扎眼），而 4 档 `#0B6E5B` 只有 3.05:1，
 * 空格子在喊、满格子在 whisper。
 *
 * 对深底 `background #111111` 的对比度依次 1.43 / 3.17 / 5.54 / 8.72 / 11.09，
 * 明度单调递增；4 档取深色 `primary`。
 */
private val HeatmapLevelsDark: List<Color> = listOf(
    Color(0xFF303030), // 0 无数据
    TealDeep52,       // 1
    TealMid64,        // 2
    TealMid76,        // 3
    Teal80,           // 4 高密度：与深色 primary 同色
)

/** 当前主题下的热力密度色阶。分主题是因为深浅底上"深=显眼"的直觉是反的。 */
@Composable
fun heatmapLevels(): List<Color> =
    if (LocalIsDarkTheme.current) HeatmapLevelsDark else HeatmapLevelsLight

// =============================================================================
// 八、把**用户存的**色值解析成 Color —— 全工程唯一一处
// =============================================================================

/** `#RRGGBB` 的形状。只接受这一种：库里存的就是选色器写进去的 6 位十六进制。 */
private val HABIT_HEX: Regex = Regex("#([0-9a-fA-F]{6})")

/**
 * 习惯的 `color_hex`（用户数据）→ 可渲染的 [Color]；**形状不对就回落到默认色，不抛**。
 *
 * 为什么不用 `android.graphics.Color.parseColor()`：
 * 1. 它在非法输入上抛 `IllegalArgumentException`。而导入路径**不校验这个字段**
 *    （`BackupRepositoryImpl` 的 `HabitBackup.toEntity` 把 `colorHex` 原样透传），
 *    所以一份手改过的备份 JSON 就能让自律页与今日页整页崩，而不是只在编辑表单里崩一下；
 * 2. 它是 Android 框架 API，纯 Kotlin 的写法才能被 JVM 单测钉住（见 `HabitColorTest`）。
 *
 * 本文件是"颜色只在 Color.kt 决定"这条规矩的例外窗口：这里的输入不是设计色，
 * 是**存在库里的用户数据**，总得有个地方把它变成像素 —— 那就只有这一个地方。
 */
fun habitColor(hex: String): Color {
    val bits: Long = hex.rgbBits() ?: Habit.DEFAULT_COLOR_HEX.rgbBits() ?: 0L
    return Color(0xFF000000L or bits)
}

/** `#RRGGBB` → 低 24 位颜色整数；形状不对返回 `null`，由调用方决定回落成什么。 */
private fun String.rgbBits(): Long? =
    HABIT_HEX.matchEntire(this)?.groupValues?.get(1)?.toLongOrNull(16)

// =============================================================================
// 七、饼图分类色阶 —— 四档**同色系明度阶梯**
// =============================================================================
// 以前这里取的是 `primary + secondary + tertiary + primaryContainer`，也就是
// 「青 + 中灰 + 暗金 + 淡青」四支不同维度的色。两个问题：
//
// 1. **相邻两块分不出来**。环形图现在坐在 `surfaceContainerHigh` 磁贴上
//    （浅 `#F0F0F0` / 深 `#1D1D1D`），实测相邻档对比度只有 **1.16**（青↔灰）与
//    **1.21**（灰↔金）—— 远低于"同一维度里两个类别"该有的落差，
//    两块楔形只能靠色相分，而色相正是最不可靠的那一维。
// 2. **第 4 档几乎融进卡片**。`primaryContainer` 淡青对磁贴底只有 **1.07**，
//    等于那一个分类没画。
//
// 换成青的明度阶梯（与热力图共用同一批中间档）后：
//    浅色 相邻 2.13 / 1.80 / 1.59，首尾 6.09，最浅一档对磁贴底 **1.90**
//    深色 相邻 1.50 / 2.00 / 1.75，首尾 5.26，最暗一档对磁贴底 **2.83**
// 最小相邻落差从 1.16 提到 1.50 以上。
//
// ⚠️ 下标 = `ExerciseCategory.ordinal`（声明序：自重 / 力量 / 有氧 / 自定义），
// **不是**列表里的位置 —— `StatsDao.categoryShareRows()` 只有 `GROUP BY` 没有 `ORDER BY`，
// 按位置取色会让同一个分类每次刷新换一个颜色。
// 四档**不占用任何语义色**：`error` 红是"出错了"，`tertiary` 金是"成就/连续"，
// 都不该拿来表示"这是有氧还是自重"（审查报告 2.3 那条 C5 的原始理由）。
private val PieSliceLevelsLight: List<Color> = listOf(
    Teal20,     // 自重：最深一档
    Teal40,     // 力量：与 primary 同色 —— 这两类最常见，放在落差最大的一对相邻档上
    TealMid56,  // 有氧
    TealMid76,  // 自定义：最浅一档，对磁贴底仍有 1.90
)

private val PieSliceLevelsDark: List<Color> = listOf(
    Teal95,     // 自重：最亮
    Teal80,     // 力量：与深色 primary 同色
    TealMid64,  // 有氧
    TealDeep52, // 自定义：最暗一档，对磁贴底 2.83
)

/** 当前主题下的饼图四档色阶（图例点与扇区取同一份，不会两边各算一次）。 */
@Composable
fun pieSliceLevels(): List<Color> =
    if (LocalIsDarkTheme.current) PieSliceLevelsDark else PieSliceLevelsLight
