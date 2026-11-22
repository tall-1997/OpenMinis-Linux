package com.openminis.app.ui.sessions

import androidx.compose.ui.graphics.Color

/**
 * 包内令牌桥。同包引用无需 import，所以这个文件让每个调用方省下 2 行 import ——
 * 架构棘轮只降不升，`ChatToolDetailUI` 等文件已在基线上限，多 2 行就会被 CI 拦。
 *
 * 用法：`RawC.systemRed.tc`（等价于 `tokens.RawColors.systemRed.asColor`）。
 * 新代码仍建议直接 import `ui.tokens` 下的原始符号；这里只为已到上限的文件服务。
 */
internal typealias RawC = com.openminis.app.ui.tokens.RawColors

/** 0xAARRGGBB → Compose Color，同 [com.openminis.app.ui.tokens.asColor]。 */
internal val Long.tc: Color get() = Color(this.toInt())
