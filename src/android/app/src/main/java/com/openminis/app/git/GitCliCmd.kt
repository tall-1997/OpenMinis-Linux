package com.openminis.app.git

/**
 * Pure git command builders, extracted from [GitCli] (PR #306).
 *
 * Every builder here is a plain string function — no state, no shell
 * execution, no suspend — so quoting and flag selection stay unit-testable
 * in GitCliParseTest on the JVM. [GitCli] keeps the data models, the
 * parsers and the suspend exec layer; nothing in that file reaches for
 * git flags directly, every call goes through a builder below.
 */
object GitCliCmd {

    /**
     * `git` with an optional `-C <root>`: every panel command carries the
     * resolved repo root so a repo nested below the session cwd (the common
     * `workspace/<clone>` layout) works without cd-ing the shared shell —
     * cd-ing would move the agent's cwd out from under it.
     */
    fun gitPrefix(root: String?): String =
        if (root.isNullOrBlank()) "git" else "git -C ${shellQuote(root)}"

    /** Probe whether a directory is inside a work tree, plus identity. */
    fun probeCmd(root: String? = null): String {
        val g = gitPrefix(root)
        return "$g rev-parse --is-inside-work-tree 2>/dev/null && " +
            "$g rev-parse --show-toplevel && " +
            "$g branch --show-current; " +
            "printf 'cfg\\t%s\\t%s\\n' \"\$(" + g + " config user.name 2>/dev/null)\" " +
            "\"\$(" + g + " config user.email 2>/dev/null)\""
    }

    /**
     * Workspace-anchored repo discovery for when cwd itself is not a repo.
     *
     * The persistent shell boots at `/root` and its cwd drifts with every
     * agent `cd`, so `find .` from the live cwd can silently miss the
     * session's repos (cwd = /root) or pick the wrong winner (cwd =
     * workspace root with several clones). Discovery therefore anchors at
     * the session workspace's fixed guest path and emits ABSOLUTE repo-root
     * lines — they resolve identically from any cwd.
     *
     * Output: `<abs repo root>` per line, most recently active first.
     * Activity = max(newest HEAD commit time, newest shallow working-tree
     * mtime). The commit signal comes from `git log -1` (O(1) object read,
     * immune to PRoot I/O amplification) and covers repos the agent commits
     * into; the shallow mtime covers uncommitted top-level edits without a
     * full-tree walk (a per-repo full-tree `find` made the panel hang on
     * real devices, where every stat crosses PRoot at 10-50x cost). Ties
     * break by path so a same-second clone batch resolves deterministically.
     * [T-git-panel-zcode-discovery] mindepth 1: the workspace ITSELF is a
     * legal repo root (the host walk covers it, and the old mindepth-2 skip
     * made the sandbox fallback blind to it). maxdepth 5 matches the host
     * walk's four levels BELOW the workspace — find counts the workspace
     * itself as depth 0, so an N-level-deep repo's `.git` sits at depth N+1.
     */
    const val DISCOVER_CMD =
        "find /var/minis/workspace -maxdepth 5 -mindepth 1 -type d -name .git 2>/dev/null | " +
            "while IFS= read -r d; do " +
            "root=\${d%/.git}; " +
            "committed=\$(git -C \"\$root\" log -1 --format=%ct 2>/dev/null); " +
            "touched=\$(find \"\$root\" -maxdepth 2 -type f -not -path '*/.git/*' " +
            "-printf '%T@\\n' 2>/dev/null | sort -rn | head -1); " +
            "score=\$(printf '%s\\n%s\\n' \"\${committed:-0}\" \"\${touched:-0}\" " +
            "| sort -rn | head -1); " +
            "printf '%s %s\\n' \"\${score:-0}\" \"\$root\"; " +
            "done | sort -k1,1nr -k2 | sed 's/^[^ ]* //' | head -8"

    // `-uall` lists untracked files individually so the Changes tab granularity
    // matches the host JGit status (porcelain v1 collapses untracked dirs by default).
    fun statusCmd(root: String? = null) =
        "${gitPrefix(root)} -c core.quotepath=false status --porcelain -b -uall"

    /**
     * Local + remote refs for the branch switcher, newest commits first.
     * `--format` is single-quoted for the shell (`%(` is a bare subshell
     * opener otherwise) and separators use for-each-ref's `%NN` hex escapes
     * (`%1f` = 0x1f; the pretty-only `%xNN` stays literal here). The extra
     * `%(symref)` column is non-empty only for symbolic refs like
     * origin/HEAD, which [GitCli.parseBranches] drops — they are aliases, not
     * switchable branches. `%(upstream:short)` feeds the tracking label
     * (ZCode's for-each-ref contract column).
     */
    fun branchesCmd(root: String? = null) =
        "${gitPrefix(root)} for-each-ref --sort=-committerdate refs/heads refs/remotes " +
            "--format='%(objectname:short)%1f%(refname:short)%1f%(contents:subject)%1f%(symref)%1f%(upstream:short)' | head -40"

    /** Stash list: `stash@{n}<x1f>subject`, newest first. */
    fun stashCmd(root: String? = null) =
        "${gitPrefix(root)} stash list --format=%gd%x1f%s"

