package com.openminis.app.ui.chat

/**
 * 流式回复期间是否把视口拉回底部。
 *
 * 语义与 [ChatScrollPolicy] 的两态模型对齐：
 * - **Pinned** = 视口吸附最新边。这是模式的承诺，因此 Pinned 下的流式
 *   tick 一律跟随——**不再检查 firstVisibleIndex/offset**。
 * - **Reading** = 用户在阅读（离开底部的每一次手势终点都会转入 Reading：
 *   DragStop 离底、fling 离底的停止边缘 rearm、FAB-up 落点）。Reading 下
 *   本函数不会被调用出"跟随"结果（isPinned=false 直接 false）。
 *
 * 为什么去掉 firstVisibleIndex==0 && offset==0 的旧条件：reverseLayout 的
 * LazyList 在 index 0 头部插入新 flat item 时会把锚点保持在上一个 item，
 * 视口被"推离"index 0 —— 这不是用户的阅读意图，而是插入的几何副作用。
 * 旧条件把这种推离误判为阅读，于是新任务开始后（SEND-PATH 的两次跳底都
 * 落在新消息进入扁平列表之前，见 T-android-send-follow-race）工具卡片/
 * 思考块/正文一旦插入就把视口越推越远，且再无事件把模式拉回 —— 视口
 * 永久钉在上一轮结束位置。
 *
 * "停在最新一条中部（offset>0）不跳"的场景由模式转换保证：能停在那里
 * 必然经过手势，DragStop 的 isNearBottom 判定已经把该状态归入 Reading。
 * Pinned 且非滚动中时 firstIdx>0 只剩插入推离一种成因 —— 必须跟随。
 */
object ScrollAnchor {

    fun shouldFollow(
        isPinned: Boolean,
        @Suppress("UNUSED_PARAMETER") firstVisibleIndex: Int,
        @Suppress("UNUSED_PARAMETER") firstVisibleOffset: Int,
        isScrolling: Boolean,
        withinInterruptWindow: Boolean,
    ): Boolean = isPinned && !isScrolling && !withinInterruptWindow
}
