package com.ironhabit.app.data.local

import com.ironhabit.app.data.local.entity.FoodEntity
import com.ironhabit.app.domain.model.FoodSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fillsInMissingBuiltInTags] —— 「内置行的忌口标签只补一次空」的边界。
 *
 * 忌口标签是安全判据（外部 AI 计划导入时按它整条挡），而它抄在 `assets/foods.json` 里。
 * 播种器原本对同名行是整个跳过，所以 2026-09-27 给核桃仁/杏仁/腰果补的 `PEANUT`
 * 只会落到新装机上（真机实测老库里那三条仍是 `NULL`）。补这一段判据的全部风险都在
 * "会不会顺手盖掉别人的值"，所以四条边界逐条钉：
 */
class FoodSeederTagBackfillTest {

    private fun row(
        source: FoodSource,
        dietaryTags: String?,
    ) = FoodEntity(
        id = 7L,
        name = "核桃仁",
        kcalPer100g = 646,
        proteinPer100g = 15.2,
        carbsPer100g = 13.7,
        fatPer100g = 65.2,
        source = source.name,
        dietaryTags = dietaryTags,
    )

    @Test
    fun builtInRowWithNoTagsTakesTheAssetValue() {
        assertTrue(
            "内置行空标签 + 数据源有标签 = 该补（否则补标只对新装机生效）",
            fillsInMissingBuiltInTags(row(FoodSource.BUILT_IN, null), presetTags = "PEANUT"),
        )
        assertTrue(
            "空串与 null 同义：`dietary_tags` 没有「未标注」这第三态",
            fillsInMissingBuiltInTags(row(FoodSource.BUILT_IN, ""), presetTags = "PEANUT"),
        )
    }

    @Test
    fun existingTagsAreNeverOverwritten() {
        assertFalse(
            "库里已经有标签，那不是「缺」 —— 盖掉它就是把用户或数据标过的值改没",
            fillsInMissingBuiltInTags(row(FoodSource.BUILT_IN, "SEAFOOD"), presetTags = "PEANUT"),
        )
    }

    @Test
    fun nonBuiltInRowsAreLeftAlone() {
        assertFalse(
            "自建条目的忌口成分只有填的人知道，表单里那组 chip 是给他的出口，不该被数据源写",
            fillsInMissingBuiltInTags(row(FoodSource.CUSTOM, null), presetTags = "PEANUT"),
        )
        assertFalse(
            "外部 AI 建的条目同理（而且它的数值本来就是估的）",
            fillsInMissingBuiltInTags(row(FoodSource.AI_SUGGESTED, null), presetTags = "PEANUT"),
        )
    }

    @Test
    fun assetWithoutTagsWritesNothing() {
        assertFalse(
            "数据源自己没标签就没有依据，写一次只是把行标成「已处理」",
            fillsInMissingBuiltInTags(row(FoodSource.BUILT_IN, null), presetTags = null),
        )
        assertFalse(
            fillsInMissingBuiltInTags(row(FoodSource.BUILT_IN, null), presetTags = ""),
        )
    }
}
