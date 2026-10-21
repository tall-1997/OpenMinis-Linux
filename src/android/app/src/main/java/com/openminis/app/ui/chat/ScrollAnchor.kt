package com.openminis.app.ui.chat

/**
 * 流式回复期间是否把视口拉回底部。
 *
 * 之前的判定只看 isPinned：视口只要曾经贴过底就一直跟随，于是用户停在
 * 上一条回复里阅读时（firstVisibleIndex 已离开 0），新一轮回复一开始
 * 就被 scrollToItem(0) 拽走。
 *
 * 跟随必须同时满足：用户没有主动离开底部（pinned）、视口锚确实在最新一条
 * 的底部（index 0 且偏移为 0）、当前没有手指滚动、也不在用户打断窗口内。
 * index 0 但偏移不为 0 表示用户停在最新一条的中部，同样不跳。
 */
object ScrollAnchor {

    fun shouldFollow(
        isPinned: Boolean,
        firstVisibleIndex: Int,
        firstVisibleOffset: Int,
        isScrolling: Boolean,
        withinInterruptWindow: Boolean,
    ): Boolean = isPinned &&
        firstVisibleIndex == 0 &&
        firstVisibleOffset == 0 &&
        !isScrolling &&
        !withinInterruptWindow
}
