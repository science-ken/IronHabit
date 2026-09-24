package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.ai.external.ExternalPlanSchema
import com.ironhabit.app.domain.model.ProfileLimits
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.model.WeeklyReview
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.PlanRepository
import com.ironhabit.app.domain.repository.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate

/**
 * 产出**复制给用户带去外部 AI 的那段提问模板**（纯本地拼字符串，零网络、零 token）。
 *
 * 三段拼一起：固定指令（含回程合同的字段说明）+ 现有 `ironhabit-week-package/v1` 数据包原文
 * + **目标周已经排了什么**的摘要。
 *
 * ## 为什么要补第三段
 * 数据包里没有 `existing`（本周现有计划）—— 它是为"复盘这一周练得怎么样"设计的。
 * 不告诉外部 AI 现状，它会把上版生成的动作原样再排一遍、或者把 7 天全排满。
 * 投影器的保护规则只挡**手改行**，挡不住这种重复，所以这道该在提示词里做。
 *
 * ## 为什么这里可以有中文
 * 和 `RemotePromptBuilder` 同一条豁免：这是**给模型的指令**，不是界面文案（架构 §7.5 管的是后者）。
 * 用户视角的那几句说明（怎么复制、粘回哪里）在 `strings.xml` 里。
 *
 * ⚠️ 模板里的字段名是**合同**（`schema` / `days` / `exercise` / `targetSets`…），
 * 改动必须和 [com.ironhabit.app.domain.ai.external.ExternalPlanDocumentParser] 同时改、
 * 同时换版本号 —— 已经发出去的旧模板还在用户手里，删掉兼容等于让那些文档读不回来。
 */
class BuildExternalCoachPromptUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val planRepository: PlanRepository,
    private val exerciseRepository: ExerciseRepository,
    private val exportWeekPackage: ExportWeekPackageUseCase,
) {

    /**
     * @param review 当前展示的那一周复盘（数据包直接从它序列化；VM 状态里已经有，不重算）
     * @param targetWeekStartEpochDay 用户挑定要写进哪一周（本周 / 下周）
     */
    suspend operator fun invoke(review: WeeklyReview, targetWeekStartEpochDay: Long): String {
        val profile = settingsRepository.profile().first()
        val daysPerWeek: Int = ProfileLimits.coerceTrainingDaysPerWeek(profile.trainingDaysPerWeek)
        // 紧凑版：模板要整段粘进聊天框，缩进多出来的字节就是把 library 被截断的概率翻倍。
        val packageJson: String = exportWeekPackage(review, includeDetails = true, pretty = false)

        val weekRows: List<WeekPlan> = planRepository.getRowsForWeek(targetWeekStartEpochDay)
        val names: Map<Long, String> = exerciseRepository.observeActive().first()
            .associate { exercise -> exercise.id to exercise.name.trim() }

        return TEMPLATE
            .replace(PLACEHOLDER_DAYS, daysPerWeek.toString())
            .replace(PLACEHOLDER_SCHEMA, ExternalPlanSchema.SCHEMA)
            .replace(PLACEHOLDER_WEEK, weekLabel(targetWeekStartEpochDay))
            .replace(PLACEHOLDER_EXISTING, existingSummary(weekRows, names))
            .replace(PLACEHOLDER_PACKAGE, packageJson)
    }

    /** 目标周的日期范围（模型没有"今天"的概念，必须给绝对日期）。 */
    private fun weekLabel(weekStartEpochDay: Long): String {
        val from: String = LocalDate.fromEpochDays(weekStartEpochDay.toInt()).toString()
        val to: String = LocalDate.fromEpochDays(weekStartEpochDay.toInt() + 6).toString()
        return "$from ~ $to"
    }

    /**
     * 那一周已经排了什么。
     *
     * 没专属行的天写「沿用每周相同模板」而不是留空 —— 留空会被模型读成"这天没安排，可以随便排"，
     * 而那几天其实由模板负责、导入时也不会被覆盖。
     */
    private fun existingSummary(weekRows: List<WeekPlan>, names: Map<Long, String>): String {
        val activeByDay: Map<Int, List<WeekPlan>> = weekRows
            .filter { plan -> plan.isActive }
            .groupBy { plan -> plan.dayOfWeek }

        if (activeByDay.isEmpty()) return "（这一周还没有任何已排好的训练，7 天都可以安排）"

        return buildString {
            for (day in MIN_DAY..MAX_DAY) {
                val rows: List<WeekPlan>? = activeByDay[day]
                if (rows == null) {
                    appendLine("周$day：沿用「每周相同」模板，未单独排")
                } else {
                    appendLine("周$day：" + rows.joinToString("、") { row -> describe(row, names) })
                }
            }
        }.trimEnd()
    }

    private fun describe(row: WeekPlan, names: Map<Long, String>): String {
        val name: String = names[row.exerciseId] ?: "（库里已无此动作）"
        val load: String = if (row.targetDurationMin != null) {
            "${row.targetDurationMin}分钟"
        } else {
            val weight: String = row.targetWeightKg?.let { "×${it}kg" } ?: ""
            "${row.targetSets}组×${row.targetReps}次$weight"
        }
        return "$name $load"
    }

    private companion object {
        const val MIN_DAY = 1
        const val MAX_DAY = 7

        const val PLACEHOLDER_DAYS = "{{DAYS}}"
        const val PLACEHOLDER_SCHEMA = "{{SCHEMA}}"
        const val PLACEHOLDER_WEEK = "{{WEEK}}"
        const val PLACEHOLDER_EXISTING = "{{EXISTING}}"
        const val PLACEHOLDER_PACKAGE = "{{PACKAGE}}"

        val TEMPLATE: String = """
帮我为下一周安排训练计划。**只输出一个 JSON 对象**，我要把它原样粘回我的健身 App 导入。

【输出硬要求】
1. 只输出 JSON：不要 Markdown 代码块围栏，不要任何解释文字、不要前后寒暄。
2. 顶层必须有 "schema":"{{SCHEMA}}"，逐字照抄，不要改大小写或版本号。
3. days 数组每项是一天：{"dayOfWeek":1,"focus":"FULL_BODY","items":[…]}
   • dayOfWeek 取 1..7（1=周一，7=周日）；只安排 {{DAYS}} 个训练日，不要多排，也不要排满 7 天。
   • focus 只能取：FULL_BODY / LOWER_BODY / UPPER_PUSH / UPPER_PULL / CARDIO_CORE。
4. items 每项是一个动作：{"exercise":"动作名","targetSets":4,"targetReps":8,"targetWeightKg":80.0}
   • exercise 必须**逐字**取自下面【我的数据】里 library 数组的 name；库里没有的动作一律不要写，也不要建议新动作。
   • targetSets 是 1..31 的整数，targetReps 是 1..100 的整数；自重动作 targetWeightKg 填 null。
   • 组数/次数/重量按【我的数据】里真有的字段定：week.days[].items 是我实际举起的重量与 RPE，summary.progressed 是本周加过重的动作，summary.stalled 是卡住没动的动作。做满且 RPE 偏低可以小幅加重。
5. analysis：1~3 句简体中文，说明这份计划为什么这样排（App 会原样显示给用户看）。
6. 只输出上面这些字段。不要输出、也不要建议修改任何身体测量数据（身高、体重、体脂、年龄）或档案设置——App 这一版只导入训练计划。
7. 如果你在上面【我的数据】里找不到 library 数组（那是我能用的动作清单），**不要**只回一个空壳计划：请在 analysis 里直接说"没收到 library"，并告诉用户重新复制模板整段再发一次。

【我的数据】下面这段 JSON 里有三样你要用到的东西：**library**（我能用的动作清单，只能逐字用里面的 name）、**profile**（目标 / 器械 / 伤病）、**week 与 summary**（这一周实际练了什么、哪些动作在进步、哪些卡住了）。
{{PACKAGE}}

【要写进的那一周】{{WEEK}}，它目前已经有：
{{EXISTING}}
这些不要重复排、也不要删；要改就改组数次数重量。

【输出示例（只示意结构，动作名换成我库里的）】
{"schema":"{{SCHEMA}}","analysis":"结合你的增肌目标与上周深蹲做满且强度有余量，本周加重并补一个髋部动作。","days":[{"dayOfWeek":1,"focus":"LOWER_BODY","items":[{"exercise":"杠铃深蹲","targetSets":4,"targetReps":8,"targetWeightKg":80.0}]}]}
        """.trimIndent()
    }
}
