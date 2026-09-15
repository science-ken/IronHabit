package com.ironhabit.app.domain.model

/**
 * 生理性别：仅用于 BMR 公式的常数项（男 +5 / 女 −161）。
 */
enum class Gender {
    MALE,
    FEMALE,
}

/**
 * 健身目标（对齐预览 `GOALS` 的 5 项）。
 *
 * @property kcalFactor 目标热量系数（相对 TDEE），供饮食规则使用。
 */
enum class Goal(val kcalFactor: Double) {
    /** 减脂 */
    CUT(0.85),

    /** 增肌 */
    BULK(1.10),

    /** 减脂增肌（体重维持 + 高蛋白） */
    RECOMP(1.00),

    /** 塑形（轻微热量缺口） */
    SHAPE(0.95),

    /** 保持 */
    MAINTAIN(1.00),
}

/**
 * 可用器械（对齐预览 `EQUIP` 的 8 项）。
 *
 * 展示顺序即本枚举声明顺序（UI 不依赖 [Set] 迭代顺序）。
 */
enum class Equipment {
    /** 无器械（仅自重） */
    NONE,

    /** 哑铃 */
    DUMBBELL,

    /** 杠铃 */
    BARBELL,

    /** 瑜伽垫 */
    YOGA_MAT,

    /** 单杠 */
    PULLUP_BAR,

    /** 弹力带 */
    RESISTANCE_BAND,

    /** 器械区 */
    MACHINE,

    /** 跑步机 */
    TREADMILL,
}

/**
 * 伤病部位（结构化，供本地规则机械排除动作）。
 *
 * ⚠️ 预览把伤病存成纯自由文本，无法被规则可靠消费；本设计改为部位枚举多选（驱动规则）
 * + 可选自由文本备注（仅展示）。
 */
enum class InjuryArea {
    /** 膝 */
    KNEE,

    /** 腰 */
    LOWER_BACK,

    /** 肩 */
    SHOULDER,

    /** 腕 */
    WRIST,

    /** 肘 */
    ELBOW,

    /** 踝 */
    ANKLE,

    /** 颈 */
    NECK,

    /** 髋 */
    HIP,

    /** 心血管（含其它慢性病） */
    CARDIO,
}

/**
 * 饮食忌口（对齐预览 `AVOID` 的 6 项）。
 */
enum class DietRestriction {
    /** 花生 */
    PEANUT,

    /** 海鲜 */
    SEAFOOD,

    /** 乳制品 */
    DAIRY,

    /** 麸质 */
    GLUTEN,

    /** 辛辣 */
    SPICY,

    /** 酒精 */
    ALCOHOL,
}

/**
 * 用户档案（单人单份，存 `SettingsDataStore`，**不落 Room**）。
 *
 * ⚠️ **不含体重** —— 体重唯一真源是 `body_metrics`（`BodyMetricType.WEIGHT` 最新值），
 * 档案只做「展示 + 跳转」。
 *
 * 所有 `?` 字段为 `null` 表示「用户尚未填写」，是**合法状态**（非错误）；
 * 集合为空集即「未选」。多选集合无次序语义。
 *
 * @property gender 生理性别，未填为 `null`
 * @property age 年龄（岁），合法域 `14–100`（写入已钳制）
 * @property heightCm 身高（cm），合法域 `140–220`（写入已钳制）
 * @property bodyFatPct 体脂率（%），合法域 `3–60`（写入已钳制）；仅展示/参考
 * @property goal 健身目标，默认 [Goal.MAINTAIN]
 * @property goalWeightKg 目标体重（kg），合法域 `30–300`（写入已钳制）；仅展示/激励
 * @property equipment 可用器械集合，空集视为「{NONE}（仅自重）」
 * @property injuryAreas 伤病部位集合，空集 = 不排除任何动作
 * @property injuryNote 伤病备注（自由文本），`null` = 不显示；仅展示，规则不解析
 * @property dietaryAvoid 饮食忌口集合，空集 = 不排除任何食物
 */
