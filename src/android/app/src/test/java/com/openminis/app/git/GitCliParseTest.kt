package com.openminis.app.git

import com.openminis.app.git.GitCli.GitFileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-parser tests for [GitCli] — no Robolectric, no sandbox. The shell
 * layer is a thin wrapper; everything that can go wrong structurally lives
 * in the parsers, so they get the coverage.
 */
class GitCliParseTest {

    // ---------------------------------------------------------------- probe

    @Test
    fun `probe parses repo root branch and identity`() {
        val out = "true\n/var/minis/workspace/myrepo\nmain\ncfg\tAlice\talice@example.com\n"
        val info = GitCli.parseProbe(out, exitCode = 0)
        assertTrue(info.isRepo)
        assertEquals("/var/minis/workspace/myrepo", info.root)
        assertEquals("main", info.branch)
        assertEquals("Alice", info.userName)
        assertEquals("alice@example.com", info.userEmail)
    }

    @Test
    fun `probe tolerates missing identity and detached HEAD`() {
        val out = "true\n/repo\n\ncfg\t\t\n"
        val info = GitCli.parseProbe(out, exitCode = 0)
        assertTrue(info.isRepo)
        assertEquals("HEAD", info.branch) // blank branch → detached-HEAD label
        assertNull(info.userName)
        assertNull(info.userEmail)
    }

    @Test
    fun `probe reports non-repo with git's own error`() {
        val info = GitCli.parseProbe("", exitCode = 128)
        assertFalse(info.isRepo)

        val noisy = GitCli.parseProbe("fatal: not a git repository\n", exitCode = 128)
        assertFalse(noisy.isRepo)
        assertTrue(noisy.error!!.contains("not a git repository"))
    }

    // --------------------------------------------------------------- status

    @Test
    fun `status parses upstream tracking line with ahead and behind`() {
        val out = "## main...origin/main [ahead 2, behind 1]\n M src/a.kt\n"
        val s = GitCli.parseStatus(out)
        assertEquals(2, s.ahead)
        assertEquals(1, s.behind)
        assertEquals("origin/main", s.upstream)
        assertEquals(1, s.entries.size)
        assertFalse(s.entries[0].staged)
        assertEquals('M', s.entries[0].code)
    }

    @Test
    fun `status handles initial commit branch line`() {
        val out = "## No commits yet on main\n?? README.md\n"
        val s = GitCli.parseStatus(out)
        assertEquals(0, s.ahead)
        assertNull(s.upstream)
        assertEquals(1, s.entries.size)
        assertTrue(s.entries[0].untracked)
        assertEquals("README.md", s.entries[0].path)
    }

    @Test
    fun `status splits mixed MM entry into staged and unstaged rows`() {
        val s = GitCli.parseStatus("MM both.txt\n")
        assertEquals(2, s.entries.size)
        val staged = s.entries.single { it.staged }
        val unstaged = s.entries.single { !it.staged }
        assertEquals('M', staged.code)
        assertEquals('M', unstaged.code)
        assertEquals(staged.path, unstaged.path)
        assertEquals(1, s.stagedCount)
    }

    @Test
    fun `status parses rename keeping orig and new path`() {
        val s = GitCli.parseStatus("R  old name.txt -> new name.txt\n")
        assertEquals(1, s.entries.size)
        val e = s.entries[0]
        assertTrue(e.staged)
        assertEquals('R', e.code)
        assertEquals("old name.txt", e.origPath)
        assertEquals("new name.txt", e.path)
    }

    @Test
    fun `status parses added deleted and untracked entries`() {
        val out = """
            A  added.kt
            D  deleted.kt
            ?? scratch/
        """.trimIndent()
        val s = GitCli.parseStatus(out)
        assertEquals(3, s.entries.size)
        assertTrue(s.entries.any { it.path == "added.kt" && it.staged && it.code == 'A' })
        assertTrue(s.entries.any { it.path == "deleted.kt" && it.staged && it.code == 'D' })
        assertTrue(s.entries.any { it.path == "scratch/" && it.untracked })
    }

    @Test
    fun `status on clean tree is clean`() {
        val s = GitCli.parseStatus("## main...origin/main\n")
        assertTrue(s.clean)
        assertEquals(0, s.stagedCount)
    }

    @Test
    fun `status ignores malformed short lines`() {
        val s = GitCli.parseStatus("## main\nxy\n?? ok.txt\n")
        assertEquals(1, s.entries.size)
    }

