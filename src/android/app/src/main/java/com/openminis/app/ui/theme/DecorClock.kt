package com.openminis.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
 * [T-decor-consumer-gate] 消费者计数：`rememberInfiniteTransition` 从进入
 * composition 起就永久占住帧时钟，即使屏幕上没有任何装饰动画（比如聊天静止、
 * 用户停在设置页）也照跑不误。现在 [decorPhaseFor] / decor* 派生器的每个消费点
 * 都通过 [DecorClockConsumers] 注册活跃计数，[DecorClockProvider] 只在计数 > 0
 * 时跑帧循环、计数归零即取消（LaunchedEffect 离开组合被协程取消），有消费者
 * 再进入时恢复。视觉 tick 不变：相位仍是 0→1 线性循环 [DecorClock.PERIOD_SECONDS]
 * 秒，重新起表从 0 开始 —— 而计数归零意味着没有任何东西在读相位，跳变不可见。
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
 * [T-decor-consumer-gate] 活跃消费者注册表。
 *
 * 计数用 Compose snapshot state 而非 AtomicInteger：0↔1 的翻转要驱动
 * [DecorClockProvider] 起停帧循环，普通原子量没有重组语义。纯 JVM 单测里
 * `intValue` 读写同样有效（global snapshot），所以注册表行为可以直接测。
 */
internal object DecorClockConsumers {

    /** 当前活跃（仍在 composition 中）的装饰动画消费者数量。 */
    val activeCount = mutableIntStateOf(0)

    /** 供 [DecorClockProvider] 观察的布尔视图：是否有任何消费者。 */
    val hasConsumers: Boolean get() = activeCount.intValue > 0

    /** 消费点进入 composition 时调用（[rememberDecorClockConsumer] 前半）。 */
    fun register() {
        activeCount.intValue += 1
    }

    /**
     * 消费点离开 composition 时调用。防御下溢：register/unregister 必须配对，
     * 但 onDispose 与再组合交错时宁可计数停在 0 也不要变负（负数会让
     * hasConsumers 永远为 true，时钟永远停不下来）。
     */
    fun unregister() {
        if (activeCount.intValue > 0) activeCount.intValue -= 1
    }
}

/**
 * [T-decor-consumer-gate] 在每个消费点（decorPhaseFor / decor* 派生器）调用：
 * 进入组合注册一个活跃消费者、离开时注销。`DisposableEffect` 的配对语义保证
 * register/unregister 恰好各执行一次。
 */
@Composable
internal fun rememberDecorClockConsumer() {
    DisposableEffect(Unit) {
        DecorClockConsumers.register()
        onDispose { DecorClockConsumers.unregister() }
    }
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
 * [T-decor-consumer-gate] 帧循环由 [DecorClockConsumers.hasConsumers] 门控：
 * `if (hasConsumers)` 守卫 [LaunchedEffect] 的进出 —— 计数归零时 effect 离开
 * 组合、协程被取消（停表），首次有消费者时重新进入组合（恢复）。相位每帧由
 * `withFrameNanos` 的绝对时间线性推进，等价原 `infiniteRepeatable(tween(PERIOD,
 * LinearEasing), Restart)` 的取样，只有起停时机由消费者数量决定。
 *
 * 只有存在装饰动画时才值得付这个代价，所以它是个独立的包装而不是塞进
 * MinisTheme —— 预览/测试可以不带。
 */
@Composable
fun DecorClockProvider(content: @Composable () -> Unit) {
    val hasConsumers = DecorClockConsumers.hasConsumers
    var phase by remember { mutableStateOf(0f) }
    if (hasConsumers) {
        LaunchedEffect(Unit) {
            val periodNanos = (DecorClock.PERIOD_SECONDS * 1_000_000_000f).toLong()
            var start = 0L
            while (true) {
                withFrameNanos { now ->
                    if (start == 0L) start = now
                    val t = ((now - start) % periodNanos).toFloat() / periodNanos
                    phase = if (t < 0f) t + 1f else t
                }
            }
        }
    }
    CompositionLocalProvider(LocalDecorPhase provides phase, content = content)
}

/**
 * 便捷读取：把共享相位换算成某个具体周期的 0..1 波形位置。
 *
 * [T-decor-consumer-gate] 首次调用（进入组合）注册活跃消费者、onDispose 注销
 * ——这是时钟起停的唯一信号源。
 *
 * @param cyclesPerClockPeriod 该动画在一个时钟周期内跑几轮（可带小数）。
 */
@Composable
fun decorPhaseFor(cyclesPerClockPeriod: Float): Float {
    rememberDecorClockConsumer()
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
 *
 * [T-decor-consumer-gate] 消费点：进入组合注册、离开注销（与 [decorPhaseFor]
 * 相同的起停信号）。
 */
@Composable
internal fun decorRotationAngle(periodMs: Int): Float {
    rememberDecorClockConsumer()
    val phase = LocalDecorPhase.current
    return (phase * quantizedCycles(periodMs) * 360f) % 360f
}

/**
 * 线性扫描（[from] → [to] 后回绕），shimmer 平移用。等价
 * `infiniteRepeatable(tween(periodMs, LinearEasing))` 的 Restart 锯齿波。
 *
 * [T-decor-consumer-gate] 消费点：进入组合注册、离开注销。
 */
@Composable
internal fun decorLinearSweep(from: Float, to: Float, periodMs: Int): Float {
    rememberDecorClockConsumer()
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
    rememberDecorClockConsumer()
    val phase = LocalDecorPhase.current
    val t = (phase * quantizedCycles(periodMs) + staggerFraction) % 1f
    val envelope = 0.5f - 0.5f * kotlin.math.cos(2f * Math.PI.toFloat() * t)
    return min + (max - min) * envelope
}
