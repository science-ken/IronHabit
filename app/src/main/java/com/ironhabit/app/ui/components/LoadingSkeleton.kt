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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

private const val MIN_ALPHA = 0.3f
private const val MAX_ALPHA = 1.0f
private const val BREATH_DURATION_MS = 900

/** 以下三个高度是骨架块的**组件固有尺寸**（用于撑出与真实内容一致的行高），非布局间距。 */
private val BLOCK_HEIGHT = 64.dp
private val TITLE_HEIGHT = 20.dp
private val LINE_HEIGHT = 14.dp
