package com.ironhabit.app.domain.model

/**
 * 一条食物的**一种家用份量**（Q35 = B：一个食物可以有多套）。
 *
 * 为什么不是一列：`米饭` 的「一碗 200g」和「一盘 350g」差的是**一倍热量**，
 * 只留一份就意味着每顿都要手动改克数 —— 而那一步正是"记两天就不记了"的起点。
 *
 * @param unit 单位名："碗 / 盘 / 个 / 份 / 勺"。
 * @param grams 该单位等于多少克（**生熟口径直接写进这一行**，不做公式换算：
 *   中式成分表按可食部生重计，而用户记的是盘子里那坨，两者只能靠"份"对齐）。
 */
data class FoodServing(
    val id: Long = 0L,
    val unit: String,
    val grams: Int,
    val sortOrder: Int = 0,
)

/**
 * 食物来源（食物库）。
 *
 * 与 [ExerciseSource] **刻意分开**：动作侧早就是三态（AI 可以直接产出一个新动作），
 * 而食物侧到 2026-09-26 之前只有两态 —— 旧规则是「食物侧 AI 只给建议、不产生库条目」。
 * 外部 AI 饮食导入（刀 4）推翻了那条：库里没有的食物现在能在导入弹层里建库。
 *
 * ⚠️ [AI_SUGGESTED] 与动作侧同名态有一处关键差别：**那四项成分是人当场确认过的**，
 * AI 只负责预填。红线「数字本地算」就是靠这一条成立的，别把它读成"AI 写的数值"。
 */
enum class FoodSource {
    /** 内置库（启动时从 `assets/foods.json` 播种）。 */
    BUILT_IN,

    /** 用户自建。 */
    CUSTOM,

    /**
     * 外部 AI 提到、食物库里没有、用户在导入弹层里逐项确认（或自己填）之后建进来的。
     *
     * 界面上必须单独打标（「外部 AI 估」）：这批数值没有第二份来源可核对，
     * 用户得能看出哪几条不是他自己的数据、也不是内置那 127 条。
     */
    AI_SUGGESTED,
}

/**
 * 一条食物（食物库的行）。
 *
 * ## 为什么营养值是「每 100g」而不是「每份」
 * 每 100g 是数据源和营养标签的通用基准，抄录时不需要换算；
 * 「份」是**各家不同的**（你家碗 ≠ 饭店碗），所以它是 [servings] 里的可选换算系数，
 * 不是数据基准。历史记录存的是算完之后的快照值，**改这里不会改写历史**
 * （与 `CheckIn.weightKg` 存"当时举了多少"同一原则）。
 *
 * ## 内置库是我们自己写的，不是抄来的
 * 实测结论（spec §6）：**不存在任何有合法授权的中文食物成分数据集**。
 * 所以每张表里的数值都是手抄/估算，[FoodServing] 的克数尤其如此 ——
 * 这也是为什么"份"必须可由用户自己改（见 [isUserEdited]）。
 *
 * @param dietaryTags 该食物**含有**哪些忌口成分，复用 [DietRestriction]：
 *   档案侧（我忌口什么）与食物侧（这个含什么）必须共用一套词汇，
 *   否则忌口过滤要在两张表之间手工对齐 —— 与 `Equipment` 的处理同理。
 */
data class Food(
    val id: Long = 0L,
    val name: String,
    val kcalPer100g: Int,
    val proteinPer100g: Double,
    val carbsPer100g: Double,
    val fatPer100g: Double,
    /** 这个食物可选的家用份量，按 [FoodServing.sortOrder] 升序；空 = 只能按克记。 */
    val servings: List<FoodServing> = emptyList(),
    val dietaryTags: Set<DietRestriction> = emptySet(),
    val source: FoodSource = FoodSource.CUSTOM,
    val note: String? = null,
    val isActive: Boolean = true,
    /**
     * 用户改过这条。内置食物被编辑后**仍然保持** [FoodSource.BUILT_IN]
     * （改"一碗=250g"是你家碗的真相，不是"我发明了个新食物"），
     * 只置这个标记 —— 与动作库"编辑即不可逆降级为 `CUSTOM`"的旧规则**有意不同**。
     */
    val isUserEdited: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long = 0L,
) {

    /** 有可用的"份"单位吗（没有就只能按克填）。 */
    val hasServing: Boolean get() = servings.isNotEmpty()
}
