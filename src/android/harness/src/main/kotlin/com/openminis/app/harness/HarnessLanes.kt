package com.openminis.app.harness

/**
 * Adapted from taixu SessionTreeStore.MAIN_LANE (GPL-3.0-or-later).
 * https://github.com/wkbin/taixu
 *
 * 主 lane 名的**唯一字面量来源**。taixu 把它放在 SessionTreeStore 里；我方
 * SessionTreeStore 尚未移植（2.4/2.5 闭包），先提到 harness 根部，queue 与
 * operation 都引用它，避免 "main" 字面量在多个包里漂移。等 SessionTreeStore
 * 落地时，它反过来引用本常量。
 */
object HarnessLanes {
    const val MAIN_LANE = "main"
}
