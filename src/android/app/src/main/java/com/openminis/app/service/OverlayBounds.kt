package com.openminis.app.service

/**
 * 悬浮胶囊的可见性规则。
 *
 * 之前边界用整屏像素且从 0 起算：y = 0 落进状态栏被挡住，底部不扣除
 * 手势条，横屏时胶囊下沿被系统手势区吃掉。位置只存一份，横竖屏互相覆盖。
 */
object OverlayBounds {

    data class Screen(
        val width: Int,
        val height: Int,
        val topInset: Int,
        val bottomInset: Int,
    )

    /** 把胶囊左上角夹进安全区，保证整颗胶囊可见。 */
    fun clamp(
        x: Int,
        y: Int,
        capsuleWidth: Int,
        capsuleHeight: Int,
        screen: Screen,
    ): Pair<Int, Int> {
        val maxX = (screen.width - capsuleWidth).coerceAtLeast(0)
        val minY = screen.topInset.coerceAtLeast(0)
        val maxY = (screen.height - screen.bottomInset - capsuleHeight).coerceAtLeast(minY)
        return x.coerceIn(0, maxX) to y.coerceIn(minY, maxY)
    }

    data class Positions(val portrait: Pair<Int, Int>?, val landscape: Pair<Int, Int>?) {
        fun forOrientation(portrait: Boolean): Pair<Int, Int>? =
            if (portrait) this.portrait else landscape
    }

    /** 横竖屏各记一份位置，旋转后读回对应方向的。 */
    fun positions(portrait: Pair<Int, Int>?, landscape: Pair<Int, Int>?): Positions =
        Positions(portrait, landscape)
}
