package com.ironhabit.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 文案占位符 ↔ 实参类型的**契约测试**（扫描式双向比对，纯 JVM / 离线可跑，不依赖 Robolectric）。
 *
 * ## 为什么需要它
 * `Context.getString(res, *args)` / Compose `stringResource(res, *args)` 最终走 `String.format`：
 * 若资源用了数值占位符 `%1$d`，而实参是 `String`（或反之），**运行时**会抛
 * `java.util.IllegalFormatConversionException`。这在编译期**完全不可见** ——
 * 本项目历史上已因同类问题崩过一次（`92263ee` 的 `msg_diet_filtered`：
 * `%1$d` 收到 `filteredCount.toString()` → 用户一勾忌口、生成计划即崩）。
 *
 * ## 覆盖范围（扫描式，非白名单锚定）
 * 本测试**扫描** `strings.xml` 得到全部带占位符的资源（当前 51 个），并与
 * [registry] 做**双向比对**：
 * 1. `strings.xml → registry`：任何带占位符的资源都必须登记（含实参类型序列），
 *    **新增带参文案不登记即红灯**——防止未来新增资源绕过契约；
 * 2. `registry → strings.xml`：登记项必须真实存在于 `strings.xml`，且占位符的
 *    个数、顺序、类型与登记的实参类型序列完全一致——防止资源文案被改后契约失真；
 * 3. **调用点扫描**：扫描全部 Kotlin 源码的字面量格式化调用
 *    （`stringResource(R.string.x, ...)` / `getString(R.string.x, ...)`）：
 *    - 带参资源**零参格式化** → 运行时 `MissingFormatArgumentException`，红灯；
 *    - 非带参资源带参调用 → 实参被静默丢弃，红灯；
 *    - 实参个数 ≠ 占位符个数 → 红灯。
 * 4. 两条 Snackbar「动态通道」（实参经 `List<Any>`/`List<String>` spread 传入，
 *    resId 为变量，静态扫描不可见）以 [channelStringArgs] / [channelIntArgs] 锚定：
 *    - 通道 A：`TodayUiState.snackbarArgs` / `SettingsViewModel.snackbarArgs`
 *      （`List<String>`）→ 资源必须 `%s`、不得 `%d`；
 *    - 通道 B：`PlanBasisItem.args`（AiCoach Snackbar 那条 Int 通道随 planResult 一起删掉了）
 *      （`List<Any>`，实参为 Int）→ 资源必须 `%d`、不得 `%s`。
 *
 * 登记表每一条都标注了实参类型的**源码证据位置**（文件:行），改实参类型时同步改这里。
 */
class StringResourcePlaceholderContractTest {

    /** 实参类型：String（`%s`）或数值（`%d`）。 */
    private enum class ArgKind { STRING, NUMERIC }

