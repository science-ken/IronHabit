package com.ironhabit.app.domain.model

/**
 * 位宽口径的组数上限（= `CheckIn.kt` 里同包顶层常量 `MAX_SETS` 的 `31`）。
 *
 * 单独起名再桥进 [InputLimits] 的原因：对象成员 `InputLimits.MAX_SETS` 在对象体内**遮蔽**
 * 同包顶层 `MAX_SETS`，直接写 `val MAX_SETS: Int = MAX_SETS` 就是自引用。
 */
private const val CHECK_IN_MAX_SETS: Int = MAX_SETS

/**
 * 用户输入数值的**唯一真源**（域层常量 + 纯函数）。
 *
 * 背景（BUG 修复）：远端 AI 路径对自己的输出有钳制（`targetSets` → `1..50`、
 * `targetReps` → `1..100`），而本地表单此前「能 parse 就收」——`sets = 0`、负次数、
 * `weight = -10`、`duration = 0`、荒唐的身体数据全都能落库。两条入口的校验强度必须
 * **对称**，故把边界集中到本文件，由三个表单 ViewModel 共同引用。
 *
 * 设计约束：
 * - **零依赖、纯函数**：只依赖 [BodyMetricType] 与 `kotlin` 标准库 → 可直接 JVM 单测；
 * - **绝不抛异常**：域/数据层对越界用户输入一律「静默忽略或钳制」（真机崩溃比一次错点
 *   严重得多）。故只提供 `isValid*`（判定）与 `coerce*`（钳制），**没有** `require*`；
 * - 单位口径写进常量名（`_KG` / `_MIN` / `_SEC` / `_PERCENT` / `_CM`），避免再次混淆
 *   「动作默认时长用秒、计划目标时长用分钟」这两种并存的口径。
 *
 * 注意：组数上限取 [MAX_SETS]（= 31）而非经验值，因为逐组勾选的唯一真源是
 * `completed_sets_mask` 这个 `Int` 位图：目标组数一旦超过 31，第 32 组在 mask 里
 * **无处可放**，用户永远勾不满。远端 AI 路径的 `1..50` 是它自己的防线，不在本文件范围。
 */
object InputLimits {

    // ================= 组数 =================

    /** 组数下限：`0` 组没有训练意义，是明显的误输（例如漏打数字）。 */
    const val MIN_SETS: Int = 1

    /** 组数上限 = 打卡位图 `MAX_SETS`（`Int` 位宽 - 1，避开符号位）= `31`。 */
    val MAX_SETS: Int = CHECK_IN_MAX_SETS

    // ================= 每组次数 =================

    /** 次数下限：与 AI 路径的 `MIN_REPS` 对齐。 */
    const val MIN_REPS: Int = 1

    /** 次数上限：与 AI 路径的 `MAX_REPS` 对齐（`100` 次/组之上已属误输）。 */
    const val MAX_REPS: Int = 100

    // ================= 负重（kg） =================

    /** 重量下限 `0`：自重动作（俯卧撑等）的合法口径就是 `0 kg`，不是负数。 */
    const val MIN_WEIGHT_KG: Float = 0f

    /**
     * 重量上限 `500 kg`：覆盖杠铃/腿举的极端负荷（世界级腿举 > 400 kg），
     * 超出基本是「磅当公斤」或多打了零。
     */
    const val MAX_WEIGHT_KG: Float = 500f

    // ================= 时长（分钟：计划目标 / 打卡口径） =================

    /** 时长下限（分钟）：`0` 分钟无意义。 */
    const val MIN_DURATION_MIN: Int = 1

    /** 时长上限（分钟）：`600` = 10 小时，单次训练超过它必是误输。 */
    const val MAX_DURATION_MIN: Int = 600

    // ================= 时长（秒：动作默认值口径） =================

    /** 时长下限（秒）。 */
    const val MIN_DURATION_SEC: Int = 1