    @Test
    fun `status parses unicode paths unescaped`() {
        // -c core.quotepath=false keeps UTF-8 verbatim.
        val s = GitCli.parseStatus("?? 中文文档/笔记.md\n")
        assertEquals("中文文档/笔记.md", s.entries.single().path)
    }

    // ------------------------------------------------------------------ log

    @Test
    fun `log parses all five fields`() {
        val out = "a1b2c3d4e5f6\u001fa1b2c3d\u001fAlice\u001f1700000000\u001ffix: subject here"
        val commits = GitCli.parseLog(out)
        assertEquals(1, commits.size)
        val c = commits[0]
        assertEquals("a1b2c3d4e5f6", c.hash)
        assertEquals("a1b2c3d", c.shortHash)
        assertEquals("Alice", c.author)
        assertEquals(1700000000L, c.epochSeconds)
        assertEquals("fix: subject here", c.subject)
    }

    @Test
    fun `log parses multiple commits and unicode subjects`() {
        val out = listOf(
            "h1\u001fs1\u001f张三\u001f1700000001\u001ffeat: 中文主题",
            "h2\u001fs2\u001fBob\u001f1700000002\u001fchore: bump",
        ).joinToString("\n")
        val commits = GitCli.parseLog(out)
        assertEquals(2, commits.size)
        assertEquals("feat: 中文主题", commits[0].subject)
        assertEquals("Bob", commits[1].author)
    }

    @Test
    fun `log drops malformed lines and blank padding`() {
        val out = "\nh1\u001fs1\u001fA\u001fnotanumber\u001fsubject\nbrokenline\n"
        val commits = GitCli.parseLog(out)
        // notanumber → epoch falls back to 0 but the record survives;
        // brokenline has no field separators → dropped.
        assertEquals(1, commits.size)
        assertEquals(0L, commits[0].epochSeconds)
        assertEquals("subject", commits[0].subject)
    }

    @Test
    fun `log on empty output yields empty list`() {
        assertEquals(0, GitCli.parseLog("").size)
    }

    // ------------------------------------------------------------- shell-ish

    @Test
    fun `shellQuote wraps and escapes single quotes`() {
        assertEquals("'plain.txt'", GitCliCmd.shellQuote("plain.txt"))
        assertEquals("'with space.txt'", GitCliCmd.shellQuote("with space.txt"))
        assertEquals("'it'\\''s.txt'", GitCliCmd.shellQuote("it's.txt"))
        assertEquals("'中文.md'", GitCliCmd.shellQuote("中文.md"))
    }

    @Test
    fun `commit command carries base64 payload and uses -F`() {
        val msg = "feat: add git panel\n\n- multi-line body with 'quotes' and \$dollar"
        val b64 = java.util.Base64.getEncoder().encodeToString(msg.toByteArray(Charsets.UTF_8))
        val cmd = GitCliCmd.buildCommitCommand(b64)
        assertTrue(cmd.contains("git commit -F /tmp/.minis-git-msg"))
        // [T-git-commit-temp-collision] Unique per invocation via the shell pid,
        // so two concurrent session commits cannot clobber one message file.
        assertTrue("per-pid temp path expected: $cmd", cmd.contains("/tmp/.minis-git-msg.\$\$"))
        assertTrue(cmd.contains(b64))
        // The b64 alphabet is quote-free, so the payload survives single quotes.
        assertFalse(b64.contains("'"))
        // Round-trip: what the guest decodes is exactly the message.
        val embedded = cmd.substringAfter("printf '%s' '").substringBefore("' |")
        assertEquals(
            msg,
            String(java.util.Base64.getDecoder().decode(embedded), Charsets.UTF_8),
        )
    }

    // ------------------------------------------------ commit-message prompt

    @Test
    fun `generator sanitize strips code fence and label`() {
        assertEquals(
            "fix: thing",
            GitCommitMessageGenerator.sanitize("```\nfix: thing\n```"),
        )
        assertEquals(
            "fix: thing",
            GitCommitMessageGenerator.sanitize("Commit message: fix: thing"),
        )
        assertNull(GitCommitMessageGenerator.sanitize("   "))
        assertNull(GitCommitMessageGenerator.sanitize(null))
    }

    @Test
    fun `generator sanitize caps runaway output at 20 lines`() {
        val raw = (1..40).joinToString("\n") { "line$it" }
        val out = GitCommitMessageGenerator.sanitize(raw)!!
        assertEquals(20, out.lines().size)
        assertEquals("line1", out.lines().first())
        assertEquals("line20", out.lines().last())
    }

