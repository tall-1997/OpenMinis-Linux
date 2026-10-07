package com.openminis.app.ui.markdown

import androidx.compose.runtime.compositionLocalOf
import com.openminis.app.ui.chat.CodeBlockRunRouter

/**
 * 当前正在运行的代码块正文集合（由 ChatViewModel 暴露、ChatScreen 提供）。
 *
 * 用「代码正文」而不是 block id 做 key：markdown 渲染是纯函数派生的，
 * block id 在每次重组/重解析时都可能变，而同一段代码在界面上只会出现一次。
 * 空集合默认值让所有不提供该状态的调用点（ChatSubAgentBar、
 * SkillsManagementScreen 等）保持原样，无需改一行。
 */
val LocalCodeBlockRunState = compositionLocalOf { emptySet<String>() }

/**
 * 代码块是否可运行的唯一判据，UI 只看这一个入口，避免「按钮点得动但必失败」。
 */
fun isRunnableCodeBlock(language: String, hasHandler: Boolean): Boolean =
    hasHandler && CodeBlockRunRouter.isSupported(language)

/**
 * 「▶ 运行」回调的注入点：(lang, code) -> Unit。
 *
 * 与 [LocalCodeBlockRunState] 的差别：前者是「正在跑哪些块」（状态），
 * 这个是「点了按钮交给谁」（动作）。默认 null —— 不提供时按钮直接不渲染，
 * MarkdownText 的其它使用方（ChatSubAgentBar、SkillsManagement 等）无需改动；
 * ChatScreen 在 provider 区块 provide `viewModel::runCodeBlockInline`，聊天流
 * 的全部渲染路径（StreamingMarkdownText / MarkdownBlock / MarkdownText）经
 * CompositionLocal 拿到它，免去在 7336 行的 ChatScreen 里逐调用点穿参。
 */
val LocalMarkdownCodeRunner = compositionLocalOf<((lang: String, code: String) -> Unit)?> { null }