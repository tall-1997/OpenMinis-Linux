package com.openminis.app.git

/**
 * [T-git-panel-v2] One branch line for the panel's branch switcher, parsed
 * from `git for-each-ref` output (`hash<x1f>name<x1f>subject`).
 */
data class GitBranchRef(
    val name: String,
    val isCurrent: Boolean,
    val lastSubject: String,
)

/** One stash entry: `stash@{n}` + subject. */
data class GitStashEntry(val index: Int, val subject: String)