    /**
     * 带参资源登记表：`资源名 → 按占位符顺序的实参类型序列`。
     *
     * 三类来源：
     * - 【通道 A】String 实参（`List<String>` Snackbar spread）；
     * - 【通道 B】Int 实参（PlanBasisItem.args spread）；
     * - 【直调】字面量 `stringResource(R.string.x, ...)`（调用点扫描可复核个数）。
     * - 【未使用】`strings.xml` 有占位符但代码暂无引用（登记占位，防止"无主带参资源"）。
     */
    private val registry: Map<String, List<ArgKind>> = buildMap {
        fun entry(name: String, vararg kinds: ArgKind) {
            put(name, kinds.toList())
        }

        // ---------------- 【通道 A】String 实参（List<String> Snackbar） ----------------
        entry("msg_diet_filtered", ArgKind.STRING) // Today/AiCoach Snackbar + AiCoachScreen:639（toString()，历史崩溃主角）
        entry("msg_reminder_set", ArgKind.STRING) // SettingsViewModel:66（formatTime(...)）
        entry("msg_plan_created", ArgKind.STRING) // TodayViewModel:354-357（writtenCount.toString()）
        entry("msg_copy_next_week_done", ArgKind.STRING) // TodayViewModel「复制到下周」（copied.toString()）
        entry("msg_repeat_plan_stopped", ArgKind.STRING) // TodayViewModel 停用模板（stopped.toString()）

        // ---------------- 【通道 B】Int 实参（PlanBasisItem.args） ----------------
        entry("basis_overload", ArgKind.NUMERIC) // LocalRuleAdvisor（overloadCount: Int）
        entry("basis_frequency", ArgKind.NUMERIC) // LocalRuleAdvisor:307（trainingDaysPerWeek）
        entry("basis_volume", ArgKind.NUMERIC, ArgKind.NUMERIC, ArgKind.NUMERIC, ArgKind.NUMERIC) // LocalRuleAdvisor（4 个 Int）
        entry("basis_cardio", ArgKind.NUMERIC) // LocalRuleAdvisor:320（cardioPerWeek）
        entry("basis_recovery_age", ArgKind.NUMERIC, ArgKind.NUMERIC) // LocalRuleAdvisor:325（age, itemsPerDay）
        entry("basis_age_volume", ArgKind.NUMERIC, ArgKind.NUMERIC) // LocalRuleAdvisor（age, itemsPerDay）
        entry("basis_bodyfat_high", ArgKind.NUMERIC) // LocalRuleAdvisor
        entry("basis_bodyfat_low", ArgKind.NUMERIC) // LocalRuleAdvisor
        entry("basis_weight_cut", ArgKind.NUMERIC) // LocalRuleAdvisor
        entry("basis_weight_gain", ArgKind.NUMERIC) // LocalRuleAdvisor
        entry("basis_injury_swap", ArgKind.NUMERIC) // LocalRuleAdvisor
        entry("basis_library_too_narrow", ArgKind.NUMERIC) // LocalRuleAdvisor

        // ---------------- 【直调】stringResource(R.string.x, ...) 字面量调用 ----------------
        entry("label_streak_days", ArgKind.NUMERIC) // HabitRow 勾选行小字 / TodayBento（streak.current）
        entry("dialog_delete_habit_message", ArgKind.STRING) // DisciplineScreen 删除确认框（habit.name）
        entry("dialog_delete_body_metric_message", ArgKind.STRING, ArgKind.STRING) // BodyMetricsScreen 删除确认框（日期 · 值+单位）
        entry("dialog_copy_next_week_message", ArgKind.STRING) // TodayScreen 复制到下周确认框（rowCount.toString()）
        entry("hint_repeat_plan", ArgKind.STRING) // TodayScreen 模板出口行（repeatPlanRowCount.toString()）
        entry("label_streak_best", ArgKind.NUMERIC) // TodayBento（streak.best）
        entry("label_streak_days_value", ArgKind.NUMERIC) // TodayBento hero 磁贴（streak.current，displaySmall 那一行）
        entry("label_progress_ratio", ArgKind.NUMERIC, ArgKind.NUMERIC) // TodayBento（今日完成、习惯 n/m）
        entry("cd_set_index", ArgKind.NUMERIC) // SetCheckboxRow:76（index + 1）
        entry("label_weight_kg", ArgKind.STRING) // PlanGoalText:62 / SettingsScreen:478（toDisplayNumber(): String）
        entry("label_duration_min", ArgKind.NUMERIC) // PlanGoalText:39/44/73（durationMin: Int）
        entry("label_profile_training_days_option", ArgKind.NUMERIC) // SettingsScreen:547（Int 选项值）
        entry("ai_diet_preserved_hint", ArgKind.NUMERIC) // AiCoachScreen:615（preservedCount）
        entry("ai_explain_intake", ArgKind.NUMERIC, ArgKind.NUMERIC) // AiCoachScreen:622（targetKcal/targetProtein: Int）
        entry("ai_insight_stats", ArgKind.NUMERIC, ArgKind.STRING, ArgKind.STRING, ArgKind.NUMERIC) // AiCoachScreen:769（count, rpeText, weightText, streak）
        entry("ai_review_progressed", ArgKind.STRING) // WeeklyReviewBlock:359（joinToString 结果）
        entry("ai_review_stalled", ArgKind.STRING) // WeeklyReviewBlock:362（joinToString 结果）
        entry("ai_review_stalled_item", ArgKind.STRING, ArgKind.NUMERIC) // WeeklyReviewBlock:353（exerciseName, stagnantWeeks）
        entry("ai_review_week_offset", ArgKind.NUMERIC) // WeeklyReviewBlock:377（-weekOffset: Int）
        entry("ai_review_week_range", ArgKind.STRING, ArgKind.STRING) // WeeklyReviewBlock:383（monthDay ×2）
        entry("settings_version", ArgKind.STRING) // SettingsScreen:282（BuildConfig.VERSION_NAME）
        entry("label_meal_kcal", ArgKind.NUMERIC, ArgKind.NUMERIC) // MealBlock:80（kcal, proteinG.roundToInt()）
        entry("label_diet_intake_kcal", ArgKind.NUMERIC, ArgKind.NUMERIC) // DietTotalsBar:47（intakeKcal, kcalDenominator）
        entry("label_diet_intake_protein", ArgKind.NUMERIC, ArgKind.NUMERIC) // DietTotalsBar:57（roundToInt(), denominator）
        entry("label_food_per_100g_summary", ArgKind.NUMERIC) // FoodLibrarySheet（kcalPer100g: Int）
        entry("label_food_serving_summary", ArgKind.STRING, ArgKind.NUMERIC) // FoodLibrarySheet（unit: String, grams: Int）
        entry("label_food_library_count", ArgKind.NUMERIC) // FoodLibraryEntry（allFoods.size: Int）
        entry("chip_food_inactive_count", ArgKind.NUMERIC) // FoodLibrarySheet（inactiveFoods.size: Int）
        entry("chip_habit_deleted_count", ArgKind.NUMERIC) // DisciplineScreen（deletedHabits.size: Int）
        entry("label_food_inactive_summary", ArgKind.NUMERIC) // FoodLibrarySheet 停用行（kcalPer100g: Int）
        entry("label_meal_item_summary", ArgKind.STRING, ArgKind.STRING, ArgKind.NUMERIC) // MealBlock（名称 · 份量 · kcal）
        entry("label_meal_kcal_approx", ArgKind.NUMERIC, ArgKind.NUMERIC) // MealBlock 粗记态（kcal, 蛋白 g）
        entry("msg_meal_item_limit", ArgKind.STRING) // TodayViewModel（上限走 snackbarArgs: List<String> → 只能 %1$s）
        entry("ai_review_diet_approx", ArgKind.NUMERIC) // WeeklyReviewBlock（avgKcal: Int，全是粗记时给日均前面加「约」）
        entry("ai_review_unit_weighins", ArgKind.NUMERIC) // WeeklyReviewBlock 称重格（sampleCount: Int）
        entry("ai_review_sets_ratio", ArgKind.NUMERIC, ArgKind.NUMERIC) // WeeklyReviewBlock 组数格（totalSets, plannedSets）
        entry("ai_review_capacity_tonnes", ArgKind.STRING) // WeeklyReviewBlock 容量格（Kotlin 里取好小数再塞 %s）
        entry("ai_review_capacity_kg", ArgKind.STRING) // 同上，不到 1 吨时退回 kg
        entry("ai_review_weight_delta_down", ArgKind.STRING) // 体重格 ↓0.8
        entry("ai_review_weight_delta_up", ArgKind.STRING) // 体重格 ↑0.8
        entry("ai_day_value_approx", ArgKind.NUMERIC) // 日卡粗记那天的热量
        entry("ai_review_summary_sets", ArgKind.NUMERIC) // 周卡折叠行：真组数，0 也照写
        entry("ai_review_summary_rpe", ArgKind.STRING) // 折叠行 RPE：Kotlin 里 formatKg 取好小数
        entry("ai_review_summary_weight", ArgKind.STRING) // 折叠行体重变化：复用 weightDeltaText 的 ↓0.8
        // 四条"缺什么"的说明：周名走卡片标题同一个 weekLabel()，翻到上周不再写「本周」。
        entry("ai_review_trend_none", ArgKind.STRING) // WeeklyReviewBlock.trendLines（week: String）
        entry("ai_review_note_no_checkin", ArgKind.STRING) // noteText / 无复盘数据时的占位（week: String）
        entry("ai_review_note_no_weight", ArgKind.STRING) // noteText（week: String）
        entry("ai_review_note_no_diet", ArgKind.STRING) // noteText（week: String）
        entry("ai_profile_equipment", ArgKind.STRING) // 档案行器械：joinToString 出来的中文器械名
        entry("ai_profile_weekly_days", ArgKind.NUMERIC) // 档案行「每周 N 练」（trainingDaysPerWeek: Int）
        entry("ai_day_shortfall", ArgKind.NUMERIC, ArgKind.NUMERIC, ArgKind.NUMERIC) // 日卡：计划 / 实际 / 差几组
        entry("ai_chip_attendance", ArgKind.NUMERIC, ArgKind.NUMERIC) // 复盘屏 chip：实际/计划天数
        entry("ai_chip_attendance_q", ArgKind.NUMERIC, ArgKind.NUMERIC) // 同上的完整提问（%2$d 在前，定位式）
        entry("ai_chip_stalled", ArgKind.NUMERIC) // 复盘屏 chip：停滞周数
        entry("ai_chip_stalled_q", ArgKind.STRING, ArgKind.NUMERIC, ArgKind.NUMERIC, ArgKind.NUMERIC) // 动作名 + 周数 + 组数
        entry("ai_chip_no_rpe", ArgKind.NUMERIC) // 复盘屏 chip：没记 RPE 的组数（下界）
        entry("ai_chip_no_rpe_q", ArgKind.NUMERIC) // 同上的完整提问

        // 「我的」页 J 版首屏（档案卡 / 四联）
        entry("value_profile_fraction", ArgKind.NUMERIC, ArgKind.NUMERIC) // ProfileCompletenessRing 环中央「4/6」
        entry("value_record_count", ArgKind.NUMERIC) // 「我的」页四联与台账、「身体数据」页共用的「N 条」
        entry("label_body_metric_records", ArgKind.NUMERIC) // 身体数据当前值卡：records.size: Int
        entry("label_profile_gap_line", ArgKind.NUMERIC, ArgKind.STRING) // 档案卡缺口行（项数 + joinLabels 出来的中文）
        entry("label_profile_body_fat_short", ArgKind.STRING) // 档案第二行：Float 在 Kotlin 里取好再塞 %s
        entry("label_profile_equipment_count", ArgKind.NUMERIC) // 档案第二行：equipment.size: Int

        // 「我的」页记录台账 + 存哪儿
        entry("value_profile_logs_count", ArgKind.NUMERIC) // 习惯行右侧：completedLogs
        entry("label_last_date_short", ArgKind.STRING) // 「最近 9/19」：epochDay 已格式化成 M/D
        entry("label_ledger_training_detail", ArgKind.NUMERIC, ArgKind.NUMERIC, ArgKind.NUMERIC, ArgKind.NUMERIC) // 组/次/带RPE/覆盖天
        entry("label_ledger_diet_gap", ArgKind.NUMERIC, ArgKind.NUMERIC, ArgKind.NUMERIC) // 餐次/食物条/热量
        entry("label_ledger_diet_detail", ArgKind.NUMERIC, ArgKind.NUMERIC, ArgKind.NUMERIC) // 同上，缺口不超半数时的普通版
        entry("label_ledger_habit_detail", ArgKind.NUMERIC, ArgKind.NUMERIC) // 在跑条数 / 完成次数
        entry("label_schema_version", ArgKind.NUMERIC) // AppDatabase.VERSION
        entry("label_profile_storage_title", ArgKind.STRING) // 存哪儿标题：%1$s = 「Room v9」

        // 「训练统计」页
        entry("label_range_days", ArgKind.NUMERIC) // 区间 chip（7 / 30 / 90）
        entry("label_active_days", ArgKind.NUMERIC, ArgKind.NUMERIC) // 有打卡的天数 / 区间天数
        entry("label_peak_count", ArgKind.NUMERIC) // 柱状卡右上角：区间内单日最高次数

        // ---------------- 【外部 AI 文档导入】清单与周标签（实参一律 toString() 成 String） ----------------
        // 导入弹层的周 chip：AiCoachScreen 的 weekRangeText(...) 产出（区间文本是字符串）
        entry("ai_import_week_this", ArgKind.STRING)
        entry("ai_import_week_next", ArgKind.STRING)
        // 通道：AiCoachUiState.snackbarArgs（List<Any> spread，实参走 toString()）
        entry("ai_import_nothing_adoptable", ArgKind.STRING)
        // 预览页的"没导进来"清单：subject 与 args 一律 .toString() 后传 String，
        // 这样这一组的类型口径只有一种，不会因为 %d 收到 String 而运行时崩。
        entry("plan_preview_note_unknown_exercise", ArgKind.STRING)
        entry("plan_preview_note_duplicate", ArgKind.STRING)
        entry("plan_preview_note_over_limit", ArgKind.STRING, ArgKind.STRING)
        entry("plan_preview_note_sets_clamped", ArgKind.STRING, ArgKind.STRING)
        entry("plan_preview_note_reps_clamped", ArgKind.STRING, ArgKind.STRING)
        entry("plan_preview_note_profile_forbidden", ArgKind.STRING)
        entry("plan_preview_note_profile_value_rejected", ArgKind.STRING)
        // 档案 diff（刀 2）：天数与按钮计数是数字，走 %d；「已改 N 项」经 snackbarArg（String 通道）走 %s。
        entry("plan_preview_profile_days_value", ArgKind.NUMERIC)
        entry("plan_preview_profile_apply", ArgKind.NUMERIC)
        entry("plan_preview_profile_applied", ArgKind.STRING)

        // ---------------- 【未使用】strings.xml 有占位符、代码暂无引用 ----------------
        entry("label_week_of", ArgKind.STRING)
        entry("title_week_plan_by_day", ArgKind.STRING)
        // AddEditPlanScreen:90-96（weekRangeText 产出的「9/21–9/27」区间文本）
        entry("scope_plan_this_week", ArgKind.STRING)
        // TrainScreen PlanSection（同一个 weekRangeText 的产出）
        entry("label_week_this", ArgKind.STRING)
        // PlanPreviewScreen（weekRangeText / 草案计数 / 保留条数）
        entry("title_plan_preview", ArgKind.STRING)
        entry("plan_preview_preserved", ArgKind.NUMERIC)
        entry("plan_preview_day_meta", ArgKind.NUMERIC, ArgKind.NUMERIC)
        entry("plan_preview_adopt_all", ArgKind.NUMERIC, ArgKind.NUMERIC)
        entry("msg_plan_adopted_count", ArgKind.STRING)
        entry("plan_preview_footer", ArgKind.NUMERIC, ArgKind.NUMERIC, ArgKind.NUMERIC)
        entry("label_sets_progress", ArgKind.NUMERIC, ArgKind.NUMERIC)
        entry("label_rpe_short", ArgKind.NUMERIC)
        entry("ai_explain_injury_swap", ArgKind.STRING)
        entry("ai_explain_equipment", ArgKind.STRING)
    }

