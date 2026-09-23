package com.ironhabit.app.ui.theme

import androidx.compose.ui.graphics.Color
import com.ironhabit.app.domain.model.Habit
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [habitColor] 的纯 JVM 单测 —— 把用户存的 `color_hex` 变成像素的那唯一一处。
 *
 * 钉的是**兜底**，不是"能不能解析出正确颜色"（那半边一眼就对）。
 * 理由：`color_hex` 是从备份 JSON 原样透传进库的（`BackupRepositoryImpl` 的
 * `HabitBackup.toEntity` 不校验这个字段），所以传进来的东西**可以是任意字符串**。
 * 用 `android.graphics.Color.parseColor` 会在非法值上抛 `IllegalArgumentException`，
 * 而这条色条现在渲染在自律页与今日页每一行习惯上 —— 一个坏字符就是整页崩。
 *
 * 顺带：这个函数刻意不依赖 Android 框架 API，否则它在 JVM 单测里根本调不动，
 * 也就没人会给它写测试。
 */
class HabitColorTest {

    @Test
    fun habitColor_parsesSixDigitHex() {
        assertEquals(Color(0xFF2196F3), habitColor("#2196F3"))
        assertEquals(Color(0xFF4CAF50), habitColor("#4CAF50"))
        assertEquals(Color(0xFF000000), habitColor("#000000"))
        assertEquals(Color(0xFFFFFFFF), habitColor("#FFFFFF"))
    }

    @Test
    fun habitColor_acceptsLowerCaseAndIsCaseInsensitive() {
        assertEquals(habitColor("#2196F3"), habitColor("#2196f3"))
    }

    @Test
    fun habitColor_fallsBackToDefaultInsteadOfThrowing() {
        val fallback: Color = habitColor(Habit.DEFAULT_COLOR_HEX)

        // ⚠️ 这些"非法输入"刻意**不含默认色自己的 hex**。
        // 第一版我拿 `#2196F3`（= 默认蓝）去拼 `" #2196F3"`、`"#2196F3FF"` 之类，
        // 结果一个错误地用了 `Regex.find` 而不是 `matchEntire` 的解析器**照样通过测试** ——
        // 因为它把 `#2196F3` 抠出来了，而那个值恰好就等于兜底色，断言分不出两者。
        // 换成绿色系之后，"抠出一段子串来解析"这个错法才会露出来。
        listOf(
            "",                      // 空串
            "red",                   // 颜色名
            "4CAF50",                // 少了 #
            "##4CAF50",              // 多了 #
            "#4CAF5",                // 5 位
            "#GGHHII",               // 不是十六进制
            "#4CAF50 ",              // 尾部空格
            " #4CAF50",              // 头部空格
            "#4CAF50FF",             // 8 位（带 alpha）—— 形状不符，别猜它的意思
            "#4CAF50#2196F3",        // 前后夹一个合法的默认色：find 会抠出第一个并"成功"
        ).forEach { bad ->
            assertEquals("`$bad` 该回落到默认色而不是崩掉整页", fallback, habitColor(bad))
        }
    }

    @Test
    fun habitColor_neverProducesTransparent() {
        // alpha 恒为 FF：色条要压在浅底和深底两种卡片上，半透明等于在某些底上看不见。
        listOf("#000000", "#FFFFFF", "nonsense").forEach { hex ->
            assertEquals(255, (habitColor(hex).alpha * 255).toInt())
        }
    }
}