    /**
     * 时长上限（秒）：`7200` = 2 小时。
     *
     * 必须不小于内置有氧动作的最大默认值（`2400` 秒 = 40 分钟），否则**编辑内置动作**
     * 会被自己的校验拦下。
     */
    const val MAX_DURATION_SEC: Int = 7_200

    // ================= 身体数据（按指标类型） =================

    /**
     * 体重 `20..400 kg`：覆盖成人两端极端体型（含极轻体重与病态肥胖），
     * 下限之下多半是「只输了小数部分」，上限之上多半是「磅/斤当成了公斤」。
     */
    const val MIN_BODY_WEIGHT_KG: Float = 20f
    const val MAX_BODY_WEIGHT_KG: Float = 400f

    /**
     * 体脂率 `3..70 %`：`3%` 是运动员维持生理功能的必需脂肪下限，
     * `70%` 已接近临床可存活上限 —— 之外的值（含把 `0.2` 当 20% 输错）一律拒绝。
     */
    const val MIN_BODY_FAT_PERCENT: Float = 3f
    const val MAX_BODY_FAT_PERCENT: Float = 70f

    /**
     * 骨骼肌量 `5..100 kg`：成年女性骨骼肌量下限约 10 kg、男性上限约 50 kg，
     * 取 `5..100` 留足余量；超界多为「把体重填进了肌肉量」。
     */
    const val MIN_MUSCLE_MASS_KG: Float = 5f
    const val MAX_MUSCLE_MASS_KG: Float = 100f

    /**
     * 围度（胸/腰/臀/臂）`20..300 cm`：下限覆盖儿童臂围，上限覆盖极端体型腰围；
     * 超界多为「毫米/米」单位误输。
     */
    const val MIN_GIRTH_CM: Float = 20f
    const val MAX_GIRTH_CM: Float = 300f

    // ================= 饮食（一餐） =================

    /**
     * 一餐热量 `0..5000 kcal`：下限 `0` 是合法值（例如只喝了水/黑咖啡），
     * 上限 `5000` 已远超单餐合理范围（一份炸鸡约 1200 kcal），超出多为漏打小数点。
     */
    const val MIN_MEAL_KCAL: Int = 0
    const val MAX_MEAL_KCAL: Int = 5_000

    /**
     * 一餐蛋白质 `0..500 g`：单餐 500 g 蛋白在任何真实食物组合下都不成立，
     * 超出多为单位误输（把 mg 当 g）。
     */
    const val MIN_MEAL_PROTEIN_G: Double = 0.0
    const val MAX_MEAL_PROTEIN_G: Double = 500.0

    /** 一餐热量是否在合法区间内。 */
    fun isValidMealKcal(value: Int): Boolean = value in MIN_MEAL_KCAL..MAX_MEAL_KCAL

    /** 一餐蛋白质是否在合法区间内（`NaN` / `±Infinity` 一律非法）。 */
    fun isValidMealProteinG(value: Double): Boolean =
        value.isFinite() && value >= MIN_MEAL_PROTEIN_G && value <= MAX_MEAL_PROTEIN_G

    // ================= 组数 =================

    /** 组数是否在合法区间内。 */
    fun isValidSets(value: Int): Boolean = value in MIN_SETS..MAX_SETS

    /** 组数钳制到 `MIN_SETS..MAX_SETS`（**不抛异常**）。 */
    fun coerceSets(value: Int): Int = value.coerceIn(MIN_SETS, MAX_SETS)

    // ================= 次数 =================

    /** 每组次数是否在合法区间内。 */
    fun isValidReps(value: Int): Boolean = value in MIN_REPS..MAX_REPS

    /** 每组次数钳制到 `MIN_REPS..MAX_REPS`（**不抛异常**）。 */
    fun coerceReps(value: Int): Int = value.coerceIn(MIN_REPS, MAX_REPS)

    // ================= 负重 =================

