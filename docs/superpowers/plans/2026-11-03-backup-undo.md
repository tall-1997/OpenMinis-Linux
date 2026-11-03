# 备份撤销 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 恢复备份前自动生成文件级撤销点，恢复完成后用户可以一键回到恢复前的状态。

**Architecture:** 新增 `BackupUndoStore`（纯 JVM，注入 `filesDir`，无 Android 依赖）负责落点映射、快照、还原、清理；`BackupUndoBridge` 扩展函数把编排塞进 ViewModel 侧的调用点（`BackupViewModel` 已到架构基线上限，只能净 0 增长）；撤销后靠 `AppRestart` 重启进程让所有仓库重新读盘。

**Tech Stack:** Kotlin / JUnit4 / java.nio / AlarmManager（重启）

**Spec:** `docs/superpowers/specs/2026-11-03-backup-undo-design.md`

## Global Constraints

- 架构门禁：`BackupViewModel.kt` ceiling 1066、`BackupAndRestoreScreen.kt` ceiling 1798，**加行即失败**；新逻辑一律进新文件，老文件净 0 增长（必要时压缩注释对冲）。
- 新文件 ≤ 800 行。
- 本地不做编译验证（会话规则）；编译验证交云端 CI，本地只跑 python/JVM 纯逻辑测试与 kotlinc 语法过滤。
- 所有新增用户可见文案必须有 `values/` + 其余 15 个 locale 的翻译键（CI `L10n gate` 会跑 `check_new_untranslated.py`）。
- 不引入新依赖。

## Review Focus

- **撤销点生成失败时恢复被挡住**：磁盘满/IO 异常下恢复必须照常进行，报告标注"无撤销点"，用户不应该因为"备份不了撤销点"而恢复不了备份。
- **WAL 未落盘**：`capture` 前没做 `wal_checkpoint(FULL)`，撤销后会静默丢掉最近一次会话写入。
- **快照里不存在的落点**：撤销时必须**删除**目标目录（恢复新增的会话/文件正是要消失的东西）；只"复制回去"等于没撤销。
- **大目录把撤销点撑爆**：`minis-sessions` 可能有 GB 级附件，超上限必须降级为 `partial` 而不是失败或无限写盘。
- **撤销时 Room 仍打开**：文件被替换但句柄还指向旧 inode，重启进程前任何写入都会写进已删除的快照目标。

---

### Task 1: 落点映射（BackupUndoPaths）

**Files:**
- Create: `src/android/app/src/main/java/com/openminis/app/backup/BackupUndoPaths.kt`
- Test: `src/android/app/src/test/java/com/openminis/app/backup/BackupUndoPathsTest.kt`

**Interfaces:**
- Produces:
  - `internal fun pathsFor(categories: Set<BackupCategory>): List<String>` — 去重、稳定顺序（按 `BackupCategory` 声明序再按落点字面序）
  - `internal const val DB_DIR = "databases"` / `BODIES_DIR = "bodies"` / `SESSIONS_DIR = "minis-sessions"` / `PREFS_DIR = "shared_prefs"` / `GLOBAL_DIR = "minis-global"`

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun chatsMapsToDatabaseBodiesAndSessions() {
    assertEquals(
        listOf("databases", "bodies", "minis-sessions"),
        pathsFor(setOf(BackupCategory.CHATS)),
    )
}

@Test fun providersAndEnvShareThePrefsRootWithoutDuplicating() {
    val p = pathsFor(setOf(BackupCategory.PROVIDERS, BackupCategory.ENVIRONMENT_VARIABLES))
    assertEquals(listOf("shared_prefs"), p)
}

@Test fun nonBackupableCategoriesContributeNothing() {
    assertTrue(pathsFor(setOf(BackupCategory.ROOTFS, BackupCategory.CACHE)).isEmpty())
}
```

- [ ] **Step 2: 跑测试确认失败**（`BackupUndoPaths` 未定义 → 编译错）

- [ ] **Step 3: 实现**

映射表照 spec 第 3 节逐条落地（`SKILLS`/`MEMORY`/`MCP_SERVERS` 各自一个 `minis-global/*` 子目录，`SHARED_FILES` → `minis-global/shared`）。

- [ ] **Step 4: 跑测试确认通过**

---

### Task 2: 快照 / 列举 / 删除 / 清理

**Files:**
- Create: `src/android/app/src/main/java/com/openminis/app/backup/BackupUndoStore.kt`
- Test: `src/android/app/src/test/java/com/openminis/app/backup/BackupUndoStoreTest.kt`

**Interfaces:**
- Consumes: `pathsFor(...)`, `BackupCategory`
- Produces: 见 spec 第 4 节的 `capture` / `list` / `newest` / `delete` / `prune`，以及 `UndoPoint` / `CaptureResult` / `RestoreResult`

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun captureCopiesOnlyTheSelectedRoots() {
    // filesDir/{databases/minis.db, shared_prefs/a.xml, minis-global/skills/x.md}
    val r = BackupUndoStore(root) { 1_000L }.capture(setOf(BackupCategory.SKILLS))
    assertNull(r.error)
    assertTrue(File(root, "backup-undo/undo-1000/tree/minis-global/skills/x.md").isFile)
    assertFalse(File(root, "backup-undo/undo-1000/tree/databases").exists())
}

@Test fun listReadsBackWhatCaptureWrote() { /* id / categories / bytes 往返 */ }

