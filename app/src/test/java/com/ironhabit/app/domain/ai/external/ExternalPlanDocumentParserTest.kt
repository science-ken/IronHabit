package com.ironhabit.app.domain.ai.external

import com.ironhabit.app.domain.model.AdviceSource
import com.ironhabit.app.domain.model.DietRestriction
import com.ironhabit.app.domain.model.Equipment
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.model.Food
import com.ironhabit.app.domain.model.Goal
import com.ironhabit.app.domain.model.InjuryArea
import com.ironhabit.app.domain.model.MealType
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
    fun parse_unknownExerciseName_becomesACreatableCandidate_andIsListedWithName() {
        // 刀 5 取消门票：陌生名不再是"静默丢掉"，而是"要不要建进库"的一次确认。
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
        assertEquals(ExternalPlanNote.Kind.EXERCISE_CREATABLE, note.kind)
        assertEquals(
            "必须把原样名字回显给用户，否则他不知道是哪一条没进来",
            "太空漫步机等",
            note.subject,
        )
        assertEquals(1, note.dayOfWeek)
        val candidate = draft.newExercises.single()
        assertEquals("太空漫步机等", candidate.name)
        assertEquals("没声明就没有分类：按 CUSTOM 进候选，用户建完可自己改", ExerciseCategory.CUSTOM, candidate.category)
        assertTrue(candidate.muscleGroups.isEmpty())
    }

    @Test
    fun parse_deactivatedExercise_isListedAsInactive_andNeverOfferedAsANewExercise() {
        // 停用行若算"库里没有"：建库撞 exercises.name UNIQUE → 被跳过 → 重解析还是"库里没有" → 死循环。
        val withInactive: List<Exercise> = library + exercise(9L, "杠铃硬举").copy(isActive = false)
        val outcome = ExternalPlanDocumentParser.parse(
            doc("""{"exercise":"杠铃深蹲","targetSets":3,"targetReps":12},{"exercise":"杠铃硬举","targetSets":3,"targetReps":12}"""),
            withInactive,
        ) as ExternalDocOutcome.Parsed

        assertEquals(listOf(ExternalPlanNote.Kind.EXERCISE_INACTIVE), outcome.draft.notes.map { it.kind })
        assertTrue("停用行不能变成建库候选", outcome.draft.newExercises.isEmpty())
        assertEquals(listOf(1L), outcome.draft.proposal.days.single().items.map { it.exerciseId })
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

    @Test
    fun parse_documentWithNoItemsAtAll_isEmptyPlanAndKeepsWhatTheModelSaid() {
        // 真机实测到的那份输出：三天都是空 items，模型在 analysis 里说了"没收到数据"。
        // 这必须和"有条目但全被挡掉"分开 —— 前者要重发模板，后者要改动作名。
        val text = """
            {"schema":"${ExternalPlanSchema.SCHEMA}","days":[
                {"dayOfWeek":1,"focus":"FULL_BODY","items":[]},
                {"dayOfWeek":3,"focus":"LOWER_BODY","items":[]}],
             "analysis":"未收到 library 与 history 数据，无法逐字匹配动作名，因此只生成三个训练日框架。"}
        """.trimIndent()

        val outcome = ExternalPlanDocumentParser.parse(text, library)

        val refused = outcome as ExternalDocOutcome.Refused
        assertEquals(ExternalDocRefusal.NOTHING_TO_IMPORT, refused.reason)
        assertEquals(
            "模型自己那句话是唯一线索，不能丢",
            true,
            refused.analysis?.contains("未收到 library"),
        )
    }

    @Test
    fun parse_emptyDaysArray_isEmptyPlanNotAQuietSuccess() {
        val text = """{"schema":"${ExternalPlanSchema.SCHEMA}","days":[]}"""

        val refused = ExternalPlanDocumentParser.parse(text, library) as ExternalDocOutcome.Refused

        assertEquals(ExternalDocRefusal.NOTHING_TO_IMPORT, refused.reason)
    }

    // ---------------- 每条动作的"为什么"（刀 3）----------------

    @Test
    fun parse_itemReason_isKeptForThePreview_withoutTouchingTheNumbers() {
        val draft = parsed(
            doc("""{"exercise":"杠铃深蹲","targetSets":3,"targetReps":12,"reason":"上周做满且 RPE 6"}"""),
        )

        val item = draft.proposal.days.single().items.single()
        assertEquals("上周做满且 RPE 6", item.explanation)
        assertEquals("理由只是文字，不参与任何数字", 3, item.targetSets)
    }

    @Test
    fun parse_overLongItemReason_isTruncatedNotDropped() {
        val long: String = "顶".repeat(ExternalPlanDocumentParser.MAX_REASON_CHARS + 140)

        val draft = parsed(doc("""{"exercise":"杠铃深蹲","targetSets":3,"targetReps":12,"reason":"$long"}"""))

        val kept: String? = draft.proposal.days.single().items.single().explanation
        assertEquals(
            "整段丢掉等于这条没理由；留前半句才是理由本体",
            ExternalPlanDocumentParser.MAX_REASON_CHARS,
            kept?.length,
        )
    }

    @Test
    fun parse_blankItemReason_becomesNull_soTheToggleDoesNotAppear() {
        val draft = parsed(doc("""{"exercise":"杠铃深蹲","targetSets":3,"targetReps":12,"reason":"   "}"""))

        assertNull(draft.proposal.days.single().items.single().explanation)
    }

    @Test
    fun parse_droppedItem_takesItsReasonAlong() {
        // 库里没有、又没建库 → 整条不进草案，它那句理由也跟着消失（否则会出现"给一条不存在的动作解释"）。
        val outcome = ExternalPlanDocumentParser.parse(
            doc("""{"exercise":"不存在的动作","targetSets":3,"targetReps":12,"reason":"很有道理"}"""),
            library,
        )

        val refused = outcome as ExternalDocOutcome.Refused
        assertEquals(ExternalDocRefusal.NO_USABLE_ITEMS, refused.reason)
        assertEquals(
            listOf(ExternalPlanNote.Kind.EXERCISE_CREATABLE),
            refused.notes.map { it.kind },
        )
    }

    // ---------------- 新动作声明（刀 4）----------------

    private val newExercise: String =
        """{"name":"保加利亚分腿蹲","category":"STRENGTH","muscleGroups":["腿部","臀部"]}"""

    /** 一条库里已有的 + 一条要新建的：这样文档整体仍然可解析（全未知会整份拒收）。 */
    private val mixedItems: String =
        """{"exercise":"杠铃深蹲","targetSets":3,"targetReps":12},""" +
            """{"exercise":"保加利亚分腿蹲","targetSets":3,"targetReps":12}"""

    @Test
    fun parse_declaredNewExercise_becomesACandidate_andTheItemIsMarkedCreatable() {
        val draft = parsed(
            doc(mixedItems, ""","newExercises":[$newExercise]"""),
        )

        val candidate = draft.newExercises.single()
        assertEquals("保加利亚分腿蹲", candidate.name)
        assertEquals(ExerciseCategory.STRENGTH, candidate.category)
        assertEquals(listOf("腿部", "臀部"), candidate.muscleGroups)
        assertEquals(
            "条目本身不进草案（库里还没有它的 id），但要说清「勾一下就能导进来」",
            listOf(ExternalPlanNote.Kind.EXERCISE_CREATABLE),
            draft.notes.map { it.kind },
        )
        assertEquals(
            "库里已有的那条照常进草案",
            listOf(1L),
            draft.proposal.days.single().items.map { it.exerciseId },
        )
    }

    @Test
    fun parse_undeclaredUnknownName_isStillCreatable() {
        // 这一条钉的就是刀 5 改掉的那个门票：旧行为是"没声明 → 静默丢掉"，
        // 而 shipped 模板里那句「也不要建议新动作」正好在劝模型别声明 —— 于是用户少了一条还看不出原因。
        val draft = parsed(doc(mixedItems))

        assertEquals(listOf("保加利亚分腿蹲"), draft.newExercises.map { it.name })
        assertEquals(ExternalPlanNote.Kind.EXERCISE_CREATABLE, draft.notes.single().kind)
    }

    @Test
    fun parse_newExerciseWithUnknownCategory_defaultsToCustom_andSaysSo() {
        val draft = parsed(
            doc(
                mixedItems,
                ""","newExercises":[{"name":"保加利亚分腿蹲","category":"FLEXIBILITY","muscleGroups":["腿部"]}]""",
            ),
        )

        val candidate = draft.newExercises.single()
        assertEquals(
            "分类不认识不再整条拒收（门票取消后拒收=永远建不了），改成按 CUSTOM 建 + 点名",
            ExerciseCategory.CUSTOM,
            candidate.category,
        )
        assertEquals(listOf("腿部"), candidate.muscleGroups)
        val note = draft.notes.single { it.kind == ExternalPlanNote.Kind.NEW_EXERCISE_CATEGORY_DEFAULTED }
        assertEquals("FLEXIBILITY", note.args.single())
    }

    @Test
    fun parse_newExerciseWithInventedMuscleLabel_dropsThatLabelAndNamesIt() {
        // 旧行为是整条拒收，理由是"有分类没肌群会静默失效"；但门票取消后整条丢的代价变成
        // "这个动作永远建不了"，所以改成：丢掉词表外的标签 + 点名 + 候选行上标「没标肌群」。
        val draft = parsed(
            doc(
                mixedItems,
                ""","newExercises":[{"name":"保加利亚分腿蹲","category":"STRENGTH","muscleGroups":["腿部","股四头肌"]}]""",
            ),
        )

        val candidate = draft.newExercises.single()
        assertEquals(listOf("腿部"), candidate.muscleGroups)
        val note = draft.notes.single { it.kind == ExternalPlanNote.Kind.NEW_EXERCISE_MUSCLES_DROPPED }
        assertTrue("要点名是哪个标签不在词表里", note.args.single().toString().contains("股四头肌"))
    }

    @Test
    fun parse_newExerciseWithBlankName_isStillRefusedEntirely() {
        // 唯一保留的"整条拒收"：没有名字建不出任何东西。
        val draft = parsed(
            doc(
                mixedItems,
                ""","newExercises":[{"name":"  ","category":"STRENGTH"}]""",
            ),
        )

        assertTrue(
            draft.notes.any { it.kind == ExternalPlanNote.Kind.NEW_EXERCISE_REJECTED },
        )
    }

    @Test
    fun parse_newExerciseAlreadyInLibrary_isSilentlyIgnored_andTheItemStillResolves() {
        val outcome = ExternalPlanDocumentParser.parse(
            doc(
                """{"exercise":"杠铃深蹲","targetSets":3,"targetReps":12}""",
                ""","newExercises":[{"name":"杠铃深蹲","category":"STRENGTH","muscleGroups":["腿部"]}]""",
            ),
            library,
        ) as ExternalDocOutcome.Parsed

        assertEquals("库里已有 → 不进候选", emptyList<ImportedNewExercise>(), outcome.draft.newExercises)
        assertEquals(listOf(1L), outcome.draft.proposal.days.single().items.map { it.exerciseId })
        assertTrue(
            "一条提示都不给：它不是错误，而「建完库自动重解析」必然走到这里 —— " +
                "报出来等于指着用户刚照我们说的做的那一步说「这不合法」（真机抓到的原话）",
            outcome.draft.notes.isEmpty(),
        )
    }

    @Test
    fun parse_allItemsAreNewExercises_stillHandsBackTheCandidates() {
        // 一份"全是新动作"的文档不是废文档：先加库、再重解析，就导得进来。
        val outcome = ExternalPlanDocumentParser.parse(
            doc(
                """{"exercise":"保加利亚分腿蹲","targetSets":3,"targetReps":12}""",
                ""","newExercises":[$newExercise]""",
            ),
            library,
        ) as ExternalDocOutcome.Refused

        assertEquals(ExternalDocRefusal.NO_USABLE_ITEMS, outcome.reason)
        assertEquals(1, outcome.newExercises.size)
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
    fun parse_profileAllowedFields_becomeAStructuredPatch() {
        val draft = parsed(
            doc(
                """{"exercise":"杠铃深蹲","targetSets":3,"targetReps":12}""",
                ""","profile":{"goal":"BULK","trainingDaysPerWeek":5,"injuryNote":"深蹲到底右膝有点顶"}""",
            ),
        )

        assertEquals(Goal.BULK, draft.profile.goal)
        assertEquals(5, draft.profile.trainingDaysPerWeek)
        assertEquals("深蹲到底右膝有点顶", draft.profile.injuryNote)
        assertTrue(
            "允许字段现在会进 diff 清单，不再报「这一版没应用」",
            draft.notes.isEmpty(),
        )
    }

    @Test
    fun parse_profileUnknownEnumValues_areNamedAndDropped_notGuessed() {
        // 合法值是**枚举名**（模板里就是把整份枚举清单发给模型的），中文标签不在合同内 ——
        // 模型真写了中文，这里会点名退回，而不是猜一个。
        val draft = parsed(
            doc(
                """{"exercise":"杠铃深蹲","targetSets":3,"targetReps":12}""",
                ""","profile":{"goal":"TONING","equipment":["反重力椅","BARBELL"]}""",
            ),
        )

        assertNull("TONING 不是任何目标的别名，不猜", draft.profile.goal)
        assertEquals("认不出的器械丢掉，认得出的留下", setOf(Equipment.BARBELL), draft.profile.equipment)
        assertEquals(
            listOf(
                ExternalPlanNote.Kind.PROFILE_VALUE_REJECTED,
                ExternalPlanNote.Kind.PROFILE_VALUE_REJECTED,
            ),
            draft.notes.map { it.kind },
        )
        assertEquals(listOf("goal", "equipment[反重力椅]"), draft.notes.map { it.subject })
    }

    @Test
    fun parse_profileWhereNoNameIsRecognised_dropsTheFieldInsteadOfClearingIt() {
        // 全认不出 ≠ "用户没有器械"。当成空集合写下去等于静默清空他的约束。
        val draft = parsed(
            doc(
                """{"exercise":"杠铃深蹲","targetSets":3,"targetReps":12}""",
                ""","profile":{"equipment":["量子训练舱","意念拉力带"]}""",
            ),
        )

        assertNull(draft.profile.equipment)
        assertEquals(2, draft.notes.count { it.kind == ExternalPlanNote.Kind.PROFILE_VALUE_REJECTED })
    }

    @Test
    fun parse_profileExplicitEmptyArray_isTakenAsADeliberateClear() {
        // 空数组是**明确指令**（"我没伤病了"），和"名字全认不出"是两回事。
        val draft = parsed(
            doc(
                """{"exercise":"杠铃深蹲","targetSets":3,"targetReps":12}""",
                ""","profile":{"injuryAreas":[]}""",
            ),
        )

        assertEquals(emptySet<InjuryArea>(), draft.profile.injuryAreas)
        assertTrue(draft.notes.isEmpty())
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

    // ---------------- 饮食段（v2）----------------
    // 这一组测的是这条通道里最容易骗人的一块：一餐的数字。

    private fun food(
        id: Long,
        name: String,
        kcalPer100g: Int,
        proteinPer100g: Double = 0.0,
        tags: Set<DietRestriction> = emptySet(),
        isActive: Boolean = true,
    ) = Food(
        id = id,
        name = name,
        kcalPer100g = kcalPer100g,
        proteinPer100g = proteinPer100g,
        carbsPer100g = 0.0,
        fatPer100g = 0.0,
        dietaryTags = tags,
        isActive = isActive,
    )

    private val foodLibrary: List<Food> = listOf(
        food(11L, "米饭（蒸）", kcalPer100g = 116, proteinPer100g = 2.6),
        food(12L, "鸡胸肉", kcalPer100g = 133, proteinPer100g = 23.3),
        food(13L, "水煮蛋", kcalPer100g = 144, proteinPer100g = 12.6),
        food(14L, "虾仁", kcalPer100g = 99, proteinPer100g = 16.4, tags = setOf(DietRestriction.SEAFOOD)),
        // 停用行：它**不是**"库里没有"，否则建库撞 UNIQUE → 跳过 → 重解析还是"库里没有" → 死循环。
        food(15L, "馒头", kcalPer100g = 221, proteinPer100g = 7.0, isActive = false),
    ) + (1L..9L).map { index -> food(20L + index, "测试食物$index", kcalPer100g = 10) }

    private fun mealDoc(entriesJson: String, day: Int = 1): String =
        """{"schema":"${ExternalPlanSchema.SCHEMA}","meals":[{"dayOfWeek":$day,"entries":[$entriesJson]}]}"""

    private fun mealEntry(mealType: String, items: String): String =
        """{"mealType":"$mealType","items":[$items]}"""

    private fun parsedDiet(text: String, avoid: Set<DietRestriction> = emptySet()): ExternalPlanDraft =
        when (val outcome = ExternalPlanDocumentParser.parse(text, library, foodLibrary, avoid)) {
            is ExternalDocOutcome.Parsed -> outcome.draft
            is ExternalDocOutcome.Refused -> error("期望解析成功，实际整份拒收：${outcome.reason} / ${outcome.notes}")
        }

    private fun dietRefused(text: String): ExternalDocOutcome.Refused =
        when (val outcome = ExternalPlanDocumentParser.parse(text, library, foodLibrary, emptySet())) {
            is ExternalDocOutcome.Refused -> outcome
            is ExternalDocOutcome.Parsed -> error("期望整份拒收，实际解析成功：${outcome.draft}")
        }

    @Test
    fun parse_mealNumbersComeFromTheLibrary_notFromTheDocument() {
        // 文档自己在条目上写了 kcal/proteinG：`ignoreUnknownKeys` 让它们凭空消失。
        // 这一餐的合计必须是 116×2 + 133×1.5 本地算出来的，一个字节都不许是它报的 9999。
        val draft = parsedDiet(
            mealDoc(
                mealEntry(
                    "LUNCH",
                    """{"food":"米饭（蒸）","grams":200,"kcal":9999,"proteinG":99},""" +
                        """{"food":"鸡胸肉","grams":150}""",
                ),
            ),
        )

        val lunch: ImportedMealDraft = draft.meals.single()
        assertEquals(232 + 200, lunch.kcal)
        assertEquals(5.2 + 34.95, lunch.proteinG, 0.0001)
        assertEquals(0, lunch.unresolvedCount)
    }

    @Test
    fun parse_dietOnlyDocument_isNoLongerRefusedAsAnEmptyPlan() {
        // v1 时代没有 `days` 就整份不收（BRIEF 记过这条欠账）；v2 起"只问吃的"是合法文档。
        val draft = parsedDiet(mealDoc(mealEntry("DINNER", """{"food":"水煮蛋","grams":60}""")))

        assertTrue(draft.proposal.days.isEmpty())
        assertEquals(1, draft.meals.size)
    }

    @Test
    fun parse_profileOnlyDocument_isRefusedAsNothingToImport() {
        val outcome = dietRefused(
            """{"schema":"${ExternalPlanSchema.SCHEMA}","profile":{"trainingDaysPerWeek":5}}""",
        )

        assertEquals(ExternalDocRefusal.NOTHING_TO_IMPORT, outcome.reason)
    }

    @Test
    fun parse_allFoodsUnknown_isRefusedButHandsBackTheCandidates() {
        // 一份"全是库里没有的食物"不是废文档，它是"先加库再导入" —— 候选必须跟着回过去。
        val outcome = dietRefused(mealDoc(mealEntry("LUNCH", """{"food":"紫薯","grams":200}""")))

        assertEquals(ExternalDocRefusal.NO_USABLE_ITEMS, outcome.reason)
        assertEquals(listOf(ExternalPlanNote.Kind.FOOD_CREATABLE), outcome.notes.map { note -> note.kind })
        assertEquals("紫薯", outcome.newFoods.single().name)
        // 文档没声明 newFoods → 数值一格都没有，**不能**拿 0 顶上（0 是一个陈述，不是"不知道"）。
        assertTrue(outcome.newFoods.single().hasCompleteNutrition.not())
    }

    @Test
    fun parse_partiallyKnownMeal_keepsItAndCountsWhatWasLeftOut() {
        val draft = parsedDiet(
            mealDoc(mealEntry("LUNCH", """{"food":"米饭（蒸）","grams":200},{"food":"紫薯","grams":150}""")),
        )

        val lunch: ImportedMealDraft = draft.meals.single()
        assertEquals(1, lunch.entries.size)
        assertEquals(232, lunch.kcal)
        // 界面那句「这餐少算了 N 条（未入库）」就取这个数。
        assertEquals(1, lunch.unresolvedCount)
    }

    @Test
    fun parse_deactivatedFood_isListedAsInactive_andNeverOfferedAsANewFood() {
        val draft = parsedDiet(
            mealDoc(mealEntry("BREAKFAST", """{"food":"米饭（蒸）","grams":150},{"food":"馒头","grams":100}""")),
        )

        assertEquals(listOf(ExternalPlanNote.Kind.FOOD_INACTIVE), draft.notes.map { note -> note.kind })
        assertTrue("停用行不能变成建库候选，否则重解析会一直停在「库里没有」", draft.newFoods.isEmpty())
        assertEquals(1, draft.meals.single().unresolvedCount)
    }

    @Test
    fun parse_foodHittingAvoidedTag_isBlockedAndNotCountedAsMissing() {
        val draft = parsedDiet(
            mealDoc(mealEntry("LUNCH", """{"food":"米饭（蒸）","grams":200},{"food":"虾仁","grams":100}""")),
            avoid = setOf(DietRestriction.SEAFOOD),
        )

        assertEquals(ExternalPlanNote.Kind.FOOD_RESTRICTED, draft.notes.single().kind)
        assertEquals(232, draft.meals.single().kcal)
        // 忌口挡掉的是**故意不算**，不是"少算了"：混进同一个数字会让那句提示变成假话。
        assertEquals(0, draft.meals.single().unresolvedCount)
    }

    @Test
    fun parse_ninthFoodInAMeal_isListed_notSilentlyCut() {
        val nineItems: String = (1L..9L).joinToString(",") { index ->
            """{"food":"测试食物$index","grams":100}"""
        }

        val draft = parsedDiet(mealDoc(mealEntry("LUNCH", nineItems)))

        assertEquals(ExternalPlanDocumentParser.MAX_FOODS_PER_MEAL, draft.meals.single().entries.size)
        val overflow: ExternalPlanNote = draft.notes.single()
        assertEquals(ExternalPlanNote.Kind.OVER_MEAL_LIMIT, overflow.kind)
        assertEquals("测试食物9", overflow.subject)
    }

    @Test
    fun parse_sameFoodTwiceInAMeal_keepsFirstAndDoesNotAddPortions() {
        val draft = parsedDiet(
            mealDoc(mealEntry("LUNCH", """{"food":"米饭（蒸）","grams":200},{"food":"米饭（蒸）","grams":300}""")),
        )

        // 合并份量等于 App 替用户改数量，所以只留第一条、另一条点名说明。
        assertEquals(232, draft.meals.single().kcal)
        assertEquals(ExternalPlanNote.Kind.DUPLICATE_FOOD, draft.notes.single().kind)
    }

    @Test
    fun parse_unknownMealType_dropsOnlyThatMeal() {
        val draft = parsedDiet(
            """{"schema":"${ExternalPlanSchema.SCHEMA}","meals":[{"dayOfWeek":1,"entries":[
                {"mealType":"BRUNCH","items":[{"food":"水煮蛋","grams":60}]},
                {"mealType":"LUNCH","items":[{"food":"米饭（蒸）","grams":200}]}]}]}""",
        )

        assertEquals(ExternalPlanNote.Kind.MEAL_TYPE_UNKNOWN, draft.notes.single().kind)
        assertEquals("BRUNCH", draft.notes.single().subject)
        assertEquals(listOf(MealType.LUNCH), draft.meals.map { meal -> meal.mealType })
    }

    @Test
    fun parse_sameSlotTwice_keepsTheFirstMealAndListsTheSecond() {
        val draft = parsedDiet(
            """{"schema":"${ExternalPlanSchema.SCHEMA}","meals":[{"dayOfWeek":1,"entries":[
                {"mealType":"LUNCH","items":[{"food":"米饭（蒸）","grams":200}]},
                {"mealType":"LUNCH","items":[{"food":"水煮蛋","grams":60}]}]}]}""",
        )

        assertEquals(232, draft.meals.single().kcal)
        assertEquals(MealType.LUNCH.name, draft.notes.single().subject)
        assertEquals(ExternalPlanNote.Kind.DUPLICATE_MEAL, draft.notes.single().kind)
    }

    @Test
    fun parse_gramsOutOfRange_areClampedAndListed() {
        val draft = parsedDiet(
            mealDoc(mealEntry("LUNCH", """{"food":"米饭（蒸）","grams":5000},{"food":"水煮蛋","grams":0}""")),
        )

        assertEquals(2, draft.notes.size)
        assertTrue(draft.notes.all { note -> note.kind == ExternalPlanNote.Kind.GRAMS_CLAMPED })
        // 上限 2000 克、下限 1 克（与表单同源，不另立数字）。
        assertEquals(listOf(2000, 1), draft.meals.single().entries.map { entry -> entry.grams })
    }

    @Test
    fun parse_declaredNewFoodNumbers_prefillTheCandidate_andOutOfRangeOnesBecomeNull() {
        val outcome = dietRefused(
            """{"schema":"${ExternalPlanSchema.SCHEMA}","meals":[{"dayOfWeek":1,"entries":[
                {"mealType":"LUNCH","items":[{"food":"紫薯","grams":200}]}]}],
             "newFoods":[{"name":"紫薯","kcalPer100g":6000,"proteinPer100g":1.6}]}""",
        )

        val candidate: ImportedNewFood = outcome.newFoods.single()
        assertEquals(1.6, candidate.proteinPer100g!!, 0.0001)
        // 越界的数值只能变回"空着等用户填"，不能钳成一个看起来像数值的数。
        assertNull(candidate.kcalPer100g)
        assertTrue(outcome.notes.any { note -> note.kind == ExternalPlanNote.Kind.NEW_FOOD_VALUE_REJECTED })
    }

    @Test
    fun parse_dayOfWeekOutOfRange_isClampedTheSameWayAsTrainingDays() {
        val draft = parsedDiet(mealDoc(mealEntry("LUNCH", """{"food":"米饭（蒸）","grams":200}"""), day = 9))

        assertEquals(7, draft.meals.single().dayOfWeek)
    }

    @Test
    fun parse_mealDayWithoutEntries_isNamed_notSilentlyDropped() {
        // 真机反馈的那一种：文档里 `meals` 有这一天，但条目一条都没读到
        // （最常见是模型把 items 直接挂在天的层级上）。以前这会是**零说明**的静默丢失。
        val text = """
            {"schema":"${ExternalPlanSchema.SCHEMA}",
             "days":[{"dayOfWeek":1,"items":[{"exercise":"杠铃深蹲","targetSets":3,"targetReps":12}]}],
             "meals":[{"dayOfWeek":2}]}
        """.trimIndent()

        val draft = ExternalPlanDocumentParser.parse(text, library, foodLibrary, emptySet())
            as ExternalDocOutcome.Parsed

        assertTrue("训练那一半照常进来", draft.draft.proposal.days.isNotEmpty())
        assertTrue("餐次一条都没生成", draft.draft.meals.isEmpty())
        val note: ExternalPlanNote = draft.draft.notes.single { n -> n.kind == ExternalPlanNote.Kind.MEAL_ENTRIES_MISSING }
        assertEquals(2, note.dayOfWeek)
        assertEquals("星期要能上界面", listOf(2), note.args)
    }

    @Test
    fun parse_trainingOnlyDocument_keepsTheNotesClean() {
        // 只导训练是**合法**用法（v1 时代一直如此）。"它没给吃"不能塞进"这份文档里没导进来的"
        // 那份清单 —— 那会让每次正常导入都凭空多一条抱怨，而清单的可信度就是它的用处。
        // 那一句话属于预览页，见 `PlanPreviewScreen` 的 `plan_preview_note_diet_section_missing`。
        val draft = parsed(
            doc("""{"exercise":"杠铃深蹲","targetSets":3,"targetReps":12}"""),
        )

        assertTrue(draft.notes.isEmpty())
    }

    @Test
    fun parse_dietUnderNutritionKey_namesIt_insteadOfSwallowingIt() {
        // 用户真机拿回来的那份，原样形状：`days` 完全照合同，吃写在顶层 `nutrition` 里。
        // 以前 `ignoreUnknownKeys` 把它无声吞掉 → 用户只看见"我问到的吃没影了"。
        val text = """
            {"schema":"${ExternalPlanSchema.SCHEMA}",
             "days":[{"dayOfWeek":1,"items":[{"exercise":"杠铃深蹲","targetSets":3,"targetReps":12}]}],
             "nutrition":{"dailyCalories":2400,"meals":[{"meal":"早餐","items":["鸡蛋3个"]}]}}
        """.trimIndent()

        val draft = ExternalPlanDocumentParser.parse(text, library, foodLibrary, emptySet())
            as ExternalDocOutcome.Parsed

        val note: ExternalPlanNote = draft.draft.notes.single { n -> n.kind == ExternalPlanNote.Kind.DIET_SECTION_MISPLACED }
        assertEquals("nutrition", note.subject)
        assertTrue("但它仍然不能凭空变成草案 —— 形状不对就是导不进来", draft.draft.meals.isEmpty())
        assertTrue("训练那一半照常进来", draft.draft.proposal.days.isNotEmpty())
    }
}
