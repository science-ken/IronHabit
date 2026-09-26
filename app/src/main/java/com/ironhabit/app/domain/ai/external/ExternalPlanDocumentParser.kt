package com.ironhabit.app.domain.ai.external

import com.ironhabit.app.domain.ai.remote.stripCodeFence
import com.ironhabit.app.domain.model.AdviceSource
import com.ironhabit.app.domain.model.DietRestriction
import com.ironhabit.app.domain.model.Equipment
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.model.Food
import com.ironhabit.app.domain.model.FoodNutritionCalculator
import com.ironhabit.app.domain.model.Goal
import com.ironhabit.app.domain.model.InjuryArea
import com.ironhabit.app.domain.model.InputLimits
import com.ironhabit.app.domain.model.MealType
import com.ironhabit.app.domain.model.MuscleGroup
import com.ironhabit.app.domain.model.PlanItemDraft
import com.ironhabit.app.domain.model.PlanProposal
import com.ironhabit.app.domain.model.PlanReason
import com.ironhabit.app.domain.model.PlannedDay
import com.ironhabit.app.domain.model.TrainingFocus
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * **外部 AI 文档 → 一周计划草案** 的解析（零网络、零 Android 依赖，纯函数）。
 *
 * ## 这条路在干什么
 * 用户把 app 生成的提问模板连同数据包一起复制给他自己的 AI（ChatGPT / DeepSeek 网页版…），
 * 拿回一段文档，粘回 app。app 在这里**不联网、不花 token**，只把那段文档读成草案。
 *
 * ## 与内置远端解析（`RemoteLlmAdvisor.parseProposalJson`）的关系
 * - **形状刻意同构**：`days / items` 字段名和内置输出一致，只有 `exerciseId` → `exercise`（名字）。
 *   外部 AI 只看得见数据包里的**动作名**（`ironhabit-week-package/v1` 的 `library` 没有 id），
 *   所以回程必须按名字反查；
 * - **防线同一份口径**：组次上限取自 [InputLimits]（与表单校验、打卡位图同源），不另立数字；
 * - **必须分开的一份**：本函数**不做**"手改槽位不碰""模板归属日不写"这两条保护 —— 那是
 *   [com.ironhabit.app.domain.usecase.PlanDraftProjector] 的活，两条来源共用一个投影器。
 *   在这里再挡一次会让"为什么这条没进来"出现两套答案。
 *
 * ## 为什么拒收而不是尽力解析
 * 自然语言 / Markdown 表格识别是无底洞，且永远没法用测试钉住。所以合同写死在模板里，
 * 对不上就**明说对不上**（[ExternalDocRefusal]），并把模板再给一次。
 * 唯一宽容的两处：```` ```json ````围栏与前后寒暄（[stripCodeFence] + 截 JSON 子串）、
 * 未知字段（`ignoreUnknownKeys`，模型爱加什么加什么）。
 */
object ExternalPlanDocumentParser {

    /** 一条外部文档**最多**允许多少字符：超过直接拒收，不给它分配序列化开销。 */
    const val MAX_DOC_BYTES: Int = 512 * 1024

    /**
     * 单日条目上限。
     *
     * 按打卡界面的可用性定的（一天十几条动作用户真的做不完），不是随便取整：
     * 外部 AI 看不见"本周已经排了什么"以外的约束，也不受内置提示词里那句硬上限管束，
     * 冒出"周一 22 个动作"是常态。**超出的部分不静默截断**，一律进 [ExternalPlanNote.Kind.OVER_DAILY_LIMIT]。
     */
    const val MAX_ITEMS_PER_DAY: Int = 12

    /**
     * 一条"为什么"保留多少字。
     *
     * 模板要的是**一句话**，模型偶尔会交一整段。160 字够写完一句带从句的解释，
     * 再长就不该出现在一张卡片里 —— 截断而不是丢弃，因为前半句通常就是理由本体。
     */
    const val MAX_REASON_CHARS: Int = 160

    /**
     * 一餐最多几条食物。
     *
     * 和 [MAX_ITEMS_PER_DAY] 同一个理由：外部 AI 看不见"一餐装得下几条"这个约束，
     * 冒出一句"早餐 15 样"是常态。超出**不静默截断**，逐条进 [ExternalPlanNote.Kind.OVER_MEAL_LIMIT]。
     * 8 是按餐卡一屏能看完、且没人真的一餐吃八样东西定的。
     */
    const val MAX_FOODS_PER_MEAL: Int = 8

    /**
     * 把用户粘回来的文本读成草案。
     *
     * @param text 粘贴的原始文本（可能带围栏、前后有模型的解释话）
     * @param library 动作库**全量（含已停用）**，同时当名字白名单用。
     *   不按 active 过滤是有意的：停用行动作若算"库里没有"，它会变成建库候选 → 用户勾了 →
     *   撞 `exercises.name` UNIQUE 被跳过 → 重解析还是"库里没有"（**死循环**，食物侧同一个坑）。
     * @param foods 食物库**全量（含已停用）**。不按 active 过滤是有意的：
     *   停用行如果算"库里没有"，它会变成待新建候选 → 用户建库时撞上 `foods.name` UNIQUE
     *   → 被当成"已有"跳过 → 重解析还是"库里没有"。**死循环**（刀 4 在动作侧已经踩过一次）。
     * @param dietaryAvoid 用户的忌口集合，命中即整条挡（见 [ExternalPlanNote.Kind.FOOD_RESTRICTED]）
     */
    fun parse(
        text: String,
        library: List<Exercise>,
        foods: List<Food> = emptyList(),
        dietaryAvoid: Set<DietRestriction> = emptySet(),
    ): ExternalDocOutcome {
        // 长度是字节数下界（UTF-8 每字符 ≥1 字节）：先做 O(1) 的粗筛，避免为巨型粘贴分配字节数组。
        if (text.length > MAX_DOC_BYTES) return ExternalDocOutcome.Refused(ExternalDocRefusal.TOO_LARGE)
        if (text.toByteArray(Charsets.UTF_8).size > MAX_DOC_BYTES) {
            return ExternalDocOutcome.Refused(ExternalDocRefusal.TOO_LARGE)
        }

        val document: ExternalDocument = decode(text)
            ?: return ExternalDocOutcome.Refused(
                if (stripCodeFence(text).isEmpty()) ExternalDocRefusal.EMPTY_DOCUMENT
                else ExternalDocRefusal.NOT_A_DOCUMENT,
            )

        // schema 不回显就拒收：粘进来的东西没有边界，用户可能粘的是聊天记录、别家的 JSON、
        // 或上一版模板的输出。标签是唯一便宜的判据，而"尽力读读看"会把上一次的计划混进这一周。
        if (document.schema?.trim() != ExternalPlanSchema.SCHEMA) {
            return ExternalDocOutcome.Refused(ExternalDocRefusal.WRONG_SCHEMA)
        }

        val byName: Map<String, Exercise> = library
            .mapNotNull { exercise -> exercise.name.trim().takeIf { it.isNotEmpty() }?.let { it.lowercase() to exercise } }
            .toMap()

        // 食物名同口径：trim + lowercase **精确**匹配，不做模糊、不做拼音 —— 名字对不上就明说对不上。
        val foodsByName: Map<String, Food> = foods
            .mapNotNull { food -> food.name.trim().takeIf { it.isNotEmpty() }?.let { it.lowercase() to food } }
            .toMap()

        val notes = mutableListOf<ExternalPlanNote>()

        val (profilePatch, profileNotes) = resolveProfile(document.profile)
        notes += profileNotes

        // 建库门票已取消（刀 5）：库里没有的名字**一律**进待确认候选，不再要求文档先声明。
        // 先吃声明（能带上分类/肌群），再在解析条目时把没声明过的陌生名补进来。
        val candidates = mutableListOf<ImportedNewExercise>()
        candidates += resolveNewExercises(document.newExercises, library, notes)

        val days: List<PlannedDay> = document.days
            .map { day -> day.dayOfWeek.coerceIn(MIN_DAY_OF_WEEK, MAX_DAY_OF_WEEK) to day }
            .mapNotNull { (dayOfWeek, day) ->
                val items = resolveItems(dayOfWeek, day.items, byName, notes, candidates)
                if (items.isEmpty()) null
                else PlannedDay(
                    dayOfWeek = dayOfWeek,
                    focus = TrainingFocus.entries.firstOrNull { it.name == day.focus?.trim()?.uppercase() }
                        ?: TrainingFocus.FULL_BODY,
                    items = items,
                )
            }

        val newFoods = mutableListOf<ImportedNewFood>()
        val mealDrafts: List<ImportedMealDraft> = resolveMeals(
            declared = document.meals,
            foodsByName = foodsByName,
            dietaryAvoid = dietaryAvoid,
            declaredNewFoods = document.newFoods,
            notes = notes,
            newFoods = newFoods,
        )

        if (days.isEmpty() && mealDrafts.isEmpty()) {
            // 和内置 B-3 同口径：一条都不剩 ≠ "这周什么都不练/什么都不吃"。当成有效结果送去采纳会把整周清空。
            //
            // 但两种"空"要给两句话，因为下一步完全不同：
            // - 文档里**根本没写内容**（days 与 meals 都空，或只有 profile）→ 多半是它没收到数据包，
            //   该重发模板；这时候说"名字对不上"是把用户往错方向支。
            // - 写了但**每一条都被防线挡掉** → 才是名字对不上，逐条清单在这里最值钱。
            val hadAnyItem: Boolean = document.days.any { day -> day.items.isNotEmpty() } ||
                document.meals.any { day -> day.entries.any { entry -> entry.items.isNotEmpty() } }
            return ExternalDocOutcome.Refused(
                reason = if (hadAnyItem) ExternalDocRefusal.NO_USABLE_ITEMS else ExternalDocRefusal.NOTHING_TO_IMPORT,
                notes = notes.toList(),
                analysis = document.analysis?.trim()?.takeIf { it.isNotEmpty() },
                // 候选照样带回去：一份"全是新东西"的文档不是废文档，它是"先加库再导入"。
                newExercises = candidates.toList(),
                newFoods = newFoods.toList(),
            )
        }

        return ExternalDocOutcome.Parsed(
            ExternalPlanDraft(
                proposal = PlanProposal(
                    days = days,
                    // 来源如实标注：app 没生成它，也没联网。
                    source = AdviceSource.EXTERNAL_AI_IMPORT,
                    analysis = document.analysis?.trim()?.takeIf { it.isNotEmpty() },
                ),
                notes = notes.toList(),
                profile = profilePatch,
                newExercises = candidates.toList(),
                meals = mealDrafts,
                newFoods = newFoods.toList(),
            ),
        )
    }

    /**
     * 文档的 `meals` 段 → 一餐草案。数字一律**本地算**（见 [ImportedMealDraft]）。
     *
     * 一条食物内部的顺序即丢弃原因的优先级（和 [resolveItems] 同构）：
     * 空名 → 库里没有（变成待建候选）→ 停用行 → 撞忌口 → 同餐重复 → 超上限 → 克数钳制。
     *
     * @param newFoods 出参：待用户确认的新食物（按名字去重后追加）。
     */
    private fun resolveMeals(
        declared: List<ExternalMealDay>,
        foodsByName: Map<String, Food>,
        dietaryAvoid: Set<DietRestriction>,
        declaredNewFoods: List<ExternalNewFood>,
        notes: MutableList<ExternalPlanNote>,
        newFoods: MutableList<ImportedNewFood>,
    ): List<ImportedMealDraft> {
        if (declared.isEmpty()) return emptyList()

        // `newFoods` 从"准入凭证"降级成"预填资料"：库里没有的名字**一律**可建，
        // 声明过的那条只是提供数值。所以这里只按名字取用，不做资格判断。
        val declaredByName: Map<String, ExternalNewFood> = declaredNewFoods
            .mapNotNull { entry -> entry.name.trim().takeIf { it.isNotEmpty() }?.let { it.lowercase() to entry } }
            .toMap()

        val seenSlots = mutableSetOf<Pair<Int, MealType>>()
        val drafts = mutableListOf<ImportedMealDraft>()

        for (day in declared) {
            val dayOfWeek: Int = day.dayOfWeek.coerceIn(MIN_DAY_OF_WEEK, MAX_DAY_OF_WEEK)

            if (day.entries.isEmpty()) {
                // 它这一天确实写了东西（`meals` 里有这一项），但条目一条都没读到 ——
                // 最常见的形状是把 items 直接挂在天下（`{"dayOfWeek":1,"items":[…]}`），
                // 而合同要的是 `entries`。不报这一句，用户看到的就只是"吃的那部分凭空没了"。
                notes += ExternalPlanNote(
                    ExternalPlanNote.Kind.MEAL_ENTRIES_MISSING,
                    dayOfWeek,
                    null,
                    listOf(dayOfWeek),
                )
                continue
            }

            for (entry in day.entries) {
                val mealType: MealType? = entry.mealType.trim().uppercase()
                    .let { raw -> MealType.entries.firstOrNull { it.name == raw } }
                if (mealType == null) {
                    // 认不出的餐次**不猜**成"加餐"：四餐的槽位是 UNIQUE(date, meal_type)，
                    // 猜错会把用户某一餐的内容就地换掉，而那句换掉他永远看不出原因。
                    notes += ExternalPlanNote(ExternalPlanNote.Kind.MEAL_TYPE_UNKNOWN, dayOfWeek, entry.mealType)
                    continue
                }
                if (!seenSlots.add(dayOfWeek to mealType)) {
                    // `subject` 给**枚举名**，中文字由界面按 `meal_*` 资源映射：
                    // 这一层写死中文会撞上"餐次名必须资源化"那条硬规则（strings.xml 里就注着）。
                    notes += ExternalPlanNote(
                        ExternalPlanNote.Kind.DUPLICATE_MEAL,
                        dayOfWeek,
                        mealType.name,
                    )
                    continue
                }
                drafts += resolveMeal(dayOfWeek, mealType, entry, foodsByName, dietaryAvoid, declaredByName, notes, newFoods)
            }
        }

        // 一餐里一条都没剩下 → 整餐不进草案：预览页对"文档没写到的餐次"的语义是**原样保留**，
        // 所以"写了但全被挡"绝不能变成"清空这一餐"。原因逐条在 notes 里。
        return drafts.filter { draft -> draft.entries.isNotEmpty() }
    }

    private fun resolveMeal(
        dayOfWeek: Int,
        mealType: MealType,
        entry: ExternalMealEntry,
        foodsByName: Map<String, Food>,
        dietaryAvoid: Set<DietRestriction>,
        declaredByName: Map<String, ExternalNewFood>,
        notes: MutableList<ExternalPlanNote>,
        newFoods: MutableList<ImportedNewFood>,
    ): ImportedMealDraft {
        val seenFoodIds = mutableSetOf<Long>()
        val resolved = mutableListOf<ImportedFoodEntry>()
        var unresolvedCount = 0

        for (raw in entry.items) {
            val name: String = raw.food.trim()
            if (name.isEmpty()) {
                notes += ExternalPlanNote(ExternalPlanNote.Kind.BLANK_FOOD_NAME, dayOfWeek)
                continue
            }
            val food: Food? = foodsByName[name.lowercase()]
            if (food == null) {
                // 库里没有 → 待用户确认建库（刀 4）。这一餐**不含**它，所以那句"少算了 N 条"要计数。
                notes += ExternalPlanNote(ExternalPlanNote.Kind.FOOD_CREATABLE, dayOfWeek, name)
                unresolvedCount++
                addNewFoodCandidate(name, declaredByName[name.lowercase()], dayOfWeek, notes, newFoods)
                continue
            }
            if (!food.isActive) {
                notes += ExternalPlanNote(ExternalPlanNote.Kind.FOOD_INACTIVE, dayOfWeek, name)
                unresolvedCount++
                continue
            }
            val hit: DietRestriction? = food.dietaryTags.firstOrNull { tag -> tag in dietaryAvoid }
            if (hit != null) {
                // 安全字段：命中就整条挡，不给"我知道，仍要"的出口（R5）。
                notes += ExternalPlanNote(ExternalPlanNote.Kind.FOOD_RESTRICTED, dayOfWeek, name)
                continue
            }
            if (!seenFoodIds.add(food.id)) {
                notes += ExternalPlanNote(ExternalPlanNote.Kind.DUPLICATE_FOOD, dayOfWeek, name)
                continue
            }
            if (resolved.size >= MAX_FOODS_PER_MEAL) {
                notes += ExternalPlanNote(
                    ExternalPlanNote.Kind.OVER_MEAL_LIMIT,
                    dayOfWeek,
                    name,
                    listOf(MAX_FOODS_PER_MEAL),
                )
                continue
            }

            val grams: Int = raw.grams.coerceIn(InputLimits.MIN_SERVING_GRAMS, InputLimits.MAX_SERVING_GRAMS)
            if (grams != raw.grams) {
                notes += ExternalPlanNote(
                    ExternalPlanNote.Kind.GRAMS_CLAMPED,
                    dayOfWeek,
                    name,
                    listOf(raw.grams, grams),
                )
            }

            resolved += ImportedFoodEntry(
                foodId = food.id,
                name = food.name.trim(),
                grams = grams,
                // 🔑 数字在这里算，不在文档里读：模型给的一餐合计营养值一律到不了这一行以下。
                nutrition = FoodNutritionCalculator.forGrams(food, grams.toDouble()),
            )
        }

        return ImportedMealDraft(
            dayOfWeek = dayOfWeek,
            mealType = mealType,
            entries = resolved,
            kcal = resolved.sumOf { item -> item.nutrition.kcal },
            proteinG = resolved.sumOf { item -> item.nutrition.proteinG },
            reason = entry.reason?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_REASON_CHARS),
            unresolvedCount = unresolvedCount,
        )
    }

    /**
     * 攒一条"库里没有、等用户确认才建库"的食物。
     *
     * 同名的多条只留第一条；库里（含停用行）已有的名字**不进候选** ——
     * 它是正常解析路径，报出来就等于指着用户刚照 app 说的做的那一步说"这不合法"。
     */
    private fun addNewFoodCandidate(
        name: String,
        declared: ExternalNewFood?,
        dayOfWeek: Int,
        notes: MutableList<ExternalPlanNote>,
        out: MutableList<ImportedNewFood>,
    ) {
        if (out.any { candidate -> candidate.name.equals(name, ignoreCase = true) }) return

        out += ImportedNewFood(
            name = name,
            kcalPer100g = declared?.let { raw ->
                raw.kcalPer100g?.takeIf { it in InputLimits.MIN_FOOD_KCAL_PER_100G..InputLimits.MAX_FOOD_KCAL_PER_100G }
                    .also { if (it == null && raw.kcalPer100g != null) notes += rejectedNewFood(name, "kcalPer100g", dayOfWeek) }
            },
            proteinPer100g = declared?.let { raw ->
                raw.proteinPer100g?.takeIf { it in InputLimits.MIN_FOOD_MACRO_PER_100G..InputLimits.MAX_FOOD_MACRO_PER_100G }
                    .also { if (it == null && raw.proteinPer100g != null) notes += rejectedNewFood(name, "proteinPer100g", dayOfWeek) }
            },
            carbsPer100g = declared?.let { raw ->
                raw.carbsPer100g?.takeIf { it in InputLimits.MIN_FOOD_MACRO_PER_100G..InputLimits.MAX_FOOD_MACRO_PER_100G }
                    .also { if (it == null && raw.carbsPer100g != null) notes += rejectedNewFood(name, "carbsPer100g", dayOfWeek) }
            },
            fatPer100g = declared?.let { raw ->
                raw.fatPer100g?.takeIf { it in InputLimits.MIN_FOOD_MACRO_PER_100G..InputLimits.MAX_FOOD_MACRO_PER_100G }
                    .also { if (it == null && raw.fatPer100g != null) notes += rejectedNewFood(name, "fatPer100g", dayOfWeek) }
            },
        )
    }

    private fun rejectedNewFood(name: String, field: String, dayOfWeek: Int) =
        ExternalPlanNote(ExternalPlanNote.Kind.NEW_FOOD_VALUE_REJECTED, dayOfWeek, "$name.$field")

    /**
     * 校验文档声明的新动作，挑出"库里没有"的那些当候选。
     *
     * **刀 5 起这不再是准入凭证**：陌生名即使没声明也会由 [resolveItems] 补进候选，
     * 声明的作用只剩下**预填**分类 / 肌群 / 器械。所以这里从"整条拒收"改成了"降级 + 说清楚"——
     * 门票取消之后再整条拒收，后果从"少一条"变成了"这条永远建不了"，那是更坏的结果。
     *
     * @return 候选列表（说明直接 append 进 [notes]）
     */
    private fun resolveNewExercises(
        declared: List<ExternalNewExercise>,
        library: List<Exercise>,
        notes: MutableList<ExternalPlanNote>,
    ): List<ImportedNewExercise> {
        if (declared.isEmpty()) return emptyList()

        val valid = mutableListOf<ImportedNewExercise>()

        for (raw: ExternalNewExercise in declared) {
            val name: String = raw.name.trim()
            if (name.isEmpty()) {
                // 只有"连名字都没有"仍然整条拒收：没有名字的行建不出任何东西。
                notes += rejectedNewExercise("newExercises（名字为空）")
                continue
            }
            val category: ExerciseCategory? = raw.category?.let { enumNameOrNull<ExerciseCategory>(it) }
            if (category == null && !raw.category.isNullOrBlank()) {
                notes += ExternalPlanNote(
                    ExternalPlanNote.Kind.NEW_EXERCISE_CATEGORY_DEFAULTED,
                    null,
                    name,
                    listOf(raw.category.trim()),
                )
            }
            val labels: List<String> = raw.muscleGroups.map { it.trim() }.filter { it.isNotEmpty() }
            val badMuscles: List<String> = labels.filter { it !in MuscleGroup.formOptions }
            if (badMuscles.isNotEmpty()) {
                // 以前是整条拒收，现在是**丢掉那几个标签并点名**：
                // 留着坏标签会静默失效，整条丢掉又让用户永远建不了这个动作。
                notes += ExternalPlanNote(
                    ExternalPlanNote.Kind.NEW_EXERCISE_MUSCLES_DROPPED,
                    null,
                    name,
                    listOf(badMuscles.joinToString(" / ")),
                )
            }

            valid += ImportedNewExercise(
                name = name,
                // 没有分类的行在规则引擎里等于"哪块肌群都不算"，所以给 CUSTOM 而不是 null：
                // 它至少是用户可以自己改的合法值，而"没分类"会让伤病避让静默失效。
                category = category ?: ExerciseCategory.CUSTOM,
                muscleGroups = labels.filter { it in MuscleGroup.formOptions }.distinct(),
                equipment = raw.equipment?.mapNotNull { enumNameOrNull<Equipment>(it) }?.toSet() ?: emptySet(),
            )
        }

        // 库里（含停用行）已有的名字不进候选：它是正常解析路径。
        // 而且**不给任何提示** —— "建完库自动重解析"这一趟必然走到这里，
        // 报出来就等于指着用户刚照 app 说的做的那一步说"这不合法"。
        val known: Set<String> = library.map { existing -> existing.name.trim() }.toSet()
        return valid.filter { it.name !in known }
    }

    /** 一天内的条目：名字反查 → 停用分流 → 去重 → 单日上限 → 数值钳制（顺序即丢弃原因优先级）。 */
    private fun resolveItems(
        dayOfWeek: Int,
        rawItems: List<ExternalItem>,
        byName: Map<String, Exercise>,
        notes: MutableList<ExternalPlanNote>,
        newExercises: MutableList<ImportedNewExercise>,
    ): List<PlanItemDraft> {
        val seen = mutableSetOf<Long>()
        val resolved = mutableListOf<PlanItemDraft>()

        for (raw: ExternalItem in rawItems) {
            val name: String = raw.exercise.trim()
            if (name.isEmpty()) {
                notes += ExternalPlanNote(ExternalPlanNote.Kind.BLANK_EXERCISE_NAME, dayOfWeek)
                continue
            }
            val exercise: Exercise? = byName[name.lowercase()]
            if (exercise == null) {
                // 建库门票取消（刀 5）：库里没有就是"待你确认新建"，不再问文档有没有声明过。
                // 幻觉名 / 改名 也走这里 —— 用户看一眼就能不勾，比静默丢掉一条好。
                notes += ExternalPlanNote(ExternalPlanNote.Kind.EXERCISE_CREATABLE, dayOfWeek, name)
                addNewExerciseCandidate(name, newExercises)
                continue
            }
            if (!exercise.isActive) {
                // 停用行**不能**算"库里没有"：给了建库候选就是死循环（建 → 撞 UNIQUE → 跳过 → 还是没有）。
                notes += ExternalPlanNote(ExternalPlanNote.Kind.EXERCISE_INACTIVE, dayOfWeek, name)
                continue
            }
            if (!seen.add(exercise.id)) {
                notes += ExternalPlanNote(ExternalPlanNote.Kind.DUPLICATE_EXERCISE, dayOfWeek, name)
                continue
            }
            if (resolved.size >= MAX_ITEMS_PER_DAY) {
                notes += ExternalPlanNote(ExternalPlanNote.Kind.OVER_DAILY_LIMIT, dayOfWeek, name)
                continue
            }

            val sets: Int = raw.targetSets.coerceIn(InputLimits.MIN_SETS, InputLimits.MAX_SETS)
            val reps: Int = raw.targetReps.coerceIn(InputLimits.MIN_REPS, InputLimits.MAX_REPS)
            if (raw.targetSets != sets) {
                notes += ExternalPlanNote(ExternalPlanNote.Kind.SETS_CLAMPED, dayOfWeek, name, listOf(raw.targetSets, sets))
            }
            if (raw.targetReps != reps) {
                notes += ExternalPlanNote(ExternalPlanNote.Kind.REPS_CLAMPED, dayOfWeek, name, listOf(raw.targetReps, reps))
            }

            resolved += PlanItemDraft(
                exerciseId = exercise.id,
                targetSets = sets,
                targetReps = reps,
                // 与内置同口径：≤0 视为自重（null），不是"0 公斤"。
                targetWeightKg = raw.targetWeightKg?.takeIf { it > 0f },
                // 时长不信模型：回本地动作库取默认时长换算（修复 C3 的同一件事）。
                targetDurationMin = exercise.defaultDurationSec
                    ?.let { seconds -> seconds / SECONDS_PER_MINUTE }
                    ?.takeIf { minutes -> minutes >= MIN_DURATION_MIN },
                reason = if (resolved.isEmpty()) PlanReason.PRIMARY_LIFT else PlanReason.SUPPLEMENT,
                // 只截不断：模型写了一整段时留前 160 字，比"这条没有理由"有用，也不会撑爆卡片。
                explanation = raw.reason?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_REASON_CHARS),
            )
        }
        return resolved
    }

    /**
     * 档案段：允许的字段读成补丁，**身体实测/身份字段点名拒收**。
     *
     * 实测值不收 —— 那是**测量值不是建议**，模型填进来就是数据污染，
     * 而且档案数字会喂给以后每一次本地生成。
     */
    private fun resolveProfile(profile: ExternalProfile?): Pair<ExternalProfilePatch, List<ExternalPlanNote>> {
        if (profile == null) return ExternalProfilePatch() to emptyList()

        val notes = mutableListOf<ExternalPlanNote>()
        val forbidden = buildList {
            profile.heightCm?.let { add(ExternalPlanSchema.FIELD_HEIGHT_CM) }
            profile.age?.let { add(ExternalPlanSchema.FIELD_AGE) }
            profile.bodyFatPct?.let { add(ExternalPlanSchema.FIELD_BODY_FAT_PCT) }
            (profile.weightKg ?: profile.currentWeightKg)?.let { add(ExternalPlanSchema.FIELD_WEIGHT_KG) }
            profile.gender?.let { add(ExternalPlanSchema.FIELD_GENDER) }
        }
        notes += forbidden.map { field ->
            ExternalPlanNote(ExternalPlanNote.Kind.PROFILE_FIELD_FORBIDDEN, null, field)
        }

        val goal: Goal? = profile.goal?.let { raw -> enumNameOrNull<Goal>(raw) }
            .also { if (it == null && !profile.goal.isNullOrBlank()) notes += rejectedProfile("goal") }

        val equipment: Set<Equipment>? = profile.equipment?.let { names ->
            resolveNames(names) { raw -> enumNameOrNull<Equipment>(raw) }
                .also { it.second.forEach { name -> notes += rejectedProfile("equipment[$name]") } }
                .first
        }
        val injuryAreas: Set<InjuryArea>? = profile.injuryAreas?.let { names ->
            resolveNames(names) { raw -> enumNameOrNull<InjuryArea>(raw) }
                .also { it.second.forEach { name -> notes += rejectedProfile("injuryAreas[$name]") } }
                .first
        }

        return ExternalProfilePatch(
            goal = goal,
            goalWeightKg = profile.goalWeightKg,
            trainingDaysPerWeek = profile.trainingDaysPerWeek,
            equipment = equipment,
            injuryAreas = injuryAreas,
            injuryNote = profile.injuryNote,
        ) to notes
    }

    /**
     * 名字列表 → 枚举集合。**认不出的逐条回报，不猜**（`SHAPE` 不等于 `TONING`）。
     *
     * 全部认不出时返回 `null`（= 这一项不采纳）而不是空集合 —— 空集合在档案里是有意义的值
     * （"我没有伤病了" / "只用自重"），拿"模型编了三个假名字"去触发它，等于静默清空用户的约束。
     * 用户真想要空集合，就写一个空数组 `[]`，那是明确指令。
     */
    private fun <T> resolveNames(
        names: List<String>,
        resolve: (String) -> T?,
    ): Pair<Set<T>?, List<String>> {
        if (names.isEmpty()) return emptySet<T>() to emptyList()
        val resolved = names.mapNotNull { name -> resolve(name.trim())?.let { name.trim() to it } }
        if (resolved.isEmpty()) return null to names.map { it.trim() }
        val unknown = names.map { it.trim() } - resolved.map { it.first }.toSet()
        return resolved.map { it.second }.toSet() to unknown
    }

    private inline fun <reified T : Enum<T>> enumNameOrNull(raw: String): T? =
        enumValues<T>().firstOrNull { it.name == raw.trim().uppercase() }

    private fun rejectedProfile(field: String) =
        ExternalPlanNote(ExternalPlanNote.Kind.PROFILE_VALUE_REJECTED, null, field)

    private fun rejectedNewExercise(what: String) =
        ExternalPlanNote(ExternalPlanNote.Kind.NEW_EXERCISE_REJECTED, null, what)

    /** 陌生名补进待确认候选：分类给 `CUSTOM`、肌群留空，界面上那条会写「没标肌群」。 */
    private fun addNewExerciseCandidate(name: String, out: MutableList<ImportedNewExercise>) {
        if (out.any { candidate -> candidate.name.equals(name, ignoreCase = true) }) return
        out += ImportedNewExercise(
            name = name,
            category = ExerciseCategory.CUSTOM,
            muscleGroups = emptyList(),
            equipment = emptySet(),
        )
    }

    /** 去围栏 → 抽出 JSON 子串 → 宽松反序列化；任何失败都返回 `null`（调用方转成可识别拒收原因）。 */
    private fun decode(raw: String): ExternalDocument? {
        val candidate: String = extractJsonObject(stripCodeFence(raw)) ?: return null
        return try {
            json.decodeFromString(ExternalDocument.serializer(), candidate)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * 模型经常把 JSON 夹在"好的，这是你要的计划："和一句收尾之间 —— 取第一个 `{` 到最后一个 `}`。
     *
     * 这不是"尽力解析自然语言"：中间那段仍然必须是合法 JSON 才过得去，缺必填字段照样失败。
     */
    private fun extractJsonObject(text: String): String? {
        val start: Int = text.indexOf('{')
        val end: Int = text.lastIndexOf('}')
        return if (start in 0 until end) text.substring(start, end + 1) else null
    }

    private val json: Json = Json { ignoreUnknownKeys = true }

    private const val MIN_DAY_OF_WEEK: Int = 1
    private const val MAX_DAY_OF_WEEK: Int = 7
    private const val SECONDS_PER_MINUTE: Int = 60
    private const val MIN_DURATION_MIN: Int = 1
}

/** 冻结的回程合同标识（改结构必须换版本号，模板与解析器同时改）。
 *
 * `v1` → `v2`：`days` 从必填变可选、新增 `meals` / `newFoods` 两段（饮食导入）。
 * 精确匹配、无版本容错是**有意的** —— 旧模板发出去的文档会吃 [ExternalDocRefusal.WRONG_SCHEMA]，
 * 而那句拒收文案负责把用户支回"重新复制一次模板"，不给他一个读得半懂的计划。
 */
object ExternalPlanSchema {
    const val SCHEMA: String = "ironhabit-plan-import/v2"

    // 拒收名单里的字段名：给界面显示"哪一项被拒了"用，**不是**中文文案（架构禁止硬编码中文）。
    const val FIELD_HEIGHT_CM: String = "heightCm"
    const val FIELD_AGE: String = "age"
    const val FIELD_BODY_FAT_PCT: String = "bodyFatPct"
    const val FIELD_WEIGHT_KG: String = "weightKg"
    const val FIELD_GENDER: String = "gender"
}

/** 解析结果：要么拿到一份草案，要么**明确**说明为什么整份不收。 */
sealed interface ExternalDocOutcome {
    data class Parsed(val draft: ExternalPlanDraft) : ExternalDocOutcome

    /**
     * @property notes 拒收时**也带**逐条说明：`NO_USABLE_ITEMS` 的含义是"读通了，但每一条都被防线挡掉"，
     *   此时用户最需要知道的恰恰是被挡掉的是哪几条、为什么（多半是他库里没有那个动作）。
     *   格式类拒收（不是 JSON / schema 不对 / 太大）没有清单可言，恒为空。
     */
    data class Refused(
        val reason: ExternalDocRefusal,
        val notes: List<ExternalPlanNote> = emptyList(),
        /** 模型自己在 `analysis` 里写的话。拒收时尤其要看它 —— 它常常直接说了为什么没排。 */
        val analysis: String? = null,
        /** 一份"全是新动作"的文档不是废文档：候选照样带回去，让用户先加库再导入。 */
        val newExercises: List<ImportedNewExercise> = emptyList(),
        /** 同上，食物侧：一份"全是库里没有的食物"的文档是"先加库再导入"，不是废文档。 */
        val newFoods: List<ImportedNewFood> = emptyList(),
    ) : ExternalDocOutcome
}

/** 整份拒收的原因（部分有效不算拒收 —— 见 [ExternalPlanNote]）。 */
enum class ExternalDocRefusal {
    /** 什么都没粘。 */
    EMPTY_DOCUMENT,

    /** 不是合法 JSON / 缺必填字段 / 抽不出 JSON 对象。 */
    NOT_A_DOCUMENT,

    /** `schema` 缺失或不是本版本认的那个值。 */
    WRONG_SCHEMA,

    /** 超过 [ExternalPlanDocumentParser.MAX_DOC_BYTES]。 */
    TOO_LARGE,

    /**
     * 文档结构上就没写内容：`days` 与 `meals` 都空（或只有 `profile`）。
     *
     * 取代 v1 的 `EMPTY_PLAN`：v2 起"只有饮食、没有训练"是**合法**文档，
     * 所以这句不再暗示"它没收到动作库"，而是覆盖两半 —— 它多半没收到数据包。
     */
    NOTHING_TO_IMPORT,

    /** 写了内容，但每一条都被防线挡掉（全对不上库）—— 与内置 B-3 同口径，不能当有效结果落库。 */
    NO_USABLE_ITEMS,
}

/** 解析成功的一份草案 + 逐条"为什么这条没进来 / 哪个数字被动过"。 */
data class ExternalPlanDraft(
    /** 归一到内置同形状，直接交给 `PlanDraftProjector` 投影。 */
    val proposal: PlanProposal,
    val notes: List<ExternalPlanNote>,
    /** 档案段（已过滤 + 已钳制前的原值）。空补丁 = 文档没提档案。 */
    val profile: ExternalProfilePatch = ExternalProfilePatch(),
    /** 待用户确认的新动作（库里没有且文档声明过）。空表 = 不需要建任何东西。 */
    val newExercises: List<ImportedNewExercise> = emptyList(),
    /** 这一周解析出来的餐次草案（数字全是本地算的，见 [ImportedMealDraft]）。 */
    val meals: List<ImportedMealDraft> = emptyList(),
    /** 待用户确认的新食物（库里完全没有）。空表 = 这一份文档不需要建任何东西。 */
    val newFoods: List<ImportedNewFood> = emptyList(),
)

/**
 * 一条"文档里有什么被丢了 / 被改了"。
 *
 * 界面**必须**摊开这些，一条都不许静默吞掉 —— 用户看不见动作库 id、也不知道自己哪些动作停用过，
 * 只有如实列出来他才知道"缺的那两条是他自己库里没有"，而不是"app 偷偷少写了"。
 *
 * @property args 给 `strings.xml` 占位符用的参数（类型与资源占位符一致）
 */
data class ExternalPlanNote(
    val kind: Kind,
    /** 所属星期 `1..7`；档案类说明与天无关，为 `null`。 */
    val dayOfWeek: Int? = null,
    /** 相关动作名 / 档案字段名（原样回显，让用户对得上他自己写的那份文档）。 */
    val subject: String? = null,
    val args: List<Any> = emptyList(),
) {
    enum class Kind {
        /** 库里没有这个名字 → 进待确认新建清单（刀 5 起不再要求文档先声明）。 */
        EXERCISE_CREATABLE,

        /** 库里有这条但**已停用**：不导入，也不给建库候选（给了就是死循环）。 */
        EXERCISE_INACTIVE,

        /** 声明的新动作连名字都没有，整条拒收（没有名字建不出任何东西）。 */
        NEW_EXERCISE_REJECTED,

        /** 声明里的 `category` 是 App 认不出的值 → 建库时先按 `CUSTOM` 放，**点名告知**。 */
        NEW_EXERCISE_CATEGORY_DEFAULTED,

        /** 声明里的肌群标签有不在词表的 → **丢掉那几个**并点名（整条拒收会让用户永远建不了它）。 */
        NEW_EXERCISE_MUSCLES_DROPPED,

        /** 空动作名。 */
        BLANK_EXERCISE_NAME,

        /** 同一天重复出现的同一动作（保留第一条）。 */
        DUPLICATE_EXERCISE,

        /** 该天超出 [ExternalPlanDocumentParser.MAX_ITEMS_PER_DAY] 之后的条目。 */
        OVER_DAILY_LIMIT,

        /** `targetSets` 越界，已钳制（args：原值、钳后值）。 */
        SETS_CLAMPED,

        /** `targetReps` 越界，已钳制（args：原值、钳后值）。 */
        REPS_CLAMPED,

        /** 文档改了身体实测/身份字段，**拒收**（subject = 字段名）。 */
        PROFILE_FIELD_FORBIDDEN,

        /** 档案字段写了个认不出的值（`goal:"TONING"`、编造的器械名…），**不猜、不采纳**（subject = 字段名）。 */
        PROFILE_VALUE_REJECTED,

        // ---------------- 饮食段（v2 新增；界面一律要摊开，一条都不许静默） ----------------
        /** 库里没有的食物 → 进"待确认建库"清单，这一餐**不含**它（subject = 食物名）。 */
        FOOD_CREATABLE,

        /** 库里**有但已停用**的食物：不导入、也不提示新建（新建会撞 UNIQUE 再绕回来）。 */
        FOOD_INACTIVE,

        /** 撞了用户忌口标签，**整条挡**（subject = 食物名）。界面不许给"仍要导入"的出口。 */
        FOOD_RESTRICTED,

        /** 空食物名。 */
        BLANK_FOOD_NAME,

        /** 同一餐里重复出现的同一食物（保留第一条，**不合并份量**）。 */
        DUPLICATE_FOOD,

        /** 该餐超出 [ExternalPlanDocumentParser.MAX_FOODS_PER_MEAL] 之后的条目。 */
        OVER_MEAL_LIMIT,

        /** 克数越界，已钳制（args：原值、钳后值）。 */
        GRAMS_CLAMPED,

        /** `mealType` 不是本 App 认识的餐次名，那一餐整条不采纳（subject = 它写的那个值）。 */
        MEAL_TYPE_UNKNOWN,

        /** 同一个 (星期, 餐次) 槽位出现两次，只留第一条（subject = `MealType.name`，中文由界面映射）。 */
        DUPLICATE_MEAL,

        /** 声明的新动作数值超出可记录范围，**置空等用户填**（subject = `名字.字段`）。 */
        NEW_FOOD_VALUE_REJECTED,

        /**
         * `meals` 里有这一天，但那一天**一条条目都没读到**（多半是把 `items` 写在了天的层级上，
         * 而合同要的是 `entries`）。不发这一条就是静默丢掉一整天的吃。
         */
        MEAL_ENTRIES_MISSING,
    }
}

// ---------------- 回程文档 DTO（必填字段不带默认值 → 缺字段即整份拒收） ----------------

@Serializable
private data class ExternalDocument(
    /** 必须回显 [ExternalPlanSchema.SCHEMA]，否则整份不收。 */
    val schema: String? = null,
    /** v2 起可选：纯饮食文档没有训练日是合法的（v1 时代它会被当空计划整份拒掉）。 */
    val days: List<ExternalDay> = emptyList(),
    /** 可选：一周的餐次草案。 */
    val meals: List<ExternalMealDay> = emptyList(),
    /** 可选：外部 AI 的"为什么这么排"，原样透传给预览页显示。 */
    val analysis: String? = null,
    /** 可选：档案改动（逐字段勾选后才写）。 */
    val profile: ExternalProfile? = null,
    /** 可选：新动作的**预填资料**（v2 起不再是建库门票，见 [resolveItems] 的调用侧）。 */
    val newExercises: List<ExternalNewExercise> = emptyList(),
    /** 可选：新食物的**预填资料**（同上）。缺了这一段的陌生食物照样可建，只是数值要用户自己填。 */
    val newFoods: List<ExternalNewFood> = emptyList(),
)

@Serializable
private data class ExternalDay(
    val dayOfWeek: Int,
    val items: List<ExternalItem>,
    val focus: String? = null,
)

@Serializable
private data class ExternalItem(
    /** **名字**而不是 id：数据包里的 `library` 本来就没有 id。 */
    val exercise: String,
    val targetSets: Int,
    val targetReps: Int,
    val targetWeightKg: Float? = null,
    /** 可选：这一条为什么排进来（自由文本，只在预览页折叠显示，不落库）。 */
    val reason: String? = null,
)

/**
 * 文档声明的新动作。`category` **不带默认值**：
 * 没有分类的行在规则引擎里等于"哪块肌群都不算"，伤病避让会静默失效，所以宁可整条拒收。
 */
@Serializable
private data class ExternalNewExercise(
    val name: String,
    val category: String? = null,
    val muscleGroups: List<String> = emptyList(),
    val equipment: List<String>? = null,
)

/** 文档里的一天饮食。`dayOfWeek` 与训练侧同口径（1=周一），越界钳到 1..7。 */
@Serializable
private data class ExternalMealDay(
    val dayOfWeek: Int,
    val entries: List<ExternalMealEntry> = emptyList(),
)

/**
 * 一餐。`mealType` **不带默认值**：没有餐次就不知道该写进哪一个槽位，
 * 而猜一个（比如一律当加餐）会静默改掉用户那一餐原本的内容。
 */
@Serializable
private data class ExternalMealEntry(
    val mealType: String,
    val items: List<ExternalFoodItem> = emptyList(),
    /** 可选：这一餐为什么这样配（自由文本，只在预览页显示，不落库）。 */
    val reason: String? = null,
)

/**
 * 一条食物。**只收克数，不收"碗/勺/份"**：
 * `foods.json` 实测有 24 种单位名，里面是 `份（干）`、`碗（生）`、`把（生）` 这种东西，
 * 模型抄不准 → 整条被拒，而用户看到的理由是"单位不认识"，比"我按克数算"难懂得多。
 * 展示时的"1.3 碗"由界面按食物自己的份量换算，**不回写文档**。
 */
@Serializable
private data class ExternalFoodItem(
    /** **名字**而不是 id：模板发给模型的食物清单本来就没有 id。 */
    val food: String,
    val grams: Int,
)

/**
 * 文档给"库里没有的食物"预填的营养值。四项**全部可空**：
 * 建库门票取消后常见"什么数都没给"，那时界面必须空着等用户填，
 * 而不是拿 0 顶上 —— 0 kcal/100g 是一个**陈述**，不是"不知道"。
 */
@Serializable
private data class ExternalNewFood(
    val name: String,
    val kcalPer100g: Int? = null,
    val proteinPer100g: Double? = null,
    val carbsPer100g: Double? = null,
    val fatPer100g: Double? = null,
)

/**
 * 档案段。允许的字段收进来但本版不应用；**拒收的字段必须显式声明**，
 * 否则 `ignoreUnknownKeys` 会让它们凭空消失，用户以为改了、其实什么都没发生。
 */
@Serializable
private data class ExternalProfile(
    val goal: String? = null,
    val goalWeightKg: Float? = null,
    val trainingDaysPerWeek: Int? = null,
    val equipment: List<String>? = null,
    val injuryAreas: List<String>? = null,
    val injuryNote: String? = null,
    val heightCm: Int? = null,
    val age: Int? = null,
    val bodyFatPct: Float? = null,
    val weightKg: Float? = null,
    val currentWeightKg: Float? = null,
    val gender: String? = null,
)