    /**
     * 重量是否在合法区间内。
     *
     * `NaN` / `±Infinity` 一律判为非法（`NaN` 与任何值比较都为 `false`，若不显式拦截，
     * 它会一路穿过 `coerceIn` 写进库里）。
     */
    fun isValidWeightKg(value: Float): Boolean =
        value.isFinite() && value >= MIN_WEIGHT_KG && value <= MAX_WEIGHT_KG

    /** 重量钳制到 `MIN_WEIGHT_KG..MAX_WEIGHT_KG`；非有限值取下界（**不抛异常**）。 */
    fun coerceWeightKg(value: Float): Float = coerceInto(value, MIN_WEIGHT_KG, MAX_WEIGHT_KG)

    // ================= 时长（分钟） =================

    /** 计划目标 / 打卡时长（分钟）是否在合法区间内。 */
    fun isValidDurationMin(value: Int): Boolean = value in MIN_DURATION_MIN..MAX_DURATION_MIN

    /** 时长（分钟）钳制到 `MIN_DURATION_MIN..MAX_DURATION_MIN`（**不抛异常**）。 */
    fun coerceDurationMin(value: Int): Int = value.coerceIn(MIN_DURATION_MIN, MAX_DURATION_MIN)

    // ================= 时长（秒） =================

    /** 动作默认时长（秒）是否在合法区间内。 */
    fun isValidDurationSec(value: Int): Boolean = value in MIN_DURATION_SEC..MAX_DURATION_SEC

    /** 时长（秒）钳制到 `MIN_DURATION_SEC..MAX_DURATION_SEC`（**不抛异常**）。 */
    fun coerceDurationSec(value: Int): Int = value.coerceIn(MIN_DURATION_SEC, MAX_DURATION_SEC)

    // ================= 身体数据 =================

    /**
     * 某指标类型的合法取值区间（闭区间）。
     *
     * 单位随类型而变（`kg` / `%` / `cm`），见各常量 KDoc；UI 的输入单位文案目前是自由文本，
     * 而本函数是**按类型**判定的唯一真源 —— 单位文本不参与校验。
     */
    fun rangeFor(type: BodyMetricType): ClosedFloatingPointRange<Float> = when (type) {
        BodyMetricType.WEIGHT -> MIN_BODY_WEIGHT_KG..MAX_BODY_WEIGHT_KG
        BodyMetricType.BODY_FAT -> MIN_BODY_FAT_PERCENT..MAX_BODY_FAT_PERCENT
        BodyMetricType.MUSCLE_MASS -> MIN_MUSCLE_MASS_KG..MAX_MUSCLE_MASS_KG
        BodyMetricType.WAIST -> MIN_GIRTH_CM..MAX_GIRTH_CM
        BodyMetricType.CHEST -> MIN_GIRTH_CM..MAX_GIRTH_CM
        BodyMetricType.ARM -> MIN_GIRTH_CM..MAX_GIRTH_CM
        BodyMetricType.HIP -> MIN_GIRTH_CM..MAX_GIRTH_CM
    }

    /** 身体数据是否落在其指标类型的区间内（`NaN` / `±Infinity` 一律非法）。 */
    fun isValidBodyMetric(type: BodyMetricType, value: Float): Boolean =
        value.isFinite() && value in rangeFor(type)

    /**
     * 身体数据钳制到其指标类型的区间；非有限值取下界（**不抛异常**）。
     *
     * 表单走「拒绝 + 提示」而非钳制（见 `BodyMetricsViewModel.onAddRecord`），
     * 本函数供导入 / 迁移等**无交互**路径兜底。
     */
    fun coerceBodyMetric(type: BodyMetricType, value: Float): Float =
        rangeFor(type).let { range -> coerceInto(value, range.start, range.endInclusive) }

    /**
     * 浮点钳制（**不抛异常**）。
     *
     * 刻意不用 `Float.coerceIn`：它只钳制有限值，`NaN` 会**原样穿过**，
     * 从而把非法值带进库里。
     */
    private fun coerceInto(value: Float, min: Float, max: Float): Float = when {
        !value.isFinite() -> min
        value < min -> min
        value > max -> max
        else -> value
    }
}
