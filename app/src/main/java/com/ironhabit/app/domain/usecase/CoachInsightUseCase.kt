package com.ironhabit.app.domain.usecase

import com.ironhabit.app.di.IoDispatcher
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * 「进度解读」的结果。
 *
 * **数字永远来自本地纯聚合**（[CoachContext]），UI 用 [CoachInsightResult.context] 渲染，
 * 再配一段固定小结文案（`ai_insight_local_body`）。
 *
 * 这里不再有 `source` / `text` 两个字段：它们存在的唯一理由是"远端可能另给一段 AI 文案"，
 * 而那条通道已在 v2.0.14 整条删除 —— 留着一个永远为 `null` 的字段，UI 就会一直有一条走不到的分支。
 */
data class CoachInsightResult(
    val context: CoachContext = CoachContext(),
)

/**
 * 「进度解读」用例：聚合最近 N 天的训练 / 体重 / 饮食数字，供卡片渲染。
 *
 * @param windowDays 统计窗口（默认近 14 天；`< 1` 会被 [BuildCoachContextUseCase] 钳到 1）
 */
class CoachInsightUseCase @Inject constructor(
    private val buildCoachContext: BuildCoachContextUseCase,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend operator fun invoke(windowDays: Int = WINDOW_DAYS): CoachInsightResult =
        withContext(ioDispatcher) {
            CoachInsightResult(context = buildCoachContext(windowDays))
        }

    companion object {
        /** 默认统计窗口：近 14 天（进度解读看趋势，比单日更宽）。 */
        const val WINDOW_DAYS: Int = 14
    }
}