data class UserProfile(
    val gender: Gender? = null,
    val age: Int? = null,
    val heightCm: Int? = null,
    val bodyFatPct: Float? = null,
    val goal: Goal = Goal.MAINTAIN,
    val goalWeightKg: Float? = null,
    val equipment: Set<Equipment> = emptySet(),
    val injuryAreas: Set<InjuryArea> = emptySet(),
    val injuryNote: String? = null,
    val dietaryAvoid: Set<DietRestriction> = emptySet(),
) {

    /** 体征三件套是否填全（BMR 计算的前提；缺失走默认值兜底）。 */
    val isBodyProfileComplete: Boolean
        get() = gender != null && age != null && heightCm != null

    /** 训练档案是否可用：**至少勾选一项器械**（没有器械就选 [Equipment.NONE]）。 */
    val isTrainingProfileComplete: Boolean
        get() = equipment.isNotEmpty()

    /** 是否存在需要规则避让的约束（伤病 / 忌口）。 */
    val hasConstraints: Boolean
        get() = injuryAreas.isNotEmpty() || dietaryAvoid.isNotEmpty()
}

/**
 * 档案数值合法域 + 写入钳制 + 文本解析（**纯函数**，供 DataStore 与 UI 共用，可 JVM 单测）。
 *
 * 总原则：写入即 `coerceIn`，越界轻提示、不阻断、不崩；任何输入都不产出荒谬值。
 */
object ProfileLimits {

    /** 年龄合法域（岁）。 */
    val AGE: IntRange = 14..100

    /** 身高合法域（cm）。 */
    val HEIGHT_CM: IntRange = 140..220

    /** 体脂率合法域（%）。 */
    val BODY_FAT_PCT: ClosedFloatingPointRange<Float> = 3f..60f

    /** 目标体重合法域（kg）。 */
    val GOAL_WEIGHT_KG: ClosedFloatingPointRange<Float> = 30f..300f

    /** 伤病备注最大字数。 */
    const val INJURY_NOTE_MAX_LENGTH: Int = 200

    /** 年龄钳制到 `14–100`。 */
    fun coerceAge(value: Int): Int = value.coerceIn(AGE.first, AGE.last)

    /** 身高钳制到 `140–220`。 */
    fun coerceHeightCm(value: Int): Int = value.coerceIn(HEIGHT_CM.first, HEIGHT_CM.last)

    /** 体脂率钳制到 `3–60`。 */
    fun coerceBodyFatPct(value: Float): Float =
        value.coerceIn(BODY_FAT_PCT.start, BODY_FAT_PCT.endInclusive)

    /** 目标体重钳制到 `30–300`。 */
    fun coerceGoalWeightKg(value: Float): Float =
        value.coerceIn(GOAL_WEIGHT_KG.start, GOAL_WEIGHT_KG.endInclusive)

    /** 伤病备注截断到 [INJURY_NOTE_MAX_LENGTH] 字。 */
    fun coerceInjuryNote(note: String): String = note.take(INJURY_NOTE_MAX_LENGTH)

    /** 年龄是否在合法域内。 */
    fun isAgeInRange(value: Int): Boolean = value in AGE

    /** 身高是否在合法域内。 */
    fun isHeightInRange(value: Int): Boolean = value in HEIGHT_CM

    /** 体脂率是否在合法域内。 */
    fun isBodyFatInRange(value: Float): Boolean = value in BODY_FAT_PCT

    /** 目标体重是否在合法域内。 */
    fun isGoalWeightInRange(value: Float): Boolean = value in GOAL_WEIGHT_KG
}

/**
 * 文本 → `Int?`：空/纯空白或非数字一律返回 `null`（**不抛异常**）。
 */
fun parseOptionalInt(text: String): Int? =
    text.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()

/**
 * 文本 → `Float?`：空/纯空白或非数字一律返回 `null`（**不抛异常**）。
 */
fun parseOptionalFloat(text: String): Float? =
    text.trim().takeIf { it.isNotEmpty() }?.toFloatOrNull()

/**
 * 枚举集合 → `Set<String>`：存 `Enum.name`（**不存 ordinal**，新增/重排枚举值不会错位）。
 */
fun <T : Enum<T>> encodeEnumSet(values: Set<T>): Set<String> =
    values.mapTo(LinkedHashSet()) { it.name }

/**
 * `Set<String>` → 枚举集合：**未知 name 忽略并回落**（不崩）。
 */
inline fun <reified T : Enum<T>> decodeEnumSet(names: Set<String>): Set<T> =
    names.mapNotNullTo(LinkedHashSet()) { name ->
        enumValues<T>().firstOrNull { it.name == name }
    }

/**
 * `String?` → 枚举：**未知 name 返回 `null`**（不崩），由调用方决定回落默认值。
 */
inline fun <reified T : Enum<T>> decodeEnum(name: String?): T? =
    name?.let { raw -> enumValues<T>().firstOrNull { it.name == raw } }
