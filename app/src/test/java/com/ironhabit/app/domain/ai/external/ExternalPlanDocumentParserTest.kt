package com.ironhabit.app.domain.ai.external

import com.ironhabit.app.domain.model.AdviceSource
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.model.PlanReason
import com.ironhabit.app.domain.model.TrainingFocus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外部 AI 文档解析的纯 JVM 单测 —— 每条都钉死**一个**丢弃/钳制/拒收分支。
 *
 * 为什么测得这么细：这条路进来的文本是**用户自己从网页复制的**，没有任何服务端约束，
 * 每一次"静默少了几条"用户都无从察觉。所以每一个被丢掉的条目、每一个被动过的数字
 * 都必须在这里被点名，否则界面拿不到清单。
 */
class ExternalPlanDocumentParserTest {

    private fun exercise(id: Long, name: String, durationSec: Int? = null) = Exercise(
        id = id,
        name = name,
        category = if (durationSec != null) ExerciseCategory.CARDIO else ExerciseCategory.STRENGTH,
        muscleGroups = listOf("腿部"),
        isActive = true,
        defaultSets = 3,
        defaultReps = 12,
        defaultDurationSec = durationSec,
    )

    private val library: List<Exercise> = listOf(
        exercise(1L, "杠铃深蹲"),
        exercise(2L, "卧推"),
        exercise(3L, "引体向上"),
        exercise(4L, "椭圆机稳态", durationSec = 1200),
    )

    private fun doc(itemsJson: String, extra: String = ""): String =
        """{"schema":"${ExternalPlanSchema.SCHEMA}","days":[{"dayOfWeek":1,"focus":"FULL_BODY","items":[$itemsJson]}]$extra}"""

    private fun parsed(text: String): ExternalPlanDraft =
        when (val outcome = ExternalPlanDocumentParser.parse(text, library)) {
            is ExternalDocOutcome.Parsed -> outcome.draft
            is ExternalDocOutcome.Refused -> error("期望解析成功，实际整份拒收：${outcome.reason}")
        }

    private fun refused(text: String): ExternalDocRefusal =
        when (val outcome = ExternalPlanDocumentParser.parse(text, library)) {
            is ExternalDocOutcome.Refused -> outcome.reason
            is ExternalDocOutcome.Parsed -> error("期望整份拒收，实际解析成功：${outcome.draft}")
        }

    // ---------------- 名字 → id（外部 AI 只看得见名字） ----------------

    @Test
    fun parse_resolvesExerciseNameToLibraryId_andLabelsSourceHonestly() {
        val draft = parsed(
            doc("""{"exercise":"杠铃深蹲","targetSets":3,"targetReps":12,"targetWeightKg":80.0}"""),
        )

        val item = draft.proposal.days.single().items.single()
        assertEquals(1L, item.exerciseId)
        assertEquals(80.0f, item.targetWeightKg!!)
        assertEquals(TrainingFocus.FULL_BODY, draft.proposal.days.single().focus)
        assertEquals(
            "来源必须是外部导入：既不是 app 联网生成的，也不是本地规则算的",
            AdviceSource.EXTERNAL_AI_IMPORT,
            draft.proposal.source,
        )
        assertTrue(draft.notes.isEmpty())
    }

    @Test
    fun parse_nameMatching_isTrimmedAndCaseInsensitive() {
        val latin = listOf(exercise(9L, "Bulgarian Split Squat"))

        val draft = ExternalPlanDocumentParser.parse(
            doc("""{"exercise":"  bulgarian SPLIT squat ","targetSets":3,"targetReps":12}"""),
            latin,
        ) as ExternalDocOutcome.Parsed

        assertEquals(9L, draft.draft.proposal.days.single().items.single().exerciseId)
    }

