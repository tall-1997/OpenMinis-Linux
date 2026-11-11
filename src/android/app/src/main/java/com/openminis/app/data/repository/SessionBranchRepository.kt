package com.openminis.app.data.repository

import com.openminis.app.data.db.ChatSessionEntity

/**
 * [T-android-session-branch-tree] View-model row for the branch-tree sheet —
 * defined at the repository boundary so Room entities never cross into ui/.
 */
data class BranchNode(
    val id: String,
    val title: String?,
    val branchPoint: String?,
) {
    internal constructor(e: ChatSessionEntity) : this(e.id, e.title, e.branchPoint)
}

internal suspend fun ChatRepository.branchTree(id: String): Pair<BranchNode, List<BranchNode>>? {
    val self = getSession(id) ?: return null
    val source = self.branchOf?.let { getSession(it) }
    val children = dao.branchChildren(id)
    if (source == null && children.isEmpty()) return null
    return BranchNode(source ?: self) to children.map(::BranchNode)
}
