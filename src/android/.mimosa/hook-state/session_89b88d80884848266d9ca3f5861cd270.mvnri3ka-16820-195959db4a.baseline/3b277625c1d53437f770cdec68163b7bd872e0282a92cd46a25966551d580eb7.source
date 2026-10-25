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
import kotlin.math.roundToInt

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

// ─── 波形派生器 ───────────────────────────────────────────────────────────────
// [T-decor-shared-clock] PR #126 只建了时钟没接消费者——LocalDecorPhase 零读取、
// 7 个文件仍各自 rememberInfiniteTransition，共享时钟成了纯开销。以下派生器
// 是迁移的统一入口；全部把周期量化成整数轮，保证时钟相位回绕（1→0）时波形
// 连续不跳变。量化后的实际周期与请求值最多差半轮，装饰动画无感。

/** 时钟周期的毫秒形式（量化基准）。 */
private const val DECOR_PERIOD_MS = (DecorClock.PERIOD_SECONDS * 1000).toInt()

/** 量化成整数轮：一个时钟周期内跑几轮。 */
private fun quantizedCycles(periodMs: Int): Int =
    (DECOR_PERIOD_MS.toFloat() / periodMs).roundToInt().coerceAtLeast(1)

/**
 * 连续旋转角（度），转圈/扫描弧用。等价 `infiniteRepeatable(tween(periodMs,
 * LinearEasing), Restart)` 的 0→360。
 */
@Composable
internal fun decorRotationAngle(periodMs: Int): Float {
    val phase = LocalDecorPhase.current
    return (phase * quantizedCycles(periodMs) * 360f) % 360f
}

/**
 * 线性扫描（[from] → [to] 后回绕），shimmer 平移用。等价
 * `infiniteRepeatable(tween(periodMs, LinearEasing))` 的 Restart 锯齿波。
 */
@Composable
internal fun decorLinearSweep(from: Float, to: Float, periodMs: Int): Float {
    val phase = LocalDecorPhase.current
    val t = (phase * quantizedCycles(periodMs)) % 1f
    return from + (to - from) * t
}

/**
 * 余弦脉冲（[min] → [max] → [min]），呼吸灯/打字点用。等价
 * `infiniteRepeatable(tween(periodMs / 2, FastOutSlowIn), Reverse)` 的
 * 往返包络。[staggerFraction] 是多元素错峰的相位偏移（如打字点 i×120/700）。
 */
@Composable
internal fun decorPulse(
    min: Float,
    max: Float,
    periodMs: Int,
    staggerFraction: Float = 0f,
): Float {
    val phase = LocalDecorPhase.current
    val t = (phase * quantizedCycles(periodMs) + staggerFraction) % 1f
    val envelope = 0.5f - 0.5f * kotlin.math.cos(2f * Math.PI.toFloat() * t)
    return min + (max - min) * envelope
}
