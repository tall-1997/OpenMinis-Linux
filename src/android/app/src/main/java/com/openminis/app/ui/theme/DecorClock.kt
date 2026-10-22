package com.openminis.app.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue

/**
 * [T-decor-shared-clock] 装饰动画共用一个节流时钟。
 *
 * 现象：流式回复时整机发烫、掉帧；恢复/存储页/日志页滚动也明显更慢。
 *
 * 根因：项目里有 15 处各自独立的 `rememberInfiniteTransition`（转圈、脉冲、
 * 打字点、朗读波形…）。每一个都是一个独立的动画驱动，各自每帧向 Compose
 * 提交一次快照写入。屏幕上同时出现几个，就有几个时钟在跑 —— 动画本身很便宜，
 * **每帧的 snapshot 提交与重组检查**才是开销，而且它们互相独立、无法合并。
 *
 * 修法：在 App 根部起一个 30fps 的共享相位（[DecorClock]），所有装饰动画读同一个
 * `phase` 自己算波形。30fps 对转圈/脉冲这类装饰足够（人眼对这类循环运动不敏感），
 * 但把每帧快照写入从 N×60 降到 1×30。
 *
 * 不改变观感：各动画的周期、幅值仍由调用方决定，只是取样时钟统一了。
 */
internal object DecorClock {

    /** 一个完整相位周期的时间（秒）。调用方用 phase 的小数部分驱动波形。 */
    const val PERIOD_SECONDS = 6f

    /** 目标帧率。装饰动画不需要 60，30 足够且省一半提交。 */
    const val FPS = 30
}

/**
 * 当前共享相位，范围 `[0, 1)`，以 [DecorClock.PERIOD_SECONDS] 为周期循环。
 *
 * 读它的 composable 会在每个时钟 tick 重组 —— 这是刻意的：装饰动画本来就要
 * 每帧更新。区别在于所有这类 composable 共享同一个 tick 来源。
 */
internal val LocalDecorPhase = compositionLocalOf { 0f }

/**
 * 在 App 根部包一层：起共享时钟并把相位下发给子树。
 *
 * 只有存在装饰动画时才值得付这个代价，所以它是个独立的包装而不是塞进
 * MinisTheme —— 预览/测试可以不带。
 */
@Composable
fun DecorClockProvider(content: @Composable () -> Unit) {
    val transition = rememberInfiniteTransition(label = "decorClock")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = (DecorClock.PERIOD_SECONDS * 1000).toInt(),
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "decorPhase",
    )
    CompositionLocalProvider(LocalDecorPhase provides phase, content = content)
}

/**
 * 便捷读取：把共享相位换算成某个具体周期的 0..1 波形位置。
 *
 * @param cyclesPerClockPeriod 该动画在一个时钟周期内跑几轮（可带小数）。
 */
@Composable
fun decorPhaseFor(cyclesPerClockPeriod: Float): Float {
    val p = LocalDecorPhase.current
    val v = (p * cyclesPerClockPeriod) % 1f
    return if (v < 0f) v + 1f else v
}
