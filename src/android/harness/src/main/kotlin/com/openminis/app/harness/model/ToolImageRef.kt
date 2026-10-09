package com.openminis.app.harness.model

import kotlinx.serialization.Serializable

/**
 * Adapted from taixu ToolImageRef (GPL-3.0-or-later).
 * https://github.com/wkbin/taixu
 *
 * 工具产物中的图片附件引用（纯路径 / URI，不携带二进制）。
 *
 * - [uri] 通常以 `screenshots://` 或自定义 scheme 开头，由 UI 层落地。
 * - UI 异步渲染本地文件；模型侧只看 [id] + [uri] + [mime] + 尺寸描述。
 */
@Serializable
data class ToolImageRef(
    val id: String,
    val uri: String,
    val mime: String = "image/png",
    val width: Int = 0,
    val height: Int = 0,
    val caption: String = "",
)