    /** Push/pull both refresh status afterwards; upstream must exist for push. */
    fun pushCmd(root: String? = null) = "${gitPrefix(root)} push"

    fun pullCmd(root: String? = null) = "${gitPrefix(root)} pull --ff-only"

    fun logCmd(limit: Int, root: String? = null, skip: Int = 0) =
        "${gitPrefix(root)} -c core.quotepath=false log -n $limit" +
            (if (skip > 0) " --skip $skip" else "") +
            " --date=unix --pretty=format:%H%x1f%h%x1f%an%x1f%ad%x1f%s"

    /** Per-file +/- line counts for staged (`--cached`) or unstaged changes. */
    fun numstatCmd(staged: Boolean, root: String? = null) =
        "${gitPrefix(root)} -c core.quotepath=false diff --numstat" +
            (if (staged) " --cached" else "")

    fun remoteCmd(root: String? = null) = "${gitPrefix(root)} remote -v"

    /**
     * History page, ZCode `getCommitGraph` contract:
     * `git log HEAD --branches --tags --remotes --date-order --topo-order
     * --skip N --max-count M --format=%H %P %h %an %ad %s %D`.
     *
     * The old command was HEAD-only — side branches never appeared in the
     * History tab, and there was no `%D` column, so the graph UI could not
     * render HEAD/main/origin/main badges. `%D` (decorations) is the last
     * field; [GitCli.parseGraphLogWithRefs] maps it to commit refs.
     */
    fun graphLogCmd(offset: Int, limit: Int, root: String? = null) =
        "${gitPrefix(root)} -c core.quotepath=false log HEAD --branches --tags --remotes " +
            "--date-order --topo-order --skip $offset --max-count $limit " +
            "--date=unix --pretty=format:%H%x1f%P%x1f%h%x1f%an%x1f%ad%x1f%s%x1f%D"

    /**
     * History fallback for repos whose HEAD cannot be resolved (`git log`
     * fails with bad/missing HEAD, and `--all` fails too because git still
     * parses HEAD first). Walking the concrete shas of every local+remote
     * ref sidesteps HEAD entirely — verified live in gitpanel_verify.py.
     * Same record shape as [graphLogCmd] so [GitCli.parseGraphLogWithRefs] is shared.
     */
    fun graphLogCmdFallback(offset: Int, limit: Int, root: String? = null): String {
        val p = gitPrefix(root)
        return "$p -c core.quotepath=false log \$($p for-each-ref " +
            "--format='%(objectname)' refs/heads refs/remotes | sort -u) " +
            "--date-order --topo-order -n $limit --skip $offset --date=unix " +
            "--pretty=format:%H%x1f%P%x1f%h%x1f%an%x1f%ad%x1f%s%x1f%D"
    }

    /**
     * Commit-detail sheet: identity + dates + subject + body, no diff.
     * Newlines inside %b stay literal — [GitCli.parseCommitDetail] reassembles
     * multi-line bodies from the trailing lines.
     */
    fun commitDetailCmd(hash: String, root: String? = null) =
        "${gitPrefix(root)} -c core.quotepath=false show --no-patch --date=unix " +
            "--pretty=format:%H%x1f%an%x1f%ae%x1f%at%x1f%cn%x1f%ce%x1f%ct%x1f%s%x1f%b " +
            shellQuote(hash)

    /**
     * Per-file stats of one commit; `--format=` leaves numstat rows only.
     * `-m --first-parent` pins merge commits to the first-parent diff — the
     * host JGit path has no combined-diff mode, and plain `git show` on a
     * merge would print the (usually empty) combined diff instead.
     */
    fun commitNumstatCmd(hash: String, root: String? = null) =
        "${gitPrefix(root)} -c core.quotepath=false show -m --first-parent --numstat --format= " +
            shellQuote(hash)

    /** Diff of a single file inside one commit (details sheet → diff view). */
    fun commitFileDiffCmd(hash: String, path: String, root: String? = null) =
        "${gitPrefix(root)} -c core.quotepath=false show -m --first-parent --date=iso " +
            shellQuote(hash) + " -- " + shellQuote(path)

    /**
     * Commit via a base64-relayed message file: the message never touches
     * shell quoting (b64 alphabet is quote-free), and `-F` preserves
     * multi-line bodies verbatim.
     *
     * [T-git-commit-temp-collision] The message file is keyed by the shell's
     * own pid (`$$`, expanded inside the single `bash -c` this runs in), not a
     * fixed name. The ExecutionCoordinator mutex is per session, so two
     * sessions committing concurrently used to clobber the same
     * `/tmp/.minis-git-msg` — the second `rm -f` could delete the first's file
     * mid-flight. A per-invocation path makes each commit independent.
     */
    fun buildCommitCommand(messageB64: String, root: String? = null): String {
        val msg = "/tmp/.minis-git-msg.$$"
        return "printf '%s' '$messageB64' | base64 -d > $msg && " +
            "${gitPrefix(root)} commit -F $msg && rm -f $msg"
    }

    /** POSIX single-quote escaping: `'` → `'\''`. */
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
