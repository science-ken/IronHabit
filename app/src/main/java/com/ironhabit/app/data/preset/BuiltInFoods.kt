package com.ironhabit.app.data.preset

import com.ironhabit.app.domain.model.DietRestriction
import com.ironhabit.app.domain.model.Food
import com.ironhabit.app.domain.model.FoodServing
import com.ironhabit.app.domain.model.FoodSource
import kotlinx.serialization.Serializable

/**
 * `assets/foods.json` 里的一条内置食物。
 *
 * ⚠️ **这份数据是我们自己写的，不是从任何数据集导入的。**
 * 实测结论（`.scratch/ironhabit-diet-food-log/spec.md` §6）：不存在任何有合法授权的中文食物成分数据集
 * —— foodwake 是爬的（且缺 猪肉/鸡肉/牛奶/坚果/禽肉/乳类/油类），Open Food Facts 离线 dump
 * 只有 18% 带热量、中文占 0.03%，《中国食物成分表》是 ¥168 的纸质书且"保留所有权利"。
 * 所以这些数值来自公开营养常识的**手抄**，正确性靠 `BuiltInFoodsDataTest` 的能量自洽校验兜底，
 * 不靠"抄自权威表"这个我们无法兑现的承诺。
 */
@Serializable
data class FoodPresetDto(
    val name: String,
    val kcalPer100g: Int,
    val proteinPer100g: Double,
    val carbsPer100g: Double,
    val fatPer100g: Double,
    val servings: List<ServingPresetDto> = emptyList(),
    /** `DietRestriction.name` 字面量。**写错会直接抛**，见 [BuiltInFoods.toDomain]。 */
    val dietaryTags: List<String> = emptyList(),
)

/** 一种家用份量。 */
@Serializable
data class ServingPresetDto(
    val unit: String,
    val grams: Int,
)

/**
 * 内置食物库的解析入口。
 *
 * 拆成"读文本 → [parse] → [toDomain]"三步而不是一个 `load(context)`，
 * 是为了让**同一份解析逻辑**能被 JVM 单测直接喂文件内容跑（不依赖 Android Context），
 * 就像 `StringResourcePlaceholderContractTest` 直接读 `strings.xml` 那样。
 */
object BuiltInFoods {

    /** assets 下的文件名。 */
    const val ASSET_NAME: String = "foods.json"

    /**
     * 严格模式：`ignoreUnknownKeys = false` + 禁止尾逗号。
     *
     * 内置数据写错（多个字段、少个引号）必须在启动时就炸出来，
     * 而不是静默少播种几十条食物、让用户以为"库里没有"。
     */
    private val format = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = false
        allowTrailingComma = false
        isLenient = false
    }

    /** JSON 文本 → DTO 列表。格式错误直接抛，由调用方（`FoodSeeder`）兜住并记日志。 */
    fun parse(raw: String): List<FoodPresetDto> = format.decodeFromString(raw)

    /**
     * DTO → 领域对象，并**校验忌口标签**。
     *
     * ⚠️ 未知标签**抛异常而不是忽略**：忌口是安全相关的判据，
     * `"DAIRY"` 拼错成 `"Dairy"` 如果被静默丢掉，一条牛奶就会变成"无忌口"，
     * 对乳制品过敏的用户就会在毫不知情的情况下被推荐它。
     * 宁可启动时播种失败并留下日志，也不能悄悄放行。
     */
    fun toDomain(presets: List<FoodPresetDto>): List<Food> = presets.mapIndexed { index, preset ->
        Food(
            name = preset.name.trim(),
            kcalPer100g = preset.kcalPer100g,
            proteinPer100g = preset.proteinPer100g,
            carbsPer100g = preset.carbsPer100g,
            fatPer100g = preset.fatPer100g,
            servings = preset.servings.mapIndexed { servingIndex, serving ->
                FoodServing(unit = serving.unit.trim(), grams = serving.grams, sortOrder = servingIndex)
            },
            dietaryTags = preset.dietaryTags.mapTo(LinkedHashSet()) { tag ->
                DietRestriction.entries.firstOrNull { it.name == tag }
                    ?: throw IllegalArgumentException(
                        "foods.json 里「${preset.name}」带着一个不认识的忌口标签「$tag」；" +
                            "合法值：${DietRestriction.entries.joinToString { it.name }}"
                    )
            },
            source = FoodSource.BUILT_IN,
            sortOrder = index + 1,
        )
    }
}
