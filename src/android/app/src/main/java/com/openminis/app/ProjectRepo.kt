package com.openminis.app

/**
 * Canonical public GitHub identity for this Linux fork (Minis Ultra).
 * About / updates / bug reports must point here — not the upstream
 * OpenMinis/OpenMinis iOS+Android monorepo.
 */
object ProjectRepo {
    const val OWNER = "tall-1997"
    const val REPO = "OpenMinis-Linux"
    const val URL = "https://github.com/$OWNER/$REPO"
    const val ISSUES_URL = "$URL/issues"
    const val ISSUES_NEW_URL = "$URL/issues/new"
    const val RELEASES_URL = "$URL/releases"
    const val RELEASES_API = "https://api.github.com/repos/$OWNER/$REPO/releases"
}