    @Test
    fun `generator prompt caps huge diffs`() {
        val huge = "x".repeat(60_000)
        val (system, user) = GitCommitMessageGenerator.buildPrompt(huge)
        assertTrue(system.contains("commit message"))
        assertTrue(user.length < huge.length)
        assertTrue(user.contains("truncated"))
    }

    @Test
    fun `generator prompt keeps small diffs verbatim`() {
        val small = "diff --git a/f b/f\n+hello\n"
        val (_, user) = GitCommitMessageGenerator.buildPrompt(small)
        assertTrue(user.contains(small))
    }

    // ----------------------------------------------------------- entry model

    @Test
    fun `status entry equality supports list diffing in compose`() {
        val e = GitFileEntry("a.txt", "a.txt", 'M', staged = true)
        assertNotNull(e.copy())
        assertEquals(e, e.copy())
        assertFalse(e.equals(e.copy(staged = false)))
    }

    // ------------------------------------------------- nested repo discovery

    @Test
    fun `discover parses absolute repo paths into path and display name in order`() {
        val out = "/var/minis/workspace/OpenMinis-Linux\n/var/minis/workspace/notes\n"
        assertEquals(
            listOf(
                GitCli.DiscoveredRepo("/var/minis/workspace/OpenMinis-Linux", "OpenMinis-Linux"),
                GitCli.DiscoveredRepo("/var/minis/workspace/notes", "notes"),
            ),
            GitCli.parseDiscover(out),
        )
    }

    @Test
    fun `discover ignores blank lines relative paths and stray text`() {
        val out = "\n/var/minis/workspace/a\n./a/.git\nnot-a-path\n/var/minis/workspace/b with space\n"
        assertEquals(
            listOf(
                GitCli.DiscoveredRepo("/var/minis/workspace/a", "a"),
                GitCli.DiscoveredRepo("/var/minis/workspace/b with space", "b with space"),
            ),
            GitCli.parseDiscover(out),
        )
    }

    @Test
    fun `discover keeps activity order and skips trailing slash lines`() {
        val out = "/w/recent\n/w/older\n/w/stale\n"
        assertEquals(
            listOf("recent", "older", "stale"),
            GitCli.parseDiscover(out).map { it.name },
        )
        assertTrue(GitCli.parseDiscover("/w/x/\n").isEmpty())
    }

    @Test
    fun `discover on empty output yields no candidates`() {
        assertTrue(GitCli.parseDiscover("").isEmpty())
        assertTrue(GitCli.parseDiscover("cfg\t\t\n").isEmpty())
    }

    @Test
    fun `git prefix is bare without root and quoted with one`() {
        assertEquals("git", GitCliCmd.gitPrefix(null))
        assertEquals("git", GitCliCmd.gitPrefix(""))
        assertEquals("git -C '/var/w/r'", GitCliCmd.gitPrefix("/var/w/r"))
        assertEquals("git -C '/a b/'", GitCliCmd.gitPrefix("/a b/"))
    }

    @Test
    fun `probe and status commands carry the resolved root`() {
        assertFalse(GitCliCmd.probeCmd().contains("-C"))
        assertTrue(GitCliCmd.probeCmd("r").contains("git -C 'r' rev-parse --is-inside-work-tree"))
        // cfg lookup must resolve against the same repo, not the shell cwd
        assertTrue(GitCliCmd.probeCmd("r").contains("\$(git -C 'r' config user.name"))
        assertEquals(
            "git -C 'r' -c core.quotepath=false status --porcelain -b -uall",
            GitCliCmd.statusCmd("r"),
        )
        assertTrue(GitCliCmd.logCmd(5, "r").startsWith("git -C 'r' -c core.quotepath=false log -n 5"))
        assertTrue(GitCliCmd.buildCommitCommand("QQ==", "r").contains("git -C 'r' commit -F"))
        // default stays cwd-bound so existing callers and tests are unaffected
        assertEquals(GitCliCmd.statusCmd(), GitCliCmd.statusCmd(null))
    }

    @Test
    fun `probe error text drops shell watchdog noise`() {
        val out = "cfg\t\t\n" +
            "/bin/bash: line 39: 14394 Killed\n" +
            "( sleep 600; kill -TERM -$$ 2> /dev/null; sleep 3; kill -KILL -$$ 2> /dev/null )\n"
        val info = GitCli.parseProbe(out, exitCode = 128)
        assertFalse(info.isRepo)
        // everything was noise → fall back to the exit code, not raw stderr
        assertEquals("exit=128", info.error)

        val mixed = GitCli.parseProbe("fatal: not a git repository\n/bin/bash: line 1: 9 Killed\n", 128)
        assertEquals("fatal: not a git repository", mixed.error)
    }

