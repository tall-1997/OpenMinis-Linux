package com.openminis.app.quick

/**
 * [E-batch-quick-invocation] Depth-first collector for the text visible on
 * screen, modelled over a minimal node facade so the traversal (ordering,
 * dedup, the character cap, own-package skipping) is unit-testable without an
 * AccessibilityNodeInfo tree. The Android adapter lives in [ScreenContext].
 *
 * Why not simply walk AccessibilityNodeInfo directly in tests: Robolectric's
 * shadow tree is a stub, and MinisAccessibilityService routes every query
 * through AccessibilityQueryGuard. The decision logic worth testing is the
 * traversal policy, not the binder plumbing.
 */
class ScreenTextWalker(private val maxChars: Int = QuickPanelPolicy.MAX_SCREEN_TEXT_CHARS) {

    /** Minimal view of one accessibility node. */
    interface Node {
        val packageName: String?
        val text: String?
        val contentDescription: String?
        val children: List<Node>
    }

    /**
     * Walk [roots] in document order and join the distinct non-blank text
     * fragments with newlines, stopping at [maxChars] characters.
     *
     *  - Fragments belonging to [ownPackage] are skipped — when the floating
     *    ball double-tap races our own overlay, our own labels must not leak
     *    into the prompt. Children are still traversed: foreign windows can
     *    appear nested under an own-package root on some OEM skins.
     *  - A fragment already collected (some apps repeat the same string on
     *    nested nodes) is emitted once.
     *  - Traversal stops as soon as the cap is reached; the last fragment is
     *    cut mid-word rather than dropped, so the cap counts separators too.
     */
    fun collect(roots: List<Node>, ownPackage: String? = null): String {
        val seen = HashSet<String>()
        val out = StringBuilder()
        val stack = ArrayDeque<Node>()
        // Push in reverse so the first root is visited first (document order).
        for (root in roots.asReversed()) stack.addLast(root)
        while (out.length < maxChars && stack.isNotEmpty()) {
            val node = stack.removeLast()
            val ownNode = ownPackage != null && node.packageName == ownPackage
            if (!ownNode) {
                for (fragment in listOfNotNull(node.text, node.contentDescription)) {
                    val trimmed = fragment.trim()
                    if (trimmed.isEmpty()) continue
                    if (!seen.add(trimmed)) continue
                    if (out.isNotEmpty()) out.append('\n')
                    val room = maxChars - out.length
                    out.append(if (trimmed.length > room) trimmed.take(room) else trimmed)
                    if (out.length >= maxChars) break
                }
            }
            if (out.length < maxChars) {
                for (child in node.children.asReversed()) stack.addLast(child)
            }
        }
        return out.toString()
    }
}