    /** 通道 A：实参为 `String` 的动态 Snackbar 通道 → 只能 `%s`。 */
    private val channelStringArgs = listOf(
        "msg_diet_filtered",
        "msg_reminder_set",
        "msg_plan_created",
    )

    /** 通道 B：实参为 `Int` 的动态通道（`PlanBasisItem.args`）→ 用 `%d`。 */
    private val channelIntArgs = registry.filterKeys { it.startsWith("basis_") }.keys

    // ---------------------------------------------------------------------
    // 1）strings.xml → registry：全部带参资源必须登记，且占位符与登记一致
    // ---------------------------------------------------------------------

    @Test
    fun parameterizedResources_mustBeRegistered_withMatchingPlaceholderKinds() {
        val strings = loadStrings()
        val unregistered = mutableListOf<String>()
        val mismatched = mutableListOf<String>()

        strings.forEach { (name, text) ->
            val placeholders = parsePlaceholders(text) ?: return@forEach // 无占位符
            val expected = registry[name]
            when {
                expected == null ->
                    unregistered += "$name（占位符=$text）→ 请在 registry 登记实参类型序列"
                expected.size != placeholders.size || expected != placeholders ->
                    mismatched +=
                        "$name：登记=${expected} 占位符=$placeholders（文案：$text）"
            }
        }

        assertTrue(
            "发现未登记的带参资源（新带参文案必须进 registry 契约）：\n" + unregistered.joinToString("\n"),
            unregistered.isEmpty(),
        )
        assertTrue(
            "登记的实参类型与占位符不一致：\n" + mismatched.joinToString("\n"),
            mismatched.isEmpty(),
        )
    }

