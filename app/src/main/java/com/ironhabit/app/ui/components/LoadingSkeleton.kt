package com.ironhabit.app.ui.components

import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ironhabit.app.ui.theme.IronHabitShapes
import com.ironhabit.app.ui.theme.IronHabitSpacing

/**
 * 骨架屏：用 `rememberInfiniteTransition` + `animateFloat` 做 alpha 呼吸的灰色圆角块。
 *
 * **不引入任何 shimmer 第三方库**（派工单 §6.2）。
 */
@Composable
private fun rememberBreathAlpha(): Float {
    val transition: InfiniteTransition = rememberInfiniteTransition(label = "skeleton_breath")
    val alpha: Float by transition.animateFloat(
        initialValue = MIN_ALPHA,
        targetValue = MAX_ALPHA,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = BREATH_DURATION_MS),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeleton_alpha",
    )
    return alpha
}

/** 通用灰色呼吸块（整行高 64dp）。 */
@Composable
fun LoadingSkeleton(modifier: Modifier = Modifier) {
    val alpha = rememberBreathAlpha()
    SkeletonBlock(
        alpha = alpha,
        height = BLOCK_HEIGHT,
        widthFraction = 1f,
        modifier = modifier,
    )
}

/** 单张卡片骨架（模拟一条打卡卡片的占位）。 */
@Composable
fun SkeletonCard(modifier: Modifier = Modifier) {
    val alpha = rememberBreathAlpha()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = IronHabitSpacing.xs, vertical = IronHabitSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
    ) {
        SkeletonBlock(alpha = alpha, height = TITLE_HEIGHT, widthFraction = 0.6f)
        SkeletonBlock(alpha = alpha, height = LINE_HEIGHT, widthFraction = 1f)
        SkeletonBlock(alpha = alpha, height = LINE_HEIGHT, widthFraction = 0.4f)
    }
}

@Composable
private fun SkeletonBlock(
    alpha: Float,
    height: Dp,
    widthFraction: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .alpha(alpha)
            .clip(IronHabitShapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

/** 圆形骨架块（头像与完整度环的位置）。`SkeletonBlock` 是"一条"，撑不出正方形。 */
@Composable
private fun SkeletonCircle(alpha: Float, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .alpha(alpha)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

/**
 * 「我的」页档案卡的骨架：头像 + 两行文字 + 环。
 *
 * 为什么单独做一块而不是丢一个通用灰条：通用条高 64dp，而真实档案卡连头像带缺口提示行
 * 有 ~100dp —— 数据到位那一刻整屏往下跳，用户会以为页面重新加载了一次。
 * 同构骨架的作用就是让"填内容"这件事不改变版式。
 */
@Composable
fun SkeletonProfileHeader(modifier: Modifier = Modifier) {
    val alpha: Float = rememberBreathAlpha()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(IronHabitShapes.card)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(IronHabitSpacing.md),
        verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
        ) {
            SkeletonCircle(alpha = alpha, size = AVATAR_SKELETON)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.xs),
            ) {
                SkeletonBlock(alpha = alpha, height = TITLE_HEIGHT, widthFraction = 0.8f)
                SkeletonBlock(alpha = alpha, height = LINE_HEIGHT, widthFraction = 1f)
            }
            SkeletonCircle(alpha = alpha, size = AVATAR_SKELETON)
        }
        // 第四行是"还差 N 项"那条缺口的占位：真实卡片只在有缺口时画它，
        // 骨架宁可多留一点高度，也不要数据到位后整屏往上缩。
        SkeletonBlock(alpha = alpha, height = LINE_HEIGHT, widthFraction = 0.45f)
    }
}

/**
 * 「我的」页关键数字四联的骨架：四格「标签 + 数值」，与 [SkeletonProfileHeader] 同一档容器色。
 */
@Composable
fun SkeletonStatStrip(modifier: Modifier = Modifier) {
    val alpha: Float = rememberBreathAlpha()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(IronHabitShapes.card)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = IronHabitSpacing.sm, vertical = IronHabitSpacing.md),
        horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
    ) {
        repeat(STAT_STRIP_COLUMNS) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.xs),
            ) {
                SkeletonBlock(alpha = alpha, height = LINE_HEIGHT, widthFraction = 0.7f)
                SkeletonBlock(alpha = alpha, height = TITLE_HEIGHT, widthFraction = 0.9f)
            }
        }
    }
}

private const val MIN_ALPHA = 0.3f
private const val MAX_ALPHA = 1.0f
private const val BREATH_DURATION_MS = 900

/** 以下高度/边长是骨架块的**组件固有尺寸**（用于撑出与真实内容一致的行高），非布局间距。 */
private val BLOCK_HEIGHT = 64.dp
private val TITLE_HEIGHT = 20.dp
private val LINE_HEIGHT = 14.dp

/** 与「我的」页档案卡上头像、完整度环同档的圆形占位。 */
private val AVATAR_SKELETON = 40.dp

/** 关键数字四联的格数。与 `ProfileStatStrip` 一致，改那边要改这边。 */
private const val STAT_STRIP_COLUMNS = 4