    @Test
    fun parse_unknownExerciseName_isDroppedAndListedWithName() {
        val draft = parsed(
            doc(
                """
                {"exercise":"杠铃深蹲","targetSets":3,"targetReps":12},
                {"exercise":"太空漫步机等","targetSets":3,"targetReps":12}
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(1L), draft.proposal.days.single().items.map { it.exerciseId })
        val note = draft.notes.single()
        assertEquals(ExternalPlanNote.Kind.UNKNOWN_EXERCISE, note.kind)
        assertEquals(
            "必须把原样名字回显给用户，否则他不知道是哪一条被丢了",
            "太空漫步机等",
            note.subject,
        )
        assertEquals(1, note.dayOfWeek)
    }

    @Test
    fun parse_blankExerciseName_isRefusedButStillExplainsItself() {
        val outcome = ExternalPlanDocumentParser.parse(
            doc("""{"exercise":"   ","targetSets":3,"targetReps":12}"""),
            library,
        )

        val refused = outcome as ExternalDocOutcome.Refused
        assertEquals(ExternalDocRefusal.NO_USABLE_ITEMS, refused.reason)
        assertEquals(
            "全被挡掉时更要说清为什么 —— 清单不能跟着草案一起丢",
            listOf(ExternalPlanNote.Kind.BLANK_EXERCISE_NAME),
            refused.notes.map { it.kind },
        )
    }

    // ---------------- 整份拒收的五种原因 ----------------

    @Test
    fun parse_missingSchema_isRefusedAsWrongSchema() {
        val text = """{"days":[{"dayOfWeek":1,"items":[{"exercise":"杠铃深蹲","targetSets":3,"targetReps":12}]}]}"""

        assertEquals(ExternalDocRefusal.WRONG_SCHEMA, refused(text))
    }

    @Test
    fun parse_otherSchemaVersion_isRefusedAsWrongSchema() {
        // 用户可能粘的是别家工具/上一版模板的输出；"尽力读读看"会把不相干的内容混进这一周。
        val text = """{"schema":"ironhabit-plan-import/v99","days":[]}"""

        assertEquals(ExternalDocRefusal.WRONG_SCHEMA, refused(text))
    }

    @Test
    fun parse_blankAndGarbage_andOversized_areEachRefusedIdentifiably() {
        assertEquals(ExternalDocRefusal.EMPTY_DOCUMENT, refused("   "))
        assertEquals(ExternalDocRefusal.NOT_A_DOCUMENT, refused("这周练深蹲、卧推，好好练"))
        val huge = "x".repeat(ExternalPlanDocumentParser.MAX_DOC_BYTES + 1)
        assertEquals(ExternalDocRefusal.TOO_LARGE, refused(huge))
    }

    @Test
    fun parse_missingRequiredField_isRefusedNotHalfRead() {
        // targetSets 没有默认值：缺了就是"这份文档不像话"，不能拿默认值凑一条 3 组进去。
        assertEquals(
            ExternalDocRefusal.NOT_A_DOCUMENT,
            refused(doc("""{"exercise":"杠铃深蹲","targetReps":12}""")),
        )
    }

    @Test
    fun parse_everyItemRejected_isRefusedAsNoUsableItems_notAnEmptyWeek() {
        // 与内置 B-3 同口径：空草案 ≠ "用户这周什么都不练"。
        // 当成有效结果送去采纳，commit 那侧会把整周现有 AI 行当成"本次不再出现"回收掉。
        assertEquals(
            ExternalDocRefusal.NO_USABLE_ITEMS,
            refused(doc("""{"exercise":"库里没有的动作","targetSets":3,"targetReps":12}""")),
        )
    }

    // ---------------- 容忍：围栏、寒暄、未知字段 ----------------

    @Test
    fun parse_toleratesCodeFenceAndSurroundingProse() {
        val text = """
            好的，这是为你安排的一周计划：
            ```json
            ${doc("""{"exercise":"卧推","targetSets":4,"targetReps":8,"targetWeightKg":60.0}""")}
            ```
            祝训练顺利！
        """.trimIndent()

        val draft = parsed(text)

        assertEquals(listOf(2L), draft.proposal.days.single().items.map { it.exerciseId })
    }

    @Test
    fun parse_unknownFieldsFromModel_areIgnored() {
        val text = doc(
            """{"exercise":"卧推","targetSets":4,"targetReps":8,"why":"因为你好久没练胸了","restSec":90}""",
            ""","confidence":0.9,"notes":"随便加"""",
        )

        assertEquals(listOf(2L), parsed(text).proposal.days.single().items.map { it.exerciseId })
    }

    // ---------------- 数值防线：钳制要留痕，越界不许原样入库 ----------------

    @Test
    fun parse_outOfRangeSetsAndReps_areClampedAndListed() {
        val draft = parsed(
            doc(
                """
                {"exercise":"杠铃深蹲","targetSets":40,"targetReps":12},
                {"exercise":"卧推","targetSets":3,"targetReps":500}
                """.trimIndent(),
            ),
        )

        val items = draft.proposal.days.single().items
        // 31 = 逐组打卡位图宽度：写成 40 组的行永远勾不满，渐进超负荷在那一行上永久失效。
        assertEquals(31, items[0].targetSets)
        assertEquals(100, items[1].targetReps)
        assertEquals(
            listOf(ExternalPlanNote.Kind.SETS_CLAMPED, ExternalPlanNote.Kind.REPS_CLAMPED),
            draft.notes.map { it.kind },
        )
        assertEquals(listOf(40, 31), draft.notes[0].args)
        assertEquals(listOf(500, 100), draft.notes[1].args)
    }

    @Test
    fun parse_nonPositiveWeight_becomesBodyweightNotNullZero() {
        val draft = parsed(
            doc(
                """
                {"exercise":"引体向上","targetSets":3,"targetReps":8,"targetWeightKg":-5.0},
                {"exercise":"卧推","targetSets":3,"targetReps":8,"targetWeightKg":0.0}
                """.trimIndent(),
            ),
        )

        assertEquals(
            listOf(null, null),
            draft.proposal.days.single().items.map { it.targetWeightKg },
        )
    }

    @Test
    fun parse_duration_comesFromLocalLibraryNotFromModel() {
        val text = """
            {"schema":"${ExternalPlanSchema.SCHEMA}","days":[{"dayOfWeek":1,"focus":"CARDIO_CORE","items":[
                {"exercise":"椭圆机稳态","targetSets":1,"targetReps":1,"durationMin":999}]}]}
        """.trimIndent()

        val item = parsed(text).proposal.days.single().items.single()

        // 模型编的 999 分钟不信；回本地库按 1200 秒换算 = 20 分钟（修复 C3 的同一件事）。
        assertEquals(20, item.targetDurationMin)
    }

    @Test
    fun parse_dayOfWeekOutOfRange_isClampedIntoWeek() {
        val text = """{"schema":"${ExternalPlanSchema.SCHEMA}","days":[{"dayOfWeek":9,"items":[
            {"exercise":"杠铃深蹲","targetSets":3,"targetReps":12}]}]}"""

        assertEquals(7, parsed(text).proposal.days.single().dayOfWeek)
    }

    // ---------------- 单日上限与同日重复 ----------------

    @Test
    fun parse_moreThanDailyCap_keepsTheFirstOnesAndListsTheRest() {
        val bigLibrary: List<Exercise> = (1..20L).map { id -> exercise(id, "动作$id") }
        val twentyItems: String = (1..20L).joinToString(",") {
            """{"exercise":"动作$it","targetSets":3,"targetReps":12}"""
        }

        val outcome = ExternalPlanDocumentParser.parse(
            """{"schema":"${ExternalPlanSchema.SCHEMA}","days":[{"dayOfWeek":2,"items":[$twentyItems]}]}""",
            bigLibrary,
        ) as ExternalDocOutcome.Parsed

        assertEquals(ExternalPlanDocumentParser.MAX_ITEMS_PER_DAY, outcome.draft.proposal.days.single().items.size)
        val overflow = outcome.draft.notes.filter { it.kind == ExternalPlanNote.Kind.OVER_DAILY_LIMIT }
        assertEquals(20 - ExternalPlanDocumentParser.MAX_ITEMS_PER_DAY, overflow.size)
        assertEquals(2, overflow.first().dayOfWeek)
        // 超出的是"哪些"也要点名，不能只报个数字。
        assertEquals(
            "动作${ExternalPlanDocumentParser.MAX_ITEMS_PER_DAY + 1}",
            overflow.first().subject,
        )
    }

    @Test
    fun parse_duplicateInSameDay_keepsFirstAndListsSecond() {
        val draft = parsed(
            doc(
                """
                {"exercise":"杠铃深蹲","targetSets":3,"targetReps":12},
                {"exercise":"杠铃深蹲","targetSets":5,"targetReps":5}
                """.trimIndent(),
            ),
        )

        val items = draft.proposal.days.single().items
        assertEquals("同日同动作只留第一条", 1, items.size)
        assertEquals(3, items.single().targetSets)
        assertEquals(ExternalPlanNote.Kind.DUPLICATE_EXERCISE, draft.notes.single().kind)
    }

    @Test
    fun parse_firstItemOfDayIsPrimaryLiftRestSupplement() {
        val draft = parsed(
            doc(
                """
                {"exercise":"杠铃深蹲","targetSets":3,"targetReps":12},
                {"exercise":"卧推","targetSets":3,"targetReps":12}
                """.trimIndent(),
            ),
        )

        assertEquals(
            listOf(PlanReason.PRIMARY_LIFT, PlanReason.SUPPLEMENT),
            draft.proposal.days.single().items.map { it.reason },
        )
    }

    // ---------------- 档案段：实测值点名拒收，允许的部分说"没应用" ----------------

    @Test
    fun parse_profileBodyMeasurements_areNamedAndRefused_whilePlanStillImports() {
        val draft = parsed(
            doc(
                """{"exercise":"杠铃深蹲","targetSets":3,"targetReps":12}""",
                ""","profile":{"heightCm":178,"age":29,"bodyFatPct":18.0,"weightKg":74.5,"gender":"MALE"}""",
            ),
        )

        val forbidden = draft.notes.filter { it.kind == ExternalPlanNote.Kind.PROFILE_FIELD_FORBIDDEN }
        assertEquals(
            listOf(
                ExternalPlanSchema.FIELD_HEIGHT_CM,
                ExternalPlanSchema.FIELD_AGE,
                ExternalPlanSchema.FIELD_BODY_FAT_PCT,
                ExternalPlanSchema.FIELD_WEIGHT_KG,
                ExternalPlanSchema.FIELD_GENDER,
            ),
            forbidden.map { it.subject },
        )
        // 拒收档案不等于作废整份文档 —— 计划照常导入，否则用户白问一次。
        assertEquals(listOf(1L), draft.proposal.days.single().items.map { it.exerciseId })
    }

    @Test
    fun parse_profileAllowedFields_areListedAsNotAppliedThisVersion() {
        val draft = parsed(
            doc(
                """{"exercise":"杠铃深蹲","targetSets":3,"targetReps":12}""",
                ""","profile":{"goal":"BULK","trainingDaysPerWeek":5}""",
            ),
        )

        val note = draft.notes.single { it.kind == ExternalPlanNote.Kind.PROFILE_NOT_APPLIED }
        assertEquals(listOf(2), note.args)
        assertTrue(
            "允许字段不进拒收名单",
            draft.notes.none { it.kind == ExternalPlanNote.Kind.PROFILE_FIELD_FORBIDDEN },
        )
    }

    @Test
    fun parse_analysis_isTrimmedAndBlankBecomesNull() {
        val blank = parsed(
            doc("""{"exercise":"杠铃深蹲","targetSets":3,"targetReps":12}""", ""","analysis":"   """")
        )
        val real = parsed(
            doc("""{"exercise":"杠铃深蹲","targetSets":3,"targetReps":12}""", ""","analysis":"  本周加重。  """")
        )

        assertNull(blank.proposal.analysis)
        assertEquals("本周加重。", real.proposal.analysis)
    }

    @Test
    fun parse_dayWithoutUsableItems_doesNotAppearAsADay() {
        // 整天都是幻觉名 → 整天不进 days（否则预览页会出现一个"0 组"的空天）。
        val text = """
            {"schema":"${ExternalPlanSchema.SCHEMA}","days":[
                {"dayOfWeek":1,"items":[{"exercise":"不存在的动作","targetSets":3,"targetReps":12}]},
                {"dayOfWeek":3,"items":[{"exercise":"卧推","targetSets":3,"targetReps":12}]}]}
        """.trimIndent()

        val days = parsed(text).proposal.days

        assertEquals(listOf(3), days.map { it.dayOfWeek })
    }
}