    // -------------------------------------------------------------- numstat

    @Test
    fun `numstat maps paths to added and deleted counts`() {
        val out = "12\t3\tsrc/a.kt\n0\t9\tREADME.md\n"
        val m = GitCli.parseNumstat(out)
        assertEquals(2, m.size)
        assertEquals(GitCli.NumStat(12, 3), m["src/a.kt"])
        assertEquals(GitCli.NumStat(0, 9), m["README.md"])
        assertFalse(m["README.md"]!!.binary)
    }

    @Test
    fun `numstat marks pure rename with new path key`() {
        val m = GitCli.parseNumstat("-\t-\tsrc/{old.kt => new.kt}\n")
        assertEquals(1, m.size)
        assertTrue(m["src/new.kt"]!!.binary) // 0/0 rename: no counts to show
        assertNull(m["src/{old.kt => new.kt}"])
    }

    @Test
    fun `numstat marks binary rows and skips garbage`() {
        val m = GitCli.parseNumstat("-\t-\timg.png\nx\ty\n12\t4\tsrc/c.kt\n")
        assertEquals(2, m.size)
        assertTrue(m["img.png"]!!.binary)
        assertEquals(GitCli.NumStat(12, 4), m["src/c.kt"])
    }

    // ------------------------------------------------------------- logPage

    @Test
    fun `graph log carries parents and tolerates short lines`() {
        val out = listOf(
            "h1\u001f\u001f7f1\u001fAlice\u001f1700000000\u001froot",
            "h2\u001fh1\u001f7f2\u001fBob\u001f1699999900\u001fsecond",
            "short-line",
            "",
        ).joinToString("\n")
        val l = GitCli.parseGraphLog(out)
        assertEquals(2, l.size)
        assertEquals(emptyList<String>(), l[0].parents)
        assertEquals(listOf("h1"), l[1].parents)
        assertEquals("7f1", l[0].shortHash)
    }

    @Test
    fun `graph log command pins hash delimiters and skip paging`() {
        val cmd = GitCliCmd.graphLogCmd(offset = 50, limit = 50)
        assertTrue(cmd.contains("log"))
        assertTrue(cmd.contains("-n 50 --skip 50"))
        assertTrue(cmd.contains("%x1f%P%x1f%h"))
        assertTrue(cmd.contains("--date=unix"))
    }

    // --------------------------------------------------------------- remote

    @Test
    fun `remote prefers push url and strips the fetch or push marker`() {
        val same = "origin\thttps://github.com/a/b.git (fetch)\n" +
            "origin\thttps://github.com/a/b.git (push)\n"
        assertEquals("https://github.com/a/b.git", GitCli.parseRemote(same))

        // fetch and push differ: the push entry must win, marker stripped.
        val split = "origin\thttps://github.com/a/b-fetch.git (fetch)\n" +
            "origin\tssh://git@github.com/a/b.git (push)\n"
        assertEquals("ssh://git@github.com/a/b.git", GitCli.parseRemote(split))
    }

    @Test
    fun `remote returns null for blank output`() {
        assertNull(GitCli.parseRemote(""))
        assertNull(GitCli.parseRemote("\n"))
        assertNull(GitCli.parseRemote("garbage-without-tab"))
    }

    // --------------------------------------------------------- commitDetail

    @Test
    fun `commit detail parses record and reassembles multiline body`() {
        val hash = "aabbccddeeff00112233445566778899aabbccdd"
        val rec = hash + "\u001fAlice\u001fa@x.io\u001f1700000000\u001fBob\u001fb@x.io\u001f1699999999" +
            "\u001fsubject line\u001f"
        val out = rec + "\nfirst body line\nsecond body line\n\n"
        val d = GitCli.parseCommitDetail(out)
        assertNotNull(d)
        assertEquals(hash, d!!.hash)
        assertEquals("Alice", d.author)
        assertEquals("Bob", d.committer)
        assertEquals(1699999999L, d.committerEpoch)
        assertEquals("subject line", d.subject)
        assertEquals("first body line\nsecond body line", d.body)
    }

    @Test
    fun `commit detail skips watchdog noise before record and inside body`() {
        val hash = "112233445566778899aabbccddeeff0011223344"
        val rec = hash + "\u001fA\u001fa@x\u001f1\u001fB\u001fb@x\u001f2\u001fsubj\u001f"
        val noise = "/bin/bash: line 1: 9 Killed"
        val d = GitCli.parseCommitDetail(noise + "\n" + rec + "\nbody\n" + noise + "\n")
        assertNotNull(d)
        assertEquals("subj", d!!.subject)
        assertEquals("body", d.body)
    }

