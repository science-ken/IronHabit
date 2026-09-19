package com.ironhabit.app.domain.usecase

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本次会话里的**计划预览快照**。
 *
 * 「让 AI 生成」先算不写（[GenerateTrainingPlanUseCase.preview]），结果要跨一次路由跳转
 * 交给预览页 —— 草案是几十条带外键的对象，塞进导航参数既不合适也不安全，所以放这里。
 *
 * 故意**不落盘**：进程被杀，预览就没了，用户回来看到的是库里的真实状态（没写就是没写）。
 * 这与「撤销这次导入只在本次会话有效」是同一条口径。
 */
@Singleton
class PlanPreviewHolder @Inject constructor() {

    private var current: PlanPreview? = null

    fun set(preview: PlanPreview) {
        current = preview
    }

    /** 取出当前快照但不消耗它（页面重建、配置变更时还要能再渲染一次）。 */
    fun peek(): PlanPreview? = current

    /** 采纳完 / 取消完必须清掉，避免下一次进来看到上一次的陈旧草案。 */
    fun clear() {
        current = null
    }
}
