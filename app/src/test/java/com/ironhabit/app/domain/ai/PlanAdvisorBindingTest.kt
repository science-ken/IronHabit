package com.ironhabit.app.domain.ai

import com.ironhabit.app.di.AppModule
import com.ironhabit.app.domain.model.AdviceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 「计划顾问只有一个实现」的防回归断言。
 *
 * v2.0.14 删掉了「用户自填 API Key 直连 DeepSeek」那条通道，连同按开关与 Key 路由的
 * `DelegatingPlanAdvisor`。CI 那边已经把 `INTERNET` 从权限白名单里摘掉（重新声明会让 CI 红），
 * 这里补上领域侧的同一道闸：**装配只能是一个纯本地实现**。
 *
 * 这两条刻意写成"改动就会红"的形式 —— 这正是目的：往 [PlanAdvisor] 上插第二个实现，
 * 必须先回答"要不要联网、把哪些档案字段发出去"，而不是悄悄插进去就完事。
 */
class PlanAdvisorBindingTest {

    @Test
    fun planAdvisorBinding_isTheLocalRuleAdvisorItself() {
        val advisor: PlanAdvisor = AppModule.providePlanAdvisor()

        assertSame(
            "装配必须直接绑 LocalRuleAdvisor：中间不允许再夹一层委托/开关路由",
            LocalRuleAdvisor,
            advisor,
        )
    }

    @Test
    fun planAdvisorSource_isLocalRules() {
        assertEquals(AdviceSource.LOCAL_RULES, AppModule.providePlanAdvisor().source)
    }

    @Test
    fun adviceSource_hasOnlyTheTwoProducers() {
        assertEquals(
            "来源只剩两种：内置规则生成、外部 AI 导入。加第三个值要连带想清楚 UI 怎么标",
            setOf(AdviceSource.LOCAL_RULES, AdviceSource.EXTERNAL_AI_IMPORT),
            AdviceSource.values().toSet(),
        )
    }
}
