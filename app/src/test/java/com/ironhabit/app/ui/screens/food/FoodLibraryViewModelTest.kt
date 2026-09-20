package com.ironhabit.app.ui.screens.food

import com.ironhabit.app.R
import com.ironhabit.app.domain.model.DietRestriction
import com.ironhabit.app.domain.model.Food
import com.ironhabit.app.domain.model.FoodServing
import com.ironhabit.app.domain.model.FoodSource
import com.ironhabit.app.domain.repository.FoodRepository
import com.ironhabit.app.test.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * [FoodLibraryViewModel] 的表单校验与保存往返。
 *
 * 重点不在"能不能存"，而在三条**会静默丢数据**的路径：
 * 1. 半填的份量行必须被拒（否则造出一条"有单位没克数"的份，算营养时是哑的）；
 * 2. 编辑内置食物时**忌口标签与 source 必须原样带过去**（漏了就是"改个克数把 PEANUT 清空"，
 *    而这是安全字段，清空后乳制品/花生过敏的用户会被放行）；
 * 3. 重名必须挡住且**不落库**。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FoodLibraryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val savedFood = slot<Food>()
    private val repository = mockk<FoodRepository>(relaxed = true)
    private val clock = mockk<Clock>(relaxed = true)

    private fun viewModel(): FoodLibraryViewModel {
        every { repository.observeActive() } returns flowOf(emptyList())
        coEvery { repository.upsert(capture(savedFood)) } returns 7L
        return FoodLibraryViewModel(repository, clock)
    }

    /** 填一份合法的新建表单（热量必填，宏量留空 = 0 合法）。 */
    private fun fillValid(vm: FoodLibraryViewModel, name: String = "米糊") {
        vm.onOpenCreate()
        vm.onNameChange(name)
        vm.onKcalChange("80")
        vm.onProteinChange("1.0")
        vm.onCarbsChange("17.0")
        vm.onFatChange("0.5")
    }

    @Test
    fun validFormSavesAndReturnsName() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = viewModel()
        fillValid(vm)

        assertEquals("米糊", vm.onSave())
        assertTrue("必须真的落库", savedFood.isCaptured)
        assertEquals(80, savedFood.captured.kcalPer100g)
    }

    @Test
    fun blankMacrosBecomeZeroNotNull() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = viewModel()
        fillValid(vm)
        vm.onProteinChange("")
        vm.onCarbsChange("")
        vm.onFatChange("")

        assertEquals("宏量留空必须合法（很多包装食品只印热量）", "米糊", vm.onSave())
        assertEquals(0.0, savedFood.captured.proteinPer100g, 0.0)
    }

    @Test
    fun blankNameIsRejectedAndNeverSaved() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = viewModel()
        fillValid(vm)
        vm.onNameChange("   ")

        assertNull("名字空白绝不能落库", vm.onSave())
        assertEquals(R.string.error_name_empty, vm.formState.value.nameErrorRes)
        assertTrue("被拒的保存不能写库", !savedFood.isCaptured)
    }

    @Test
    fun kcalAbovePhysicalCeilingIsRejected() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = viewModel()
        fillValid(vm)
        vm.onKcalChange("1200") // 纯脂肪才 900 kcal/100g，超过必是抄错

        assertNull(vm.onSave())
        assertEquals(R.string.error_invalid_number, vm.formState.value.numberErrorRes)
    }

    @Test
    fun outOfRangeMacroIsRejected() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = viewModel()
        fillValid(vm)
        vm.onProteinChange("130") // 每 100g 不可能有 130g 蛋白

        assertNull(vm.onSave())
        assertEquals(R.string.error_invalid_number, vm.formState.value.numberErrorRes)
    }

    /** 核心：只填单位不填克数（或反之）必须整单被拒，而不是悄悄丢掉那一行。 */
    @Test
    fun halfFilledServingRowRejectsTheWholeSave() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = viewModel()
        fillValid(vm)
        vm.onServingChange(0, unit = "碗", grams = "")

        assertNull("半填的份量行会造出一条算不出营养的份，必须拒绝而不是丢弃", vm.onSave())
        assertEquals(R.string.error_invalid_number, vm.formState.value.numberErrorRes)
    }

    @Test
    fun fullyBlankServingRowIsIgnored() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = viewModel()
        fillValid(vm)
        vm.onServingAdd() // 第二行全空 = 用户没打算加

        assertEquals("米糊", vm.onSave())
        assertEquals("只应保留那一行填了的", 0, savedFood.captured.servings.size)
    }

    @Test
    fun servingsAreStoredWithTheirGrams() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = viewModel()
        fillValid(vm)
        vm.onServingChange(0, unit = " 碗 ", grams = "200")

        assertEquals("米糊", vm.onSave())
        val servings: List<FoodServing> = savedFood.captured.servings
        assertEquals(1, servings.size)
        assertEquals("单位要去掉首尾空白", "碗", servings.first().unit)
        assertEquals(200, servings.first().grams)
    }

    @Test
    fun duplicateNameIsRejectedAndNeverSaved() = runTest(mainDispatcherRule.testDispatcher) {
        coEvery { repository.nameExists("米糊", 0L) } returns true
        val vm = viewModel()
        fillValid(vm)

        assertNull("重名不能落库（唯一索引会抛，用户要看到友好报错）", vm.onSave())
        assertEquals(R.string.error_name_exists, vm.formState.value.nameErrorRes)
        assertTrue(!savedFood.isCaptured)
    }

    /**
     * 编辑内置食物：source 与忌口标签**必须原样保留**。
     *
     * 反例长这样：表单里没有忌口控件 → 保存时 `dietaryTags = emptySet()` →
     * 内置「牛奶」的 DAIRY 标签被一次"改个克数"清空 → 乳制品过敏的用户被放行。
     */
    @Test
    fun editingABuiltInKeepsSourceAndAllergenTags() = runTest(mainDispatcherRule.testDispatcher) {
        val milk = Food(
            id = 3L,
            name = "牛奶",
            kcalPer100g = 54,
            proteinPer100g = 3.0,
            carbsPer100g = 3.4,
            fatPer100g = 3.2,
            servings = listOf(FoodServing(id = 1L, unit = "盒", grams = 250)),
            dietaryTags = setOf(DietRestriction.DAIRY),
            source = FoodSource.BUILT_IN,
        )
        val repo = mockk<FoodRepository>(relaxed = true)
        every { repo.observeActive() } returns flowOf(listOf(milk))
        coEvery { repo.getFood(3L) } returns milk
        coEvery { repo.upsert(capture(savedFood)) } returns 3L
        val vm = FoodLibraryViewModel(repo, clock)
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

        vm.onOpenEdit(milk)
        vm.onKcalChange("55") // 用户只改了一个数字
        assertEquals("牛奶", vm.onSave())

        val saved = savedFood.captured
        assertEquals("内置身份不能被编辑打掉（Q20=B）", FoodSource.BUILT_IN, saved.source)
        assertEquals(
            "忌口标签不能被一次无关编辑清空",
            setOf(DietRestriction.DAIRY),
            saved.dietaryTags,
        )
        assertEquals(55, saved.kcalPer100g)
        assertTrue("必须置用户已改标记", saved.isUserEdited)
    }

    @Test
    fun savingFlagResetsEvenWhenRepositoryThrows() = runTest(mainDispatcherRule.testDispatcher) {
        val repo = mockk<FoodRepository>(relaxed = true)
        every { repo.observeActive() } returns flowOf(emptyList())
        coEvery { repo.upsert(any()) } throws RuntimeException("db busy")
        val vm = FoodLibraryViewModel(repo, clock)

        vm.onOpenCreate()
        vm.onNameChange("米糊")
        vm.onKcalChange("80")
        runCatching { vm.onSave() }
        assertEquals("失败后必须复位，否则保存按钮永久点不动", false, vm.formState.value.isSaving)
    }
}