    // ---------------------------------------------------------------------
    // 2）registry → strings.xml：登记项必须存在（防重命名/删除后契约失真）
    // ---------------------------------------------------------------------

    @Test
    fun registryEntries_mustExistInStringsXml() {
        val strings = loadStrings()
        val stale = registry.keys.filterNot { it in strings }
        assertTrue(
            "registry 含 strings.xml 不存在的资源（已重命名/删除？请同步登记表）：$stale",
            stale.isEmpty(),
        )
    }

    // ---------------------------------------------------------------------
    // 3）调用点扫描：字面量格式化调用的实参个数必须与占位符个数一致
    // ---------------------------------------------------------------------

    @Test
    fun formatCallSites_argCountMustMatchPlaceholderCount() {
        val strings = loadStrings()
        val violations = mutableListOf<String>()

        kotlinSourceRoots()
            .asSequence()
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" } }
            .forEach { file ->
                val text = file.readText()
                resourceRefRegex.findAll(text).forEach { ref ->
                    val name = ref.groupValues[1]
                    val placeholders = strings[name]?.let { parsePlaceholders(it) } ?: emptyList()
                    val call = enclosingFormatCall(text, ref.range.first) ?: return@forEach
                    val argCount = call.formatArgCount
                    val at = "${file.name}:${text.take(ref.range.first).count { it == '\n' } + 1}"

                    when {
                        argCount == 0 && placeholders.isNotEmpty() ->
                            violations += "$at：带参资源 `$name` 被零参格式化（运行时 MissingFormatArgumentException）"
                        argCount > 0 && placeholders.isEmpty() ->
                            violations += "$at：非带参资源 `$name` 被带参调用（实参会被静默丢弃）"
                        argCount > 0 && placeholders.isNotEmpty() && argCount != placeholders.size ->
                            violations += "$at：`$name` 实参个数 $argCount ≠ 占位符个数 ${placeholders.size}"
                    }
                }
            }

        assertTrue(
            "占位符 ↔ 调用点契约被破坏：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    // ---------------------------------------------------------------------
    // 4）两条动态 Snackbar 通道的语义断言（实参类型由载体 List 元素类型决定）
    // ---------------------------------------------------------------------

    @Test
    fun stringArgChannel_resourcesUseStringPlaceholderOnly() {
        val strings = loadStrings()
        channelStringArgs.forEach { name ->
            val text = strings[name] ?: error("strings.xml 缺少资源：$name")
            assertTrue(
                "$name 走 String 实参通道，必须含 %s 占位符，实际为「$text」",
                stringPlaceholder.containsMatchIn(text),
            )
            assertFalse(
                "$name 走 String 实参通道，不能含 %d 占位符（会抛 IllegalFormatConversionException），实际为「$text」",
                numericPlaceholder.containsMatchIn(text),
            )
        }
    }

    @Test
    fun intArgChannel_resourcesUseNumericPlaceholder() {
        val strings = loadStrings()
        channelIntArgs.forEach { name ->
            val text = strings[name] ?: error("strings.xml 缺少资源：$name")
            assertTrue(
                "$name 走 Int 实参通道，必须含 %d 占位符，实际为「$text」",
                numericPlaceholder.containsMatchIn(text),
            )
            assertFalse(
                "$name 走 Int 实参通道，不应含 %s 占位符，实际为「$text」",
                stringPlaceholder.containsMatchIn(text),
            )
        }
    }

    /**
     * **直接复现历史崩溃**：用当年会崩的实参类型（String）去格式化 `msg_diet_filtered`。
     * 若占位符被改回 `%1$d`，本用例会抛 `IllegalFormatConversionException` 而失败。
     */
    @Test
    fun msgDietFiltered_formatsWithStringArg_doesNotThrow() {
        val text = loadStrings()["msg_diet_filtered"] ?: error("strings.xml 缺少资源：msg_diet_filtered")

        // 正是调用点传的实参：filteredCount.toString()
        val rendered = String.format(Locale.ROOT, text, "3")

        assertTrue("渲染结果应含过滤数，实际为「$rendered」", rendered.contains("3"))
    }

    // ---------------------------------------------------------------------
    // 占位符解析
    // ---------------------------------------------------------------------

    /** 数值占位符：`%d` 或定位式 `%1$d`。 */
    private val numericPlaceholder = Regex("""%(\d+\${'$'})?d""")

    /** 字符串占位符：`%s` 或定位式 `%1$s`。 */
    private val stringPlaceholder = Regex("""%(\d+\${'$'})?s""")

    /** 占位符正则：匹配百分号转义、定位式与非定位式占位符。 */
    private val placeholderRegex = Regex("%%|%((\\d+)\\\$)?([sd])")

    /** 源码中的资源引用：`R.string.xxx`。 */
    private val resourceRefRegex = Regex("""R\.string\.([a-z0-9_]+)""")

    /**
     * 解析文案中的占位符为有序 [ArgKind] 序列；无占位符返回 `null`。
     * 全部定位式（`%1$s`）按位置排序；全部非定位式按出现顺序；混用视为契约错误直接抛出。
     */
    private fun parsePlaceholders(text: String): List<ArgKind>? {
        data class Ph(val position: Int?, val kind: ArgKind)

        val found = placeholderRegex.findAll(text).mapNotNull { m ->
            if (m.value == "%%") {
                null
            } else {
                Ph(m.groupValues[2].toIntOrNull(), if (m.groupValues[3] == "s") ArgKind.STRING else ArgKind.NUMERIC)
            }
        }.toList()
        if (found.isEmpty()) return null

        val positions = found.mapNotNull { it.position }
        return when {
            positions.size == found.size -> {
                // 全定位式：按位置 1..n 排序，缺号/重号都是文案错误
                assertEquals(
                    "定位占位符应为 1..n 连续：$text",
                    (1..found.size).toList(),
                    positions.sorted(),
                )
                found.sortedBy { it.position }.map { it.kind }
            }
            positions.isEmpty() -> found.map { it.kind }
            else -> error("占位符定位式与非定位式混用（禁止）：$text")
        }
    }

    // ---------------------------------------------------------------------
    // 调用点扫描基础设施
    // ---------------------------------------------------------------------

    /** 一次字面量格式化调用的解析结果。 */
    private data class FormatCall(val formatArgCount: Int)

    /**
     * 找到 `R.string.x` 所在的字面量格式化调用（`stringResource(...)` / `getString(...)`），
     * 返回其中的**格式化实参个数**；资源引用不在任何此类调用内时返回 `null`。
     */
    private fun enclosingFormatCall(text: String, refStart: Int): FormatCall? {
        val callStart = maxOf(
            text.lastIndexOf("stringResource(", refStart),
            text.lastIndexOf("getString(", refStart),
        )
        if (callStart < 0) return null
        val openParen = callStart + "stringResource".length // 两个 token 等长，'(' 位置相同
        val closeParen = matchParen(text, openParen) ?: return null
        if (refStart !in openParen..closeParen) return null // 引用只是位于同一文件更靠后的位置

        val argText = text.substring(openParen + 1, closeParen)
        val parts = splitTopLevel(argText).filter { it.isNotBlank() }
        val resourcePartCount = parts.count { it.contains("R.string.") }
        return FormatCall(formatArgCount = (parts.size - resourcePartCount).coerceAtLeast(0))
    }

    /** 返回 `open` 处 '(' 的匹配 ')' 下标；引号内的括号不参与计数；找不到返回 `null`。 */
    private fun matchParen(text: String, open: Int): Int? {
        var depth = 0
        var i = open
        var inString = false
        while (i < text.length) {
            val c = text[i]
            when {
                inString && c == '\\' -> i++ // 跳过转义
                c == '"' -> inString = !inString
                !inString && c == '(' -> depth++
                !inString && c == ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    /** 按顶层逗号切分（忽略嵌套括号/引号内的逗号），保留原顺序。 */
    private fun splitTopLevel(text: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var inString = false
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inString && c == '\\' -> {
                    current.append(c)
                    if (i + 1 < text.length) current.append(text[i + 1])
                    i++
                }
                c == '"' -> {
                    inString = !inString
                    current.append(c)
                }
                !inString && c in "([{" -> {
                    depth++; current.append(c)
                }
                !inString && c in ")]}" -> {
                    depth--; current.append(c)
                }
                !inString && c == ',' && depth == 0 -> {
                    parts += current.toString(); current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        parts += current.toString()
        return parts
    }

    // ---------------------------------------------------------------------
    // 文件定位
    // ---------------------------------------------------------------------

    /** 解析 `strings.xml` 为 `name → 文本` 映射。 */
    private fun loadStrings(): Map<String, String> {
        val file = locateStringsXml()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        val map = LinkedHashMap<String, String>(nodes.length)
        for (i in 0 until nodes.length) {
            val el = nodes.item(i)
            val name = el.attributes?.getNamedItem("name")?.nodeValue ?: continue
            map[name] = el.textContent ?: ""
        }
        return map
    }

    /**
     * 定位 `strings.xml`。Gradle 单测的工作目录默认是**模块目录**（`app/`），但为稳妥起见，
     * 从 `user.dir` 起逐级向上探测两种相对路径，命中即返回。
     */
    private fun locateStringsXml(): File {
        val relativeCandidates = listOf(
            "src/main/res/values/strings.xml", // 工作目录 = app/
            "app/src/main/res/values/strings.xml", // 工作目录 = 仓库根/
        )
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val tried = mutableListOf<String>()
        while (dir != null) {
            relativeCandidates.forEach { rel ->
                val candidate = File(dir, rel)
                tried += candidate.absolutePath
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        error("未找到 strings.xml（user.dir=${System.getProperty("user.dir")}）；已尝试：\n" + tried.joinToString("\n"))
    }

    /** 从 strings.xml 位置反推主源码根目录（`src/main/java`，兼容 `src/main/kotlin`）。 */
    private fun kotlinSourceRoots(): List<File> {
        val stringsXml = locateStringsXml()
        val mainDir = stringsXml.parentFile // values
            ?.parentFile // res
            ?.parentFile // main
            ?: error("无法从 strings.xml 反推 main 目录：$stringsXml")
        return listOf("java", "kotlin")
            .map { File(mainDir, it) }
            .filter { it.isDirectory }
    }
}