    @Test
    fun `commit detail returns null when no 40 char record exists`() {
        assertNull(GitCli.parseCommitDetail("fatal: bad revision\n"))
        assertNull(GitCli.parseCommitDetail(""))
    }

    // -------------------------------------------------------- commitNumstat

    @Test
    fun `commit numstat parses counts and binary marker`() {
        val out = "12\t3\tsrc/a.kt\n-\t-\timg.png\n-\t-\tsrc/{x.kt => y.kt}\n"
        val l = GitCli.parseCommitNumstat(out)
        assertEquals(3, l.size)
        assertEquals("src/a.kt", l[0].path)
        assertEquals(12, l[0].added)
        assertEquals(3, l[0].deleted)
        assertFalse(l[0].binary)
        assertTrue(l[1].binary)
        assertNull(l[1].renameFrom)
        assertEquals("src/y.kt", l[2].path)
        assertEquals("src/x.kt", l[2].renameFrom)
    }

    @Test
    fun `commit numstat returns empty on failure output`() {
        assertTrue(GitCli.parseCommitNumstat("").isEmpty())
        assertTrue(GitCli.parseCommitNumstat("fatal: ambiguous argument").isEmpty())
    }

    // ------------------------------------------------------------ branches

    @Test
    fun `branches parse hash name subject and mark only the exact current`() {
        val out = "abc1234\u001fmain\u001fFix parser bug\n" +
            "def5678\u001forigin/main\u001fFix parser bug\n" +
            "987fedc\u001ffeature/x\u001fWork in progress\n"
        val list = GitCli.parseBranches(out, current = "main")
        assertEquals(3, list.size)
        assertEquals("main", list[0].name)
        assertTrue(list[0].isCurrent)
        assertEquals("Fix parser bug", list[0].lastSubject)
        // origin/main must NOT count as current — only the exact name does.
        assertEquals("origin/main", list[1].name)
        assertFalse(list[1].isCurrent)
        assertEquals("feature/x", list[2].name)
        assertEquals("Work in progress", list[2].lastSubject)
    }

    @Test
    fun `branches drop malformed rows and dedupe by name`() {
        val out = "abc1234\u001fmain\u001fone\ngarbage-no-separator\n" +
            "def5678\u001fmain\u001fduplicate\n\t\n\n"
        val list = GitCli.parseBranches(out, current = "other")
        assertEquals(1, list.size)
        assertEquals("main", list[0].name)
        assertFalse(list[0].isCurrent)
    }

    @Test
    fun `branches drop symbolic refs like origin HEAD aliases`() {
        val out = "abc1234\u001fmain\u001fFix parser bug\u001f\n" +
            "def5678\u001forigin\u001fwhatever\u001frefs/remotes/origin/main\n" +
            "987fedc\u001forigin/main\u001fFix parser bug\u001f\n"
        val list = GitCli.parseBranches(out, current = "main")
        // The `origin` symbolic-ref row is skipped; plain rows survive.
        assertEquals(2, list.size)
        assertEquals("main", list[0].name)
        assertEquals("origin/main", list[1].name)
    }

    @Test
    fun `branches empty output and blank current yield empty list`() {
        assertTrue(GitCli.parseBranches("", "").isEmpty())
        assertTrue(GitCli.parseBranches("fatal: not a repo\n", "main").isEmpty())
    }

    // -------------------------------------------------------------- stashes

    @Test
    fun `stashes parse index and subject in order`() {
        val out = "stash@{0}\u001fWIP widget\nstash@{1}\u001fon branch: older\n"
        val list = GitCli.parseStashes(out)
        assertEquals(2, list.size)
        assertEquals(0, list[0].index)
        assertEquals("WIP widget", list[0].subject)
        assertEquals(1, list[1].index)
        assertEquals("on branch: older", list[1].subject)
    }

    @Test
    fun `stashes drop malformed rows`() {
        val out = "not-a-ref\u001fnope\ngarbage\nstash@{x}\u001fno-index\n"
        assertTrue(GitCli.parseStashes(out).isEmpty())
        assertTrue(GitCli.parseStashes("").isEmpty())
    }

    @Test
    fun `stashes split on first separator only so subject keeps x1f`() {
        val out = "stash@{0}\u001fWIP\u001fsplit\n"
        val list = GitCli.parseStashes(out)
        assertEquals(1, list.size)
        assertEquals("WIP\u001fsplit", list[0].subject)
    }
}
