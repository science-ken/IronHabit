package com.ironhabit.app.domain.usecase

import com.ironhabit.app.di.IoDispatcher
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ProfileLimits
import com.ironhabit.app.domain.model.WeeklyReview
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.SettingsRepository
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * **把一周的数据导出成一段 JSON「数据包」**（P2，零联网、零 token）。
 *
 * 用途：用户可以把它复制给**任何** AI 看（不用填 API Key），将来 App 直连 DeepSeek 时也复用同一份结构
 * （`docs/ai-coach-local.md` 的"两条路都开着"）。
 *
 * ## 🔒 不变量
 * 1. **只读**：只读档案与动作库，不写任何东西。
 * 2. **字段名/嵌套是接口**：`ironhabit-week-package/v1` 的结构一旦发出就**不能再改**
 *    （第三方 AI 按它读），所以这里用 `@Serializable` DTO 而不是手拼字符串 —— 改字段名会立刻在
 *    `ExportWeekPackageUseCaseTest` 的"能读回来"用例上炸。
 * 3. **不省略字段**：拿不到的值输出 JSON `null`（`explicitNulls = true`），
 *    而不是把字段删掉 —— 删字段会让下游分不清"没有这项"和"这项没数据"。
 * 4. **不泄露私密信息**：这里**没有** API Key、没有设备标识、没有账号；只有用户自己填的档案与训练数据。
 *
 * ## JSON 结构（v1，冻结合同）
 * ```json
 * {
 *   "schema": "ironhabit-week-package/v1",
 *   "generatedAtEpochMillis": 1758000000000,
 *   "profile": { "gender":"MALE", "age":30, "heightCm":178, "bodyFatPct":22.0,
 *                "goal":"BULK", "goalWeightKg":80.0, "equipment":["DUMBBELL"],
 *                "injuryAreas":["KNEE"], "injuryNote":"深蹲到底右膝有点顶" },
 *   "week": { "from":"2026-09-14", "to":"2026-09-20",
 *             "days":[ { "date":"2026-09-15", "done":true,
 *                        "items":[ { "exercise":"杠铃深蹲", "sets":4, "reps":8,
 *                                    "weightKg":42.5, "rpe":6, "note":null } ] } ] },
 *   "summary": { "attendance":"3/3", "totalVolumeKg":12480.0, "avgRpe":7.2,
 *                "weightDeltaKg":-0.4,
 *                "stalled":[ { "exercise":"卧推", "stagnantWeeks":3 } ],
 *                "progressed":[ { "exercise":"杠铃深蹲",
 *                                 "previousWeightKg":40.0, "latestWeightKg":42.5 } ] },
 *   "library": [ { "name":"杠铃深蹲", "category":"STRENGTH", "muscleGroups":["腿部"] } ]
 * }
 * ```
 *
 * ⚠️ 与预览稿的一处偏差（已获批）：预览示例里的 `streakDays` **已删除** ——
 * 连续打卡是「今日」页的指标，混进"这一周的数据包"没有意义，只会多占 token。
 *
 * @param includeDetails `false` = 只导出汇总（省 token），`days` 输出空数组
 */
class ExportWeekPackageUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val exerciseRepository: ExerciseRepository,
    private val clock: Clock,
    private val timeZone: TimeZone,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    @OptIn(ExperimentalSerializationApi::class)
    private val prettyJson: Json = Json {
        prettyPrint = true
        explicitNulls = true
    }

    /** 紧凑版：给提问模板用（见 [invoke] 的 `pretty` 说明）。 */
    @OptIn(ExperimentalSerializationApi::class)
    private val compactJson: Json = Json {
        explicitNulls = true
    }

    /**
     * @param pretty `true` = 缩进换行（「AI 会看到什么」弹层要给人读）；
     *   `false` = 紧凑一行。**拼进提问模板时必须用 false**：整段模板要粘进聊天框，
     *   输入被截断时丢掉的是**尾部**，而 `library` 恰好排在最后 —— 缩进把字节数放大一倍多，
     *   等于把"动作清单被截掉"的概率翻倍。字段名与嵌套两种模式完全一致（那才是合同）。
     */
    suspend operator fun invoke(
        review: WeeklyReview,
        includeDetails: Boolean = true,
        pretty: Boolean = true,
    ): String {
        val profile = settingsRepository.profile().first()
        val library: List<Exercise> = exerciseRepository.observeActive().first()

        val dto = WeekPackageDto(
            schema = SCHEMA,
            generatedAtEpochMillis = clock.now().toEpochMilliseconds(),
            profile = ProfileDto(
                gender = profile.gender?.name,
                age = profile.age,
                heightCm = profile.heightCm,
                bodyFatPct = profile.bodyFatPct,
                goal = profile.goal.name,
                goalWeightKg = profile.goalWeightKg,
                equipment = profile.equipment.map { it.name }.sorted(),
                injuryAreas = profile.injuryAreas.map { it.name }.sorted(),
                injuryNote = profile.injuryNote,
                trainingDaysPerWeek = ProfileLimits.coerceTrainingDaysPerWeek(profile.trainingDaysPerWeek),
            ),
            week = WeekDto(
                from = review.weekStartEpochDay.toIsoDate(),
                to = review.weekEndEpochDay.toIsoDate(),
                days = if (includeDetails) {
                    review.days.map { day ->
                        DayDto(
                            date = day.dateEpochDay.toIsoDate(),
                            done = day.items.isNotEmpty(),
                            items = day.items.map { item ->
                                ItemDto(
                                    exercise = item.exerciseName,
                                    sets = item.sets,
                                    reps = item.reps,
                                    weightKg = item.weightKg,
                                    rpe = item.rpe,
                                    note = item.note,
                                )
                            },
                        )
                    }
                } else {
                    emptyList()
                },
            ),
            summary = SummaryDto(
                attendance = "${review.training.completedDays}/${review.training.plannedDays}",
                // ⚠️ 必须圆到 1 位小数：Float 减法会带精度噪声（74.2 − 74.6 = −0.40000153），
                // 直接把噪声原样发给 AI，会让人以为数据坏了。
                totalVolumeKg = review.training.totalVolumeKg.roundTo1(),
                // 同层三个浮点字段统一口径：`WeeklyReview` 是公开模型，任何调用方都能构造，
                // 不能假设上游已经圆过（复核报告 F-3 指出的不一致）。
                avgRpe = review.training.avgRpe?.roundTo1(),
                weightDeltaKg = review.body.deltaKg?.roundTo1(),
                dietAvgKcal = review.diet.avgKcal,
                dietAvgProteinG = review.diet.avgProteinG,
                stalled = review.training.stalled.map { trend ->
                    StalledDto(exercise = trend.exerciseName, stagnantWeeks = trend.stagnantWeeks)
                },
                progressed = review.training.progressed.map { trend ->
                    ProgressedDto(
                        exercise = trend.exerciseName,
                        previousWeightKg = trend.previousWeightKg,
                        latestWeightKg = trend.latestWeightKg,
                    )
                },
            ),
            // 动作库按名字去重 + 排序：AI 优先从这里挑动作（库里没有的才用 newExercise 提新动作）。
            library = library
                .map { exercise ->
                    LibraryItemDto(
                        name = exercise.name.trim(),
                        category = exercise.category.name,
                        muscleGroups = exercise.muscleGroups,
                    )
                }
                .filter { item -> item.name.isNotEmpty() }
                .distinctBy { item -> item.name }
                .sortedBy { item -> item.name },
        )

        // 序列化与仓库读取都放 IO：字符串可能几百 KB（明细全量时），别占主线程。
        val encoder: Json = if (pretty) prettyJson else compactJson
        return withContext(ioDispatcher) { encoder.encodeToString(WeekPackageDto.serializer(), dto) }
    }

    /** epochDay → ISO 日期（`"2026-09-14"`）。仓库层用 `Long`，本机 kotlinx-datetime 用 `Int`。 */
    private fun Long.toIsoDate(): String = LocalDate.fromEpochDays(toInt()).toString()

    /** 圆到 1 位小数（去掉 Float 精度噪声，与界面显示口径一致）。 */
    private fun Float.roundTo1(): Float = (this * 10).roundToInt() / 10f

    // ---------------- 冻结的 JSON DTO（字段名 = 合同） ----------------

    @Serializable
    private data class WeekPackageDto(
        val schema: String,
        val generatedAtEpochMillis: Long,
        val profile: ProfileDto,
        val week: WeekDto,
        val summary: SummaryDto,
        val library: List<LibraryItemDto>,
    )

    @Serializable
    private data class ProfileDto(
        val gender: String?,
        val age: Int?,
        val heightCm: Int?,
        val bodyFatPct: Float?,
        val goal: String,
        val goalWeightKg: Float?,
        val equipment: List<String>,
        val injuryAreas: List<String>,
        val injuryNote: String?,
        /** P1 起档案里有"每周训练天数"（`3–6`）—— AI 排课时必须知道它。 */
        val trainingDaysPerWeek: Int,
    )

    @Serializable
    private data class WeekDto(
        val from: String,
        val to: String,
        val days: List<DayDto>,
    )

    @Serializable
    private data class DayDto(
        val date: String,
        val done: Boolean,
        val items: List<ItemDto>,
    )

    @Serializable
    private data class ItemDto(
        val exercise: String,
        val sets: Int,
        val reps: Int,
        val weightKg: Float?,
        val rpe: Int?,
        val note: String?,
    )

    @Serializable
    private data class SummaryDto(
        /** `"完成天数/计划天数"`，例如 `"3/3"`。 */
        val attendance: String,
        val totalVolumeKg: Float,
        val avgRpe: Float?,
        val weightDeltaKg: Float?,
        val dietAvgKcal: Int?,
        val dietAvgProteinG: Int?,
        val stalled: List<StalledDto>,
        val progressed: List<ProgressedDto>,
    )

    @Serializable
    private data class StalledDto(
        val exercise: String,
        val stagnantWeeks: Int,
    )

    @Serializable
    private data class ProgressedDto(
        val exercise: String,
        val previousWeightKg: Float?,
        val latestWeightKg: Float?,
    )

    @Serializable
    private data class LibraryItemDto(
        val name: String,
        val category: String,
        val muscleGroups: List<String>,
    )

    companion object {
        /** 数据包格式标识（改结构必须换版本号，下游按它决定怎么读）。 */
        const val SCHEMA: String = "ironhabit-week-package/v1"
    }
}
