# Contributors

Minis Ultra is GPL-3.0-or-later. Copyright in each contribution stays with its
author; this file keeps the attribution that the license requires and that
`git log` alone makes easy to lose — a squashed merge carries only the merger's
name, so a contributor's work can ship without their name ever appearing in a
readable place.

Listed in order of first contribution. Add yourself (or be added) in the pull
request that lands your first change.

| Contributor | Contributions |
|---|---|
| **tall-1997** &lt;tall-1997@chaitin.com&gt; | Project maintainer. Linux/Android port, agent loop, tool surface, context and compaction pipeline, CI gates. |
| **Ethan** &lt;wsvn53@gmail.com&gt;, &lt;ethan@openminis.app&gt; | Repository foundations (CONTRIBUTING, issue/PR templates, BUILDING), the 1.13 Android line (chat, markdown, theming, interface), provider/thinking/storage work, iSH submodule tracking, locale helper scripts. |
| **doubao01** &lt;31365329+doubao01@users.noreply.github.com&gt; | Design-system unification: the token layer (primitive → semantic → component) with its scope/semantics/coverage gates, typed preferences (raw `SharedPreferences` 146 → 3 call sites), the restructured settings/composer surfaces, and 11 new CI gates (PR #410). |

## Attribution in history

Squash merges rewrite authorship into the merging account. Where a contribution
was squashed, the individual commits are still reachable and this table is the
canonical record:

- **#410** — authored by `doubao01` (`23f2231b`, replayed from 24 commits).
- **#432** — fused #430 + #431; the #431 half was authored by the maintainer's
  agent session and is recorded in the merge commit body.

## Reporting an omission

If your work is missing here, open an issue or a pull request adding your row —
no proof of authorship beyond the commit history is needed.