@Test fun oversizedSubtreeMarksThePointPartial() {
    val r = store.capture(setOf(BackupCategory.CHATS), maxBytes = 1_000L)
    assertTrue(r.point!!.partial)
    assertTrue(r.point!!.skipped.isNotEmpty())
}

@Test fun pruneKeepsNewestN() { /* 4 份 → 保留 3 */ }

@Test fun corruptUndoDirIsSkippedAndRemoved() { /* 缺 manifest 目录不进 list 且被删 */ }

@Test fun deleteRemovesThePoint() { /* delete → list 为空 */ }
```

- [ ] **Step 2: 跑测试确认失败**

- [ ] **Step 3: 实现 capture / list / newest / delete / prune**

`manifest.json` 手工拼（`org.json` 在 JVM 单测里不可用 —— 改用 `java.util.Properties` 或自拼 JSON 再用 `kotlinx.serialization` 解；优先 `kotlinx.serialization`，项目已有依赖）。

- [ ] **Step 4: 跑测试确认通过**

---

### Task 3: 还原

**Files:**
- Modify: `BackupUndoStore.kt`
- Test: `BackupUndoStoreTest.kt`（追加用例）

**Interfaces:**
- Produces: `restore(point: UndoPoint): RestoreResult`

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun restoreBringsOverwrittenFilesBackAndDeletesAddedOnes() {
    // 快照时：skills/a.md = "old"；restore 后 skills/a.md = "new"、skills/b.md 新增
    // restore(point) 之后：a.md == "old"、b.md 不存在
}

@Test fun restoreDeletesRootsThatDidNotExistAtCaptureTime() {
    // 快照时没有 minis-global/memory；之后出现 → restore 后消失
}
```

- [ ] **Step 2: 跑测试确认失败**

- [ ] **Step 3: 实现 restore（先删目标、再从 tree 复制）**

- [ ] **Step 4: 跑测试确认通过**

---

### Task 4: 重启工具与 DB 关闭

**Files:**
- Create: `src/android/app/src/main/java/com/openminis/app/util/AppRestart.kt`
- Modify: `src/android/app/src/main/java/com/openminis/app/data/db/AppDatabase.kt`（+`closeForRestore()`）

**Interfaces:**
- Produces:
  - `object AppRestart { fun restart(context: Context) }`
  - `AppDatabase.Companion.closeForRestore()`

- [ ] **Step 1: 实现 AppRestart（AlarmManager + PendingIntent + killProcess）**
- [ ] **Step 2: 实现 `closeForRestore()`：`INSTANCE?.close(); INSTANCE = null`**
- [ ] **Step 3: kotlinc 语法过滤确认无新增真错**

---

### Task 5: ViewModel 接线

**Files:**
- Create: `src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupUndoBridge.kt`
- Modify: `BackupViewModel.kt`（净 0 增长：加调用点，压缩等价注释对冲）

**Interfaces:**
- Consumes: `BackupUndoStore`, `AppDatabase.closeForRestore`, `AppRestart`
- Produces:
  - `internal suspend fun BackupViewModel.captureUndoBeforeRestore(cats): UndoPoint?`
  - `internal fun BackupViewModel.undoRestore(point: UndoPoint)`
  - `val undoPoint: StateFlow<UndoPoint?>`（ViewModel 内新增，2 行）
  - `val undoPoints: StateFlow<List<UndoPoint>>`

- [ ] **Step 1: Bridge 实现三条流程（checkpoint → prune → capture；close DB → restore → restart）**
- [ ] **Step 2: `startRestore` 在 import 前调用 capture，结果写进 `_undoPoint`**
- [ ] **Step 3: 净 0 核对（`wc -l` + 架构门禁）**

---

### Task 6: UI 与文案

**Files:**
- Create: `src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupUndoUi.kt`
- Modify: `BackupAndRestoreScreen.kt`（净 0：调用点 + 注释对冲）
- Modify: `src/android/app/src/main/res/values/strings.xml` + 15 个 locale

**Interfaces:**
- Consumes: `undoPoint` / `undoPoints` / `undoRestore`
- Produces: `@Composable internal fun RestoreUndoSection(...)`

- [ ] **Step 1: 报告底部撤销按钮（destructive + 二次确认）**
- [ ] **Step 2: 恢复区撤销点说明行 + Discard**
- [ ] **Step 3: 6 个 string key × 16 locale**
- [ ] **Step 4: L10n gate 通过**

---

### Task 7: 门禁与提交

- [ ] **Step 1:** `python3 scripts/ci/check_architecture_policy.py`
- [ ] **Step 2:** `python3 scripts/ci/check_new_untranslated.py`（提交后跑）
- [ ] **Step 3:** kotlinc 新旧对比语法过滤
- [ ] **Step 4:** 分支 → 提交 → push → PR → CI 绿 → squash 合并 → 删分支